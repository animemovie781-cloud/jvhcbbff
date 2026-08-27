package genius.DMTech.Vectr;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public class FileToolExecutor {

    /** Потолок на чтение файла агентом — защита от OOM на больших/бинарных файлах. */
    private static final int MAX_READ_BYTES = 2 * 1024 * 1024;

    private final Context context;

    public FileToolExecutor(Context context) {
        this.context = context;
    }

    public static class WriteDiffResult {
        public String summary;
        public String oldContent;
        public String newContent;
        public int added;
        public int removed;
    }

    private DocumentFile getRoot() {
        Uri rootUri = ProjectState.getInstance().projectRootUri;
        if (rootUri == null) return null;
        return DocumentFile.fromTreeUri(context, rootUri);
    }

    /**
     * Нормализует относительный путь внутри проекта.
     * Возвращает null при path traversal (..) / абсолютном пути / пустом имени.
     */
    static String normalizeRelativePath(String relativePath) {
        if (relativePath == null) return null;
        String p = relativePath.replace('\\', '/').trim();
        if (p.isEmpty() || ".".equals(p)) return ".";
        if (p.startsWith("/")) return null;
        // Windows-style absolute / URI scheme
        if (p.length() >= 2 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':') return null;
        if (p.contains("://")) return null;

        StringBuilder out = new StringBuilder();
        for (String part : p.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) return null;
            if (out.length() > 0) out.append('/');
            out.append(part);
        }
        return out.length() == 0 ? "." : out.toString();
    }

    // *** ФИКС name.java.txt: для всех файлов возвращаем application/octet-stream,
    //     чтобы SAF не дописывал .txt из-за незнакомых MIME-типов ***
    private static String mimeForFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot).toLowerCase();
            // изображениям и иконкам оставляем нормальный MIME — чтобы SAF не обосрался при открытии
            switch (ext) {
                case ".png": return "image/png";
                case ".jpg":
                case ".jpeg": return "image/jpeg";
                case ".gif": return "image/gif";
                case ".webp": return "image/webp";
                case ".svg": return "image/svg+xml";
                case ".ico": return "image/x-icon";
                // аудио/видео — тоже норм MIME
                case ".mp3": return "audio/mpeg";
                case ".wav": return "audio/wav";
                case ".mp4": return "video/mp4";
                // для всего остального — octet-stream, чтобы SAF не лепил .txt
                default: return "application/octet-stream";
            }
        }
        // если расширения нет — тоже octet-stream, а не text/plain
        return "application/octet-stream";
    }

    private DocumentFile resolve(String relativePath, boolean createIfMissing, boolean lastIsDirectory) {
        DocumentFile current = getRoot();
        if (current == null) return null;

        String normalized = normalizeRelativePath(relativePath);
        if (normalized == null) return null;
        if (".".equals(normalized)) return current;

        String[] parts = normalized.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            DocumentFile next = current.findFile(part);
            boolean isLast = (i == parts.length - 1);

            if (next == null) {
                if (!createIfMissing) return null;
                if (isLast && !lastIsDirectory) {
                    String mime = mimeForFile(part);
                    next = current.createFile(mime, part);
                } else {
                    next = current.createDirectory(part);
                }
                if (next == null) return null;
            }
            current = next;
        }
        return current;
    }

    /** Existing project file URI, or null. */
    public Uri resolveFileUri(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        DocumentFile file = resolve(relativePath, false, false);
        if (file == null || !file.exists() || file.isDirectory()) return null;
        return file.getUri();
    }

    /**
     * Walks the project tree and returns relative file paths (max {@code maxFiles}).
     * Skips hidden / build caches by simple name heuristics.
     */
    public List<String> listProjectFilePaths(int maxFiles) {
        List<String> out = new ArrayList<>();
        DocumentFile root = getRoot();
        if (root == null) return out;

        Queue<Object[]> q = new LinkedList<>();
        q.add(new Object[]{root, ""});
        while (!q.isEmpty() && out.size() < maxFiles) {
            Object[] node = q.poll();
            DocumentFile dir = (DocumentFile) node[0];
            String prefix = (String) node[1];
            DocumentFile[] children = dir.listFiles();
            if (children == null) continue;
            for (DocumentFile child : children) {
                String name = child.getName();
                if (name == null || name.isEmpty()) continue;
                if (name.startsWith(".")) continue;
                if ("build".equalsIgnoreCase(name) || "node_modules".equalsIgnoreCase(name)
                        || ".git".equals(name) || "bin".equalsIgnoreCase(name)
                        || "obj".equalsIgnoreCase(name)) {
                    continue;
                }
                String rel = prefix.isEmpty() ? name : prefix + "/" + name;
                if (child.isDirectory()) {
                    q.add(new Object[]{child, rel});
                } else {
                    out.add(rel);
                    if (out.size() >= maxFiles) break;
                }
            }
        }
        return out;
    }

    public String readFile(String path) {
        try {
            if (normalizeRelativePath(path) == null) {
                return "ОШИБКА: недопустимый путь (только относительные пути внутри проекта): " + path;
            }
            DocumentFile file = resolve(path, false, false);
            if (file == null || !file.exists()) return "ОШИБКА: файл не найден: " + path;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean truncated = false;
            try (InputStream in = context.getContentResolver().openInputStream(file.getUri())) {
                if (in == null) return "ОШИБКА чтения: не удалось открыть поток";
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    int room = MAX_READ_BYTES - baos.size();
                    if (room <= 0) {
                        truncated = true;
                        break;
                    }
                    baos.write(buf, 0, Math.min(n, room));
                    if (n > room) {
                        truncated = true;
                        break;
                    }
                }
            }
            String text = baos.toString(StandardCharsets.UTF_8.name());
            if (truncated) {
                return text + "\n\n…[обрезано: файл больше "
                        + (MAX_READ_BYTES / 1024) + " КБ — читай частями или укажи меньший фрагмент]";
            }
            return text;
        } catch (Exception e) {
            return "ОШИБКА чтения: " + e.getMessage();
        }
    }

    /** Полное чтение для мутаций — усечённый файл нельзя писать обратно. */
    private String readFileForMutate(String path) {
        String content = readFile(path);
        if (content != null && content.contains("…[обрезано:")) {
            return "ОШИБКА: файл слишком большой для правки целиком (> "
                    + (MAX_READ_BYTES / 1024) + " КБ). Читай частями или уменьши файл.";
        }
        return content;
    }

    public String writeFile(String path, String content) {
        try {
            if (normalizeRelativePath(path) == null) {
                return "ОШИБКА: недопустимый путь (только относительные пути внутри проекта): " + path;
            }
            DocumentFile file = resolve(path, true, false);
            if (file == null) return "ОШИБКА: не смог создать файл: " + path;

            try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri(), "wt")) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return "OK: записано " + content.length() + " символов в " + path;
        } catch (Exception e) {
            return "ОШИБКА записи: " + e.getMessage();
        }
    }

    public WriteDiffResult writeFileDetailed(String path, String newContent) {
        WriteDiffResult result = new WriteDiffResult();

        String oldContent = readFileForMutate(path);
        if (oldContent.startsWith("ОШИБКА")) oldContent = "";

        String writeResult = writeFile(path, newContent);
        DiffUtil.DiffResult diff = DiffUtil.diffLines(oldContent, newContent);

        result.summary = writeResult + " (+" + diff.added + " -" + diff.removed + ")";
        result.oldContent = oldContent;
        result.newContent = newContent;
        result.added = diff.added;
        result.removed = diff.removed;
        return result;
    }

    public WriteDiffResult searchReplaceDetailed(String path, String oldString, String newString, boolean replaceAll) {
        WriteDiffResult result = new WriteDiffResult();
        if (oldString == null || oldString.isEmpty()) {
            result.summary = "ОШИБКА: old_string пустой";
            result.oldContent = "";
            result.newContent = "";
            result.added = 0;
            result.removed = 0;
            return result;
        }
        if (newString == null) newString = "";

        String content = readFileForMutate(path);
        if (content.startsWith("ОШИБКА")) {
            result.summary = content;
            result.oldContent = "";
            result.newContent = "";
            return result;
        }

        int count = 0;
        int from = 0;
        while (from <= content.length()) {
            int idx = content.indexOf(oldString, from);
            if (idx < 0) break;
            count++;
            from = idx + Math.max(oldString.length(), 1);
            if (!replaceAll && count > 1) break;
        }

        if (count == 0) {
            result.summary = "ОШИБКА: old_string не найден в " + path +
                    ". Прочитай файл и укажи точный фрагмент.";
            result.oldContent = content;
            result.newContent = content;
            return result;
        }
        if (count > 1 && !replaceAll) {
            result.summary = "ОШИБКА: найдено " + count + " вхождений old_string в " + path +
                    ". Уточни контекст или передай replace_all=true.";
            result.oldContent = content;
            result.newContent = content;
            return result;
        }

        String newContent;
        if (replaceAll) {
            newContent = content.replace(oldString, newString);
        } else {
            int idx = content.indexOf(oldString);
            newContent = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
        }

        WriteDiffResult written = writeFileDetailed(path, newContent);
        written.summary = "OK search_replace " + path + " ×" + (replaceAll ? count : 1)
                + " (+" + written.added + " -" + written.removed + ")";
        return written;
    }

    public WriteDiffResult applyPatchDetailed(String path, String patch) {
        WriteDiffResult result = new WriteDiffResult();
        if (patch == null || patch.trim().isEmpty()) {
            result.summary = "ОШИБКА: пустой patch";
            return result;
        }

        String content = readFileForMutate(path);
        if (content.startsWith("ОШИБКА")) {
            result.summary = content;
            return result;
        }

        String working = content;
        java.util.regex.Pattern block = java.util.regex.Pattern.compile(
                "<<<<<<<\\s*SEARCH\\s*\\r?\\n(.*?)\\r?\\n=======\\s*\\r?\\n(.*?)\\r?\\n>>>>>>>\\s*REPLACE",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = block.matcher(patch);
        int applied = 0;
        while (m.find()) {
            String oldStr = m.group(1);
            String newStr = m.group(2);
            if (oldStr == null) continue;
            if (newStr == null) newStr = "";
            // нормализуем хвостовые \r
            oldStr = oldStr.replace("\r\n", "\n");
            newStr = newStr.replace("\r\n", "\n");
            int idx = working.indexOf(oldStr);
            if (idx < 0) {
                result.summary = "ОШИБКА apply_patch: блок #" + (applied + 1)
                        + " не найден в " + path + ". Уже применено: " + applied;
                result.oldContent = content;
                result.newContent = working;
                return result;
            }
            working = working.substring(0, idx) + newStr + working.substring(idx + oldStr.length());
            applied++;
        }

        if (applied == 0) {
            result.summary = "ОШИБКА: в patch нет блоков <<<<<<< SEARCH / >>>>>>> REPLACE";
            result.oldContent = content;
            result.newContent = content;
            return result;
        }

        WriteDiffResult written = writeFileDetailed(path, working);
        written.summary = "OK apply_patch " + path + " блоков=" + applied
                + " (+" + written.added + " -" + written.removed + ")";
        return written;
    }

    public String listFiles(String path) {
        try {
            if (path != null && !path.isEmpty() && !".".equals(path)
                    && normalizeRelativePath(path) == null) {
                return "ОШИБКА: недопустимый путь: " + path;
            }
            DocumentFile dir = (path == null || path.isEmpty() || path.equals("."))
                    ? getRoot() : resolve(path, false, true);

            if (dir == null || !dir.exists()) return "ОШИБКА: папка не найдена: " + path;

            StringBuilder sb = new StringBuilder();
            for (DocumentFile child : dir.listFiles()) {
                sb.append(child.isDirectory() ? "[папка] " : "[файл] ").append(child.getName()).append("\n");
            }
            return sb.length() == 0 ? "(пусто)" : sb.toString();
        } catch (Exception e) {
            return "ОШИБКА: " + e.getMessage();
        }
    }

    public String runLocalCommand(String command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            File cwd = resolveProjectDir();
            if (cwd != null) pb.directory(cwd);
            pb.redirectErrorStream(false);
            process = pb.start();
            final Process proc = process;
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread tOut = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null)
                        out.append(line).append("\n");
                } catch (Exception ignored) {}
            }, "vectr-cmd-out");
            Thread tErr = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null)
                        err.append("[stderr] ").append(line).append("\n");
                } catch (Exception ignored) {}
            }, "vectr-cmd-err");
            tOut.start();
            tErr.start();
            boolean finished = waitForProcess(proc, 30_000);
            if (!finished) {
                killProcess(proc);
                return "ОШИБКА: команда не завершилась за 30с";
            }
            tOut.join(2000);
            tErr.join(2000);
            int exit = proc.exitValue();
            StringBuilder combined = new StringBuilder();
            if (cwd != null) combined.append("[cwd ").append(cwd.getAbsolutePath()).append("]\n");
            combined.append(out);
            combined.append(err);
            return "OK (exit " + exit + ")\n" + combined.toString().trim();
        } catch (Exception e) {
            if (process != null) {
                try { killProcess(process); } catch (Exception ignored) {}
            }
            return "ОШИБКА выполнения: " + e.getMessage();
        }
    }

    /** Путь проекта для UI / Termux cwd, если удалось резолвнуть с SAF. */
    public String getProjectCwdHint() {
        File dir = resolveProjectDir();
        return dir != null ? dir.getAbsolutePath() : null;
    }

    /** file:// или primary:… tree URI → реальный File, иначе null. */
    private File resolveProjectDir() {
        Uri rootUri = ProjectState.getInstance().projectRootUri;
        if (rootUri == null) return null;
        try {
            if ("file".equalsIgnoreCase(rootUri.getScheme())) {
                String path = rootUri.getPath();
                if (path != null) {
                    File f = new File(path);
                    if (f.isDirectory()) return f;
                }
            }
            String docId = android.provider.DocumentsContract.getTreeDocumentId(rootUri);
            if (docId != null && docId.contains(":")) {
                String[] parts = docId.split(":", 2);
                String volume = parts[0];
                String rel = parts.length > 1 ? parts[1] : "";
                File base = null;
                if ("primary".equalsIgnoreCase(volume)) {
                    base = android.os.Environment.getExternalStorageDirectory();
                }
                if (base != null) {
                    File dir = rel.isEmpty() ? base : new File(base, rel);
                    if (dir.isDirectory()) return dir;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean waitForProcess(Process proc, long timeoutMs) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= 26) {
            return proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                proc.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(50);
            }
        }
        try {
            proc.exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            return false;
        }
    }

    private static void killProcess(Process proc) {
        if (Build.VERSION.SDK_INT >= 26) {
            proc.destroyForcibly();
        } else {
            proc.destroy();
        }
    }

    public String runTermuxCommand(String command) {
        BroadcastReceiver receiver = null;
        try {
            String tag = "vectr_" + UUID.randomUUID().toString().substring(0, 8);
            IntentFilter filter = new IntentFilter(tag);
            final CountDownLatch latch = new CountDownLatch(1);
            final StringBuilder output = new StringBuilder();
            final int[] exitCode = {-1};

            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String stdout = null, stderr = null;
                    int code = -1;

                    try {
                        Object r = intent.getExtras() != null ? intent.getExtras().get("result") : null;
                        if (r instanceof android.os.Bundle) {
                            android.os.Bundle rb = (android.os.Bundle) r;
                            if (rb.containsKey("exitCode")) code = rb.getInt("exitCode", -1);
                            if (rb.containsKey("stdout")) stdout = rb.getString("stdout");
                            if (rb.containsKey("stderr")) stderr = rb.getString("stderr");
                        }
                    } catch (Exception ignored) {}

                    if (stdout == null) {
                        android.os.Bundle ex = intent.getExtras();
                        if (ex != null) {
                            if (ex.containsKey("com.termux.app.EXTRA_STDOUT"))
                                stdout = ex.getString("com.termux.app.EXTRA_STDOUT");
                            if (ex.containsKey("com.termux.app.EXTRA_EXIT_CODE"))
                                code = ex.getInt("com.termux.app.EXTRA_EXIT_CODE", -1);
                        }
                    }

                    exitCode[0] = code;
                    if (stdout != null) output.append(stdout);
                    if (stderr != null && !stderr.isEmpty())
                        output.append("\n[stderr] ").append(stderr);
                    latch.countDown();
                }
            };

            if (Build.VERSION.SDK_INT >= 33) {
                // NOT_EXPORTED — колбэк только из нашего пакета (PendingIntent.setPackage)
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }

            String safeCmd = command.replace("'", "'\\''");
            File projectDir = resolveProjectDir();
            if (projectDir != null) {
                String p = projectDir.getAbsolutePath().replace("'", "'\\''");
                safeCmd = "cd '" + p + "' && " + safeCmd;
            }
            Intent piIntent = new Intent(tag);
            piIntent.setPackage(context.getPackageName());
            int reqCode = tag.hashCode();
            PendingIntent pi = PendingIntent.getBroadcast(context, reqCode,
                    piIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Intent intent = new Intent();
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", safeCmd});
            String workDir = projectDir != null
                    ? projectDir.getAbsolutePath()
                    : "/data/data/com.termux/files/home";
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);

            try {
                context.startService(intent);
            } catch (Exception e) {
                try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
                receiver = null;
                return runTermuxViaAm(command);
            }

            boolean done = latch.await(30, TimeUnit.SECONDS);

            if (!done) return "ОШИБКА: Termux не ответил за 30с";
            return "OK (exit " + exitCode[0] + ")\n" + output.toString().trim();
        } catch (Exception e) {
            return "ОШИБКА Termux: " + e.getMessage();
        } finally {
            if (receiver != null) {
                try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
            }
        }
    }

    private String runTermuxViaAm(String command) {
        try {
            String safeCmd = command.replace("'", "'\\''");
            String amCmd = "am startservice --user 0 -n com.termux/com.termux.app.RunCommandService "
                    + "-e com.termux.RUN_COMMAND_PATH /data/data/com.termux/files/usr/bin/bash "
                    + "--esa com.termux.RUN_COMMAND_ARGUMENTS '-c','" + safeCmd + "' "
                    + "-e com.termux.RUN_COMMAND_WORKDIR /data/data/com.termux/files/home "
                    + "-e com.termux.RUN_COMMAND_SESSION_ACTION 0";
            Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/sh", "-c", amCmd + " 2>&1"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            String output = sb.toString().trim();
            if (output.contains("Not found") || output.contains("Error")
                    || output.contains("does not exist")) {
                return "ОШИБКА: Termux или Termux:Tasker не установлены. "
                        + "Установи оба из F-Droid.";
            }
            if (p.exitValue() != 0) {
                return "ОШИБКА Termux: " + output;
            }
            return "OK: команда отправлена в Termux (через am)";
        } catch (Exception e2) {
            return "ОШИБКА: Termux недоступен. Установи Termux и Termux:Tasker из F-Droid. "
                    + e2.getMessage();
        }
    }

    public String execute(String toolName, JSONObject arguments) {
        try {
            switch (toolName) {
                case "read_file": return readFile(arguments.getString("path"));
                case "write_file": return writeFile(arguments.getString("path"), arguments.getString("content"));
                case "list_files": return listFiles(arguments.optString("path", "."));
                case "search_replace": {
                    boolean all = "true".equalsIgnoreCase(arguments.optString("replace_all", "false"));
                    return searchReplaceDetailed(
                            arguments.getString("path"),
                            arguments.getString("old_string"),
                            arguments.getString("new_string"),
                            all).summary;
                }
                case "apply_patch":
                    return applyPatchDetailed(
                            arguments.getString("path"),
                            arguments.getString("patch")).summary;
                case "web_search": {
                    if (!AiConfig.isWebSearchEnabled(context)) {
                        return "ОШИБКА: поиск в сети выключен пользователем";
                    }
                    int max = 5;
                    try {
                        String mr = arguments.optString("max_results", "5").trim();
                        if (!mr.isEmpty()) max = Integer.parseInt(mr);
                    } catch (Exception ignored) {}
                    return WebSearchTool.search(arguments.getString("query"), max);
                }
                case "fetch_url":
                    if (!AiConfig.isWebSearchEnabled(context)) {
                        return "ОШИБКА: поиск в сети выключен пользователем";
                    }
                    return WebSearchTool.fetchUrl(arguments.getString("url"));
                case "run_command":
                    return "ИСПОЛЬЗУЙ run_command через ChatFragment (требует подтверждения)";
                default: return "ОШИБКА: неизвестная функция " + toolName;
            }
        } catch (JSONException e) {
            return "ОШИБКА парсинга аргументов: " + e.getMessage();
        }
    }
}
