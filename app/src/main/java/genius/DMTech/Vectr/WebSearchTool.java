package genius.DMTech.Vectr;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Поиск в интернете для агента (function calling).
 * DuckDuckGo HTML — без API-ключа; fetch_url — чтение страницы.
 */
public final class WebSearchTool {

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    /** Отдельный клиент для fetch: проверяет хост на каждом hop (в т.ч. редиректы). */
    private static final OkHttpClient FETCH_HTTP = HTTP.newBuilder()
            .addNetworkInterceptor(new Interceptor() {
                @Override
                public Response intercept(Chain chain) throws IOException {
                    String blocked = blockedHostReason(chain.request().url().host());
                    if (blocked != null) {
                        throw new IOException("Blocked URL (" + blocked + ")");
                    }
                    return chain.proceed(chain.request());
                }
            })
            .build();

    private static final Pattern RESULT_LINK = Pattern.compile(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern RESULT_SNIPPET = Pattern.compile(
            "<(?:a|td|div)[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|td|div)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private WebSearchTool() {}

    public static String search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return "ОШИБКА: пустой query";
        }
        int limit = maxResults <= 0 ? 5 : Math.min(maxResults, 10);
        String q = query.trim();

        try {
            String url = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(q, "UTF-8");
            String html = httpGet(HTTP, url, "https://duckduckgo.com/");
            if (html == null || html.isEmpty()) {
                return "ОШИБКА: пустой ответ поисковика";
            }

            List<Hit> hits = parseResults(html, limit);
            if (hits.isEmpty()) {
                // запасной простой разбор ссылок uddg=
                hits = parseUddgFallback(html, limit);
            }
            if (hits.isEmpty()) {
                return "По запросу «" + q + "» ничего не найдено. Уточни формулировку.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Результаты поиска («").append(q).append("»):\n\n");
            for (int i = 0; i < hits.size(); i++) {
                Hit h = hits.get(i);
                sb.append(i + 1).append(". ").append(h.title).append("\n");
                sb.append("   URL: ").append(h.url).append("\n");
                if (h.snippet != null && !h.snippet.isEmpty()) {
                    sb.append("   ").append(h.snippet).append("\n");
                }
                sb.append("\n");
            }
            sb.append("Если нужно глубже — вызови fetch_url(url) по релевантной ссылке.");
            return sb.toString().trim();
        } catch (Exception e) {
            return "ОШИБКА поиска: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public static String fetchUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "ОШИБКА: пустой url";
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return "ОШИБКА: url должен начинаться с http:// или https://";
        }
        String precheck = blockedUrlReason(u);
        if (precheck != null) {
            return "ОШИБКА: " + precheck;
        }
        try {
            String html = httpGet(FETCH_HTTP, u, "https://duckduckgo.com/");
            if (html == null || html.isEmpty()) return "ОШИБКА: пустая страница";

            String text = html
                    .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                    .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                    .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
            text = TAGS.matcher(text).replaceAll(" ");
            text = text.replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'");
            text = WS.matcher(text).replaceAll(" ").trim();

            final int max = 12000;
            if (text.length() > max) {
                text = text.substring(0, max) + "\n\n…[обрезано, страница длинная]";
            }
            return "Содержимое " + u + ":\n\n" + text;
        } catch (Exception e) {
            return "ОШИБКА fetch_url: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** null если URL допустим, иначе причина блокировки. */
    static String blockedUrlReason(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return "недопустимый url";
            scheme = scheme.toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "разрешены только http/https";
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "нет hostname в url";
            return blockedHostReason(host);
        } catch (Exception e) {
            return "некорректный url";
        }
    }

    /** null если host допустим. Блокирует localhost / private / link-local / metadata. */
    static String blockedHostReason(String host) {
        if (host == null || host.isEmpty()) return "пустой host";
        String h = host.toLowerCase();
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if ("localhost".equals(h) || h.endsWith(".localhost")
                || "metadata.google.internal".equals(h)
                || h.endsWith(".local")
                || "0.0.0.0".equals(h)) {
            return "локальный/служебный host запрещён";
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(h);
            for (InetAddress addr : addrs) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    return "private/local IP запрещён (" + addr.getHostAddress() + ")";
                }
            }
        } catch (Exception e) {
            // DNS fail — пусть OkHttp сам упадёт на connect; не блокируем заранее
        }
        // эвристики без DNS (на случай если резолв ещё не был)
        if (h.matches("^127\\.\\d+\\.\\d+\\.\\d+$")
                || h.matches("^10\\.\\d+\\.\\d+\\.\\d+$")
                || h.matches("^192\\.168\\.\\d+\\.\\d+$")
                || h.matches("^169\\.254\\.\\d+\\.\\d+$")
                || h.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+$")
                || "::1".equals(h)
                || (h.contains(":") && (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")))) {
            return "private/local IP запрещён";
        }
        return null;
    }

    private static String httpGet(OkHttpClient client, String url, String referer) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
                .header("Referer", referer)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private static List<Hit> parseResults(String html, int limit) {
        List<Hit> out = new ArrayList<>();
        Matcher links = RESULT_LINK.matcher(html);
        while (links.find() && out.size() < limit) {
            String href = unwrapDuckUrl(links.group(1));
            String title = cleanText(links.group(2));
            if (href == null || href.isEmpty() || title.isEmpty()) continue;
            if (href.contains("duckduckgo.com/y.js")) continue;

            String snippet = "";
            int after = links.end();
            String window = html.substring(after, Math.min(html.length(), after + 800));
            Matcher sn = RESULT_SNIPPET.matcher(window);
            if (sn.find()) snippet = cleanText(sn.group(1));

            out.add(new Hit(title, href, snippet));
        }
        return out;
    }

    private static List<Hit> parseUddgFallback(String html, int limit) {
        List<Hit> out = new ArrayList<>();
        Matcher m = Pattern.compile("uddg=([^&\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find() && out.size() < limit) {
            try {
                String href = URLDecoder.decode(m.group(1), "UTF-8");
                if (!href.startsWith("http")) continue;
                // title nearby
                int start = Math.max(0, m.start() - 200);
                int end = Math.min(html.length(), m.end() + 300);
                String chunk = html.substring(start, end);
                String title = cleanText(chunk.replaceAll("(?is)<[^>]+>", " "));
                if (title.length() > 80) title = title.substring(0, 80) + "…";
                if (title.length() < 4) title = href;
                boolean dup = false;
                for (Hit hit : out) {
                    if (hit.url.equals(href)) { dup = true; break; }
                }
                if (!dup) out.add(new Hit(title, href, ""));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static String unwrapDuckUrl(String href) {
        if (href == null) return null;
        try {
            String h = href.replace("&amp;", "&");
            int i = h.indexOf("uddg=");
            if (i >= 0) {
                String enc = h.substring(i + 5);
                int amp = enc.indexOf('&');
                if (amp >= 0) enc = enc.substring(0, amp);
                return URLDecoder.decode(enc, "UTF-8");
            }
            if (h.startsWith("//")) h = "https:" + h;
            return h;
        } catch (Exception e) {
            return href;
        }
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        String t = TAGS.matcher(s).replaceAll(" ");
        t = t.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">");
        return WS.matcher(t).replaceAll(" ").trim();
    }

    private static final class Hit {
        final String title;
        final String url;
        final String snippet;

        Hit(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }
}
