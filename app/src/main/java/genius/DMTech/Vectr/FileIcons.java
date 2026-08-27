package genius.DMTech.Vectr;

/**
 * Иконки файлов по расширению / имени. Цвет заложен в drawable —
 * на ImageView не вешать глобальный tint (кроме folder/generic).
 */
public final class FileIcons {

    private FileIcons() {}

    public static int forEntry(boolean isDirectory, boolean isParent, String name) {
        if (isParent) return R.drawable.ic_folder_up;
        if (isDirectory) return R.drawable.ic_folder;
        return forFileName(name);
    }

    /** true = можно красить accent-ом; false = в иконке свой цвет */
    public static boolean usesAccentTint(boolean isDirectory, boolean isParent, String name) {
        if (isParent || isDirectory) return true;
        int res = forFileName(name);
        return res == R.drawable.ic_file_generic
                || res == R.drawable.ic_file_text
                || res == R.drawable.ic_file_image
                || res == R.drawable.ic_file_archive;
    }

    public static int forFileName(String name) {
        if (name == null || name.isEmpty()) return R.drawable.ic_file_generic;
        String lower = name.toLowerCase();

        // special filenames
        if (lower.equals("dockerfile") || lower.startsWith("dockerfile.")) return R.drawable.ic_file_docker;
        if (lower.equals("makefile") || lower.equals("cmakelists.txt")) return R.drawable.ic_file_generic;
        if (lower.equals("gradlew") || lower.equals("gradlew.bat")) return R.drawable.ic_file_gradle;
        if (lower.equals("package.json") || lower.equals("package-lock.json")) return R.drawable.ic_file_js;
        if (lower.equals("androidmanifest.xml")) return R.drawable.ic_file_xml;

        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return R.drawable.ic_file_generic;
        String ext = lower.substring(dot + 1);

        switch (ext) {
            case "java": return R.drawable.ic_file_java;
            case "kt":
            case "kts": return R.drawable.ic_file_kotlin;
            case "js":
            case "mjs":
            case "cjs": return R.drawable.ic_file_js;
            case "ts":
            case "tsx": return R.drawable.ic_file_ts;
            case "jsx": return R.drawable.ic_file_js;
            case "py":
            case "pyw": return R.drawable.ic_file_python;
            case "xml": return R.drawable.ic_file_xml;
            case "html":
            case "htm": return R.drawable.ic_file_html;
            case "css":
            case "scss":
            case "sass":
            case "less": return R.drawable.ic_file_css;
            case "json": return R.drawable.ic_file_json;
            case "md":
            case "markdown": return R.drawable.ic_file_md;
            case "c":
            case "h": return R.drawable.ic_file_c;
            case "cpp":
            case "cc":
            case "cxx":
            case "hpp":
            case "hh":
            case "hxx": return R.drawable.ic_file_cpp;
            case "gradle":
            case "properties":
            case "toml":
            case "yaml":
            case "yml":
            case "ini":
            case "cfg":
            case "conf": return R.drawable.ic_file_gradle;
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
            case "bmp":
            case "ico":
            case "svg": return R.drawable.ic_file_image;
            case "txt":
            case "log":
            case "csv": return R.drawable.ic_file_text;
            case "zip":
            case "jar":
            case "apk":
            case "aar":
            case "rar":
            case "7z":
            case "gz":
            case "tar": return R.drawable.ic_file_archive;
            case "sh":
            case "bash":
            case "bat":
            case "cmd":
            case "ps1": return R.drawable.ic_file_shell;
            case "sql": return R.drawable.ic_file_sql;
            case "go": return R.drawable.ic_file_go;
            case "rs": return R.drawable.ic_file_rust;
            case "swift": return R.drawable.ic_file_swift;
            case "dart": return R.drawable.ic_file_dart;
            case "php": return R.drawable.ic_file_php;
            case "rb": return R.drawable.ic_file_ruby;
            default: return R.drawable.ic_file_generic;
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** MIME для createFile через SAF (как в FileToolExecutor). */
    public static String mimeForFile(String name) {
        if (name == null) return "application/octet-stream";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot).toLowerCase();
        switch (ext) {
            case ".png": return "image/png";
            case ".jpg":
            case ".jpeg": return "image/jpeg";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".svg": return "image/svg+xml";
            case ".ico": return "image/x-icon";
            case ".mp3": return "audio/mpeg";
            case ".wav": return "audio/wav";
            case ".mp4": return "video/mp4";
            case ".json": return "application/json";
            case ".xml": return "text/xml";
            case ".html":
            case ".htm": return "text/html";
            case ".css": return "text/css";
            case ".js": return "application/javascript";
            case ".md": return "text/markdown";
            case ".txt":
            case ".log": return "text/plain";
            default: return "application/octet-stream";
        }
    }
}
