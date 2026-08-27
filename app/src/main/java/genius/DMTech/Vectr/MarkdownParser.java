package genius.DMTech.Vectr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownParser {

    public static String toHtml(String md) {
        return toHtml(md, "Copy", ChatHtmlTheme.fromNightFallback());
    }

    public static String toHtml(String md, String copyLabel) {
        return toHtml(md, copyLabel, ChatHtmlTheme.fromNightFallback());
    }

    public static String toHtml(String md, String copyLabel, ChatHtmlTheme theme) {
        if (md == null || md.isEmpty()) return "";
        if (theme == null) theme = ChatHtmlTheme.fromNightFallback();

        List<String> blocks = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        int start = 0;
        while (true) {
            int open = md.indexOf("```", start);
            if (open == -1) {
                raw.append(md.substring(start));
                break;
            }
            raw.append(md, start, open);
            int close = md.indexOf("```", open + 3);
            if (close == -1) {
                raw.append(md.substring(open));
                break;
            }
            String code = md.substring(open + 3, close);
            String lang = "";
            int nl = code.indexOf('\n');
            if (nl != -1) {
                String firstLine = code.substring(0, nl).trim();
                if (!firstLine.isEmpty() && !firstLine.contains(" ")) {
                    lang = firstLine;
                    code = code.substring(nl + 1);
                }
            }
            String html = buildCodeBlock(escapeHtml(code), lang, blocks.size(), copyLabel, theme);
            blocks.add(html);
            raw.append("§§CODE").append(blocks.size() - 1).append("§§");
            start = close + 3;
        }

        String text = raw.toString();

        Matcher inlineMatcher = Pattern.compile("`([^`]+)`").matcher(text);
        StringBuilder withMarkers = new StringBuilder();
        int lastEnd = 0;
        while (inlineMatcher.find()) {
            withMarkers.append(text, lastEnd, inlineMatcher.start());
            String html = "<code style=\"background:" + theme.codeBg + ";color:" + theme.codeFg
                    + ";padding:1px 5px;border-radius:4px;font-family:monospace;font-size:13px;border:1px solid "
                    + theme.border + ";\">"
                    + escapeHtml(inlineMatcher.group(1)) + "</code>";
            blocks.add(html);
            withMarkers.append("§§CODE").append(blocks.size() - 1).append("§§");
            lastEnd = inlineMatcher.end();
        }
        withMarkers.append(text.substring(lastEnd));
        text = withMarkers.toString();

        text = processTables(text, blocks, theme);
        text = escapeHtmlSafe(text);

        text = text.replaceAll("(?m)^###### ([^§].*)$", "<h6>$1</h6>");
        text = text.replaceAll("(?m)^##### ([^§].*)$", "<h5>$1</h5>");
        text = text.replaceAll("(?m)^#### ([^§].*)$", "<h4>$1</h4>");
        text = text.replaceAll("(?m)^### ([^§].*)$", "<h3>$1</h3>");
        text = text.replaceAll("(?m)^## ([^§].*)$", "<h2>$1</h2>");
        text = text.replaceAll("(?m)^# ([^§].*)$", "<h1>$1</h1>");

        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        text = text.replaceAll("__(.+?)__", "<b>$1</b>");
        text = text.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<i>$1</i>");
        text = text.replaceAll("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)", "<i>$1</i>");
        text = text.replaceAll("~~(.+?)~~", "<s>$1</s>");

        text = text.replaceAll("(?m)^\\* (.+)$", "• $1");
        text = text.replaceAll("(?m)^- (.+)$", "• $1");

        text = text.replace("\n", "<br>");

        // ссылки до вставки code-блоков — иначе URL внутри <pre> становятся <a>
        text = text.replaceAll("(https?://[\\w./?=&%-]+)",
                "<a href=\"$1\" style=\"color:" + theme.accent + ";\">$1</a>");

        for (int i = blocks.size() - 1; i >= 0; i--) {
            text = text.replace("§§CODE" + i + "§§", blocks.get(i));
        }

        return text;
    }

    private static String buildCodeBlock(String code, String lang, int index, String copyLabel,
                                         ChatHtmlTheme theme) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:8px 0;border-radius:8px;overflow:hidden;background:")
                .append(theme.codeBg).append(";border:1px solid ").append(theme.border).append(";\">");
        sb.append("<div style=\"display:flex;align-items:center;justify-content:space-between;background:")
                .append(theme.headerBg).append(";padding:6px 10px;\">");
        sb.append("<span style=\"color:").append(theme.muted)
                .append(";font-size:11px;font-family:monospace;\">");
        sb.append(lang.isEmpty() ? "code" : escapeHtml(lang));
        sb.append("</span>");
        sb.append("<span onclick=\"copyCode(this)\" style=\"cursor:pointer;display:flex;align-items:center;gap:4px;color:")
                .append(theme.accent).append(";font-size:11px;\">");
        sb.append("<svg width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='")
                .append(theme.accent)
                .append("' stroke-width='2'><rect x='9' y='9' width='13' height='13' rx='2' ry='2'/><path d='M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1'/></svg>");
        sb.append(escapeHtml(copyLabel != null ? copyLabel : "Copy"));
        sb.append("</span>");
        sb.append("</div>");
        sb.append("<pre style=\"margin:0;padding:12px;color:").append(theme.preFg)
                .append(";font-family:monospace;font-size:12px;white-space:pre-wrap;overflow-x:auto;\">");
        sb.append(code);
        sb.append("</pre>");
        sb.append("</div>");
        sb.append("<span id=\"code").append(index).append("\" style=\"display:none;\">");
        sb.append(escapeHtml(code));
        sb.append("</span>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeHtmlSafe(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '§' && i + 6 < s.length() && s.startsWith("§§CODE", i)) {
                int end = s.indexOf("§§", i + 6);
                if (end != -1) {
                    sb.append(s, i, end + 2);
                    i = end + 2;
                    continue;
                }
            }
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String processTables(String text, List<String> blocks, ChatHtmlTheme theme) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.startsWith("|") && line.endsWith("|") && line.indexOf('|', 1) > 0) {
                if (i + 1 < lines.length) {
                    String sep = lines[i + 1].trim();
                    if (sep.startsWith("|") && sep.contains("-")) {
                        StringBuilder table = new StringBuilder();
                        table.append("<div style=\"overflow-x:auto;margin:8px 0;\">");
                        table.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px;min-width:240px;\">");
                        table.append("<thead><tr style=\"background:").append(theme.headerBg).append(";\">");
                        for (String h : splitRow(line)) {
                            table.append("<th style=\"padding:6px 10px;border:1px solid ")
                                    .append(theme.border).append(";text-align:left;color:")
                                    .append(theme.accent).append(";\">")
                                    .append(cellToHtml(h.trim(), blocks)).append("</th>");
                        }
                        table.append("</tr></thead><tbody>");
                        i += 2;
                        boolean alt = false;
                        while (i < lines.length) {
                            String dl = lines[i].trim();
                            if (!dl.startsWith("|") || !dl.endsWith("|")) break;
                            table.append("<tr style=\"background:")
                                    .append(alt ? theme.rowB : theme.rowA).append(";\">");
                            for (String c : splitRow(dl)) {
                                table.append("<td style=\"padding:5px 10px;border:1px solid ")
                                        .append(theme.border).append(";color:")
                                        .append(theme.text).append(";\">")
                                        .append(cellToHtml(c.trim(), blocks)).append("</td>");
                            }
                            table.append("</tr>");
                            alt = !alt;
                            i++;
                        }
                        table.append("</tbody></table></div>");

                        blocks.add(table.toString());
                        sb.append("§§CODE").append(blocks.size() - 1).append("§§");
                        if (i < lines.length) sb.append("\n");
                        continue;
                    }
                }
            }
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
            i++;
        }
        return sb.toString();
    }

    private static String cellToHtml(String cell, List<String> blocks) {
        if (cell.isEmpty()) return "";
        Pattern p = Pattern.compile("§§CODE(\\d+)§§");
        Matcher m = p.matcher(cell);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(formatPlainFragment(cell.substring(last, m.start())));
            int idx;
            try {
                idx = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                out.append(escapeHtml(m.group()));
                last = m.end();
                continue;
            }
            if (idx >= 0 && idx < blocks.size()) {
                out.append(blocks.get(idx));
            } else {
                out.append(escapeHtml(m.group()));
            }
            last = m.end();
        }
        out.append(formatPlainFragment(cell.substring(last)));
        return out.toString();
    }

    private static String formatPlainFragment(String s) {
        if (s.isEmpty()) return "";
        String e = escapeHtml(s);
        e = e.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        e = e.replaceAll("__(.+?)__", "<b>$1</b>");
        return e;
    }

    private static String[] splitRow(String row) {
        String inner = row.trim();
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        return inner.split("\\|", -1);
    }
}
