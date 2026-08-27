package genius.DMTech.Vectr;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Контекст открытого редактора для системного промпта агента.
 */
public final class EditorContext {

    private EditorContext() {}

    public static class Diagnostic {
        public final int line; // 1-based
        public final String severity; // error | warning
        public final String message;

        public Diagnostic(int line, String severity, String message) {
            this.line = line;
            this.severity = severity;
            this.message = message;
        }
    }

    /** Простые эвристики без LSP: скобки, кавычки, TODO/FIXME. */
    public static List<Diagnostic> analyze(String text, String fileName) {
        List<Diagnostic> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        String[] lines = text.split("\n", -1);
        int brace = 0, paren = 0, bracket = 0;
        boolean inBlockComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int lineNo = i + 1;

            if (trimmed.contains("TODO") || trimmed.contains("FIXME") || trimmed.contains("XXX")) {
                out.add(new Diagnostic(lineNo, "warning", "Маркер: " + trimMsg(trimmed, 80)));
            }

            // грубый проход по символам вне строк
            boolean inString = false;
            char stringQuote = 0;
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                char prev = c > 0 ? line.charAt(c - 1) : 0;

                if (!inString && c + 1 < line.length() && ch == '/' && line.charAt(c + 1) == '*') {
                    inBlockComment = true;
                    c++;
                    continue;
                }
                if (inBlockComment && c + 1 < line.length() && ch == '*' && line.charAt(c + 1) == '/') {
                    inBlockComment = false;
                    c++;
                    continue;
                }
                if (inBlockComment) continue;

                if (!inString && ch == '/' && c + 1 < line.length() && line.charAt(c + 1) == '/') break;

                if ((ch == '"' || ch == '\'') && prev != '\\') {
                    if (!inString) {
                        inString = true;
                        stringQuote = ch;
                    } else if (ch == stringQuote) {
                        inString = false;
                    }
                    continue;
                }
                if (inString) continue;

                if (ch == '{') brace++;
                else if (ch == '}') brace--;
                else if (ch == '(') paren++;
                else if (ch == ')') paren--;
                else if (ch == '[') bracket++;
                else if (ch == ']') bracket--;

                if (brace < 0) {
                    out.add(new Diagnostic(lineNo, "error", "Лишняя закрывающая }"));
                    brace = 0;
                }
                if (paren < 0) {
                    out.add(new Diagnostic(lineNo, "error", "Лишняя закрывающая )"));
                    paren = 0;
                }
                if (bracket < 0) {
                    out.add(new Diagnostic(lineNo, "error", "Лишняя закрывающая ]"));
                    bracket = 0;
                }
            }
        }

        if (brace > 0) out.add(new Diagnostic(lines.length, "error", "Не хватает } — открыто ещё " + brace));
        if (paren > 0) out.add(new Diagnostic(lines.length, "error", "Не хватает ) — открыто ещё " + paren));
        if (bracket > 0) out.add(new Diagnostic(lines.length, "error", "Не хватает ] — открыто ещё " + bracket));

        // truncate
        if (out.size() > 40) {
            return new ArrayList<>(out.subList(0, 40));
        }
        return out;
    }

    public static String buildPromptBlock() {
        ProjectState state = ProjectState.getInstance();
        OpenFileTab tab = state.getCurrentTab();
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== Контекст редактора Vectr ===\n");

        if (state.openTabs == null || state.openTabs.isEmpty()) {
            sb.append("Открытых файлов нет.\n");
            return sb.toString();
        }

        sb.append("Открытые вкладки: ");
        for (int i = 0; i < state.openTabs.size(); i++) {
            OpenFileTab t = state.openTabs.get(i);
            if (i > 0) sb.append(", ");
            String label = t.relativePath != null && !t.relativePath.isEmpty() ? t.relativePath : t.name;
            if (i == state.currentTabIndex) sb.append("[").append(label).append("]");
            else sb.append(label);
        }
        sb.append("\n");

        if (tab == null) return sb.toString();

        String path = tab.relativePath != null && !tab.relativePath.isEmpty() ? tab.relativePath : tab.name;
        sb.append("Активный файл: `").append(path).append("`\n");
        sb.append("Курсор: строка ").append(Math.max(1, tab.cursorLine))
                .append(", колонка ").append(Math.max(0, tab.cursorCh)).append("\n");

        if (tab.selection != null && !tab.selection.isEmpty()) {
            String sel = tab.selection;
            if (sel.length() > 2000) sel = sel.substring(0, 2000) + "\n…(обрезано)";
            sb.append("Выделение пользователя:\n```\n").append(sel).append("\n```\n");
        }

        if (tab.content != null && !tab.content.isEmpty()) {
            String near = extractNearby(tab.content, Math.max(1, tab.cursorLine), 18);
            if (!near.isEmpty()) {
                sb.append("Код около курсора (±18 строк):\n```\n").append(near).append("\n```\n");
            }
        }

        List<Diagnostic> diags = analyze(tab.content, tab.name);
        if (!diags.isEmpty()) {
            sb.append("Диагностика (эвристика IDE):\n");
            int n = Math.min(diags.size(), 12);
            for (int i = 0; i < n; i++) {
                Diagnostic d = diags.get(i);
                sb.append("- L").append(d.line).append(" [").append(d.severity).append("] ")
                        .append(d.message).append("\n");
            }
        }

        sb.append("=== конец контекста ===\n");
        return sb.toString();
    }

    public static String extractNearby(String content, int cursorLine1, int radius) {
        if (content == null || content.isEmpty()) return "";
        String[] lines = content.split("\n", -1);
        int idx = Math.min(Math.max(cursorLine1 - 1, 0), lines.length - 1);
        int from = Math.max(0, idx - radius);
        int to = Math.min(lines.length - 1, idx + radius);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(String.format("%4d", i + 1));
            sb.append(i == idx ? " ▶ " : " │ ");
            sb.append(lines[i]);
            if (i < to) sb.append('\n');
        }
        if (sb.length() > 6000) return sb.substring(0, 6000) + "\n…";
        return sb.toString();
    }

    public static String relativePathForUri(Uri uri, String fallbackName) {
        if (uri == null) return fallbackName;
        ProjectState state = ProjectState.getInstance();
        if (state.openTabs != null) {
            for (OpenFileTab t : state.openTabs) {
                if (uri.equals(t.uri) && t.relativePath != null && !t.relativePath.isEmpty()) {
                    return t.relativePath;
                }
            }
        }
        return fallbackName != null ? fallbackName : uri.getLastPathSegment();
    }

    private static String trimMsg(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
