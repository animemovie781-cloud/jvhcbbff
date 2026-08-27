package genius.DMTech.Vectr;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

public class DeepSeekClient implements AiClient {

    private static final String DEFAULT_URL = "https://api.deepseek.com/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String TAG = "DeepSeekClient";

    // Модель иногда вместо native tool_calls высирает DSML в content, вида:
    //   < | DSML | | invoke name="read_file">
    //   < | DSML | | parameter name="path" string="true">java/AiConfig.java</ | DSML | | parameter>
    //   </ | DSML | | invoke>
    // Между "<"/">" куча пайпов и пробелов — старые лимиты {0,10}/{0,20} не влезали,
    // парсер возвращал пусто, а сырой DSML утекал в чат.
    private static final Pattern DSML_START_PATTERN = Pattern.compile(
            "<[^>]{0,80}?(?:tool_calls|invoke)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DSML_INVOKE = Pattern.compile(
            "<[^>]{0,80}?invoke\\b[^>]{0,80}?name\\s*=\\s*\"([^\"]+)\"[^>]*>" +
                    "(.*?)</[^>]{0,80}?invoke[^>]*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DSML_PARAM = Pattern.compile(
            "<[^>]{0,80}?parameter\\b[^>]{0,80}?name\\s*=\\s*\"([^\"]+)\"[^>]*>" +
                    "(.*?)</[^>]{0,80}?parameter[^>]*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    // если закрывающие теги кривые/обрезанные — всё равно вытащим name + path
    private static final Pattern DSML_INVOKE_LOOSE = Pattern.compile(
            "invoke\\b[^>]{0,80}?name\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)(?=<[^>]{0,80}?invoke\\b|</[^>]{0,80}?tool_calls|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DSML_PARAM_LOOSE = Pattern.compile(
            "parameter\\b[^>]{0,80}?name\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)(?:</[^>]{0,80}?parameter[^>]*>|(?=<[^>]{0,80}?parameter\\b)|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DSML_MARK_PATTERN = Pattern.compile(
            "DSML|tool_calls|</?\\s*[|]?\\s*DSML",
            Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client;
    private final String endpoint;
    /** DeepSeek-only поля вроде reasoning_effort; для openai_compat — false. */
    private final boolean deepseekVendor;
    private volatile EventSource currentEventSource;
    /** Инкремент на cancel/новом ходе — колбэки старого EventSource игнорятся. */
    private final java.util.concurrent.atomic.AtomicLong streamGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);

    public DeepSeekClient() {
        this(DEFAULT_URL, true);
    }

    public DeepSeekClient(String apiBaseUrl) {
        this(apiBaseUrl, true);
    }

    public DeepSeekClient(String apiBaseUrl, boolean deepseekVendor) {
        this.deepseekVendor = deepseekVendor;
        this.endpoint = (apiBaseUrl == null || apiBaseUrl.trim().isEmpty())
                ? DEFAULT_URL
                : AiConfig.normalizeCompletionsUrl(apiBaseUrl);
        client = new OkHttpClient.Builder()
                // readTimeout(0) вешал запрос НАВСЕГДА, если сервер молча перестанет слать байты -
                // ни onError, ни onComplete, просто тишина. 120с с запасом на паузы между токенами.
                .readTimeout(120, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                // общий потолок на один SSE-запрос: keep-alive иначе может бесконечно сбрасывать readTimeout
                .callTimeout(4, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public void cancel() {
        streamGeneration.incrementAndGet();
        EventSource es = currentEventSource;
        currentEventSource = null;
        if (es != null) {
            try { es.cancel(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void streamChat(String apiKey, String model, String systemPrompt,
                            List<ChatMessage> history, int maxTokens, boolean thinkingEnabled,
                            boolean allowTools, StreamCallback callback) {

        // новый ход — гасим предыдущий EventSource, иначе два стрима пишут в один колбэк
        cancel();

        JSONObject body = new JSONObject();
        try {
            JSONArray messages = new JSONArray();

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
                messages.put(sys);
            }

            for (ChatMessage msg : history) {
                JSONObject m = new JSONObject();

                if (msg.role == ChatMessage.Role.USER) {
                    m.put("role", "user");
                    m.put("content", msg.text);

                } else if (msg.role == ChatMessage.Role.TOOL) {
                    m.put("role", "tool");
                    m.put("tool_call_id", msg.toolCallId);
                    m.put("content", msg.text);

                } else { // ASSISTANT
                    m.put("role", "assistant");
                    // content обязан быть null, если есть tool_calls — иначе DeepSeek шлёт 400
                    if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                        m.put("content", JSONObject.NULL);
                    } else {
                        m.put("content", msg.text == null ? "" : msg.text);
                    }

                    if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                        JSONArray tcArray = new JSONArray();
                        for (ToolCallInfo tc : msg.toolCalls) {
                            JSONObject tcObj = new JSONObject();
                            tcObj.put("id", tc.id);
                            tcObj.put("type", "function");
                            JSONObject func = new JSONObject();
                            func.put("name", tc.name);
                            func.put("arguments", tc.argumentsJson == null ? "{}" : tc.argumentsJson);
                            tcObj.put("function", func);
                            tcArray.put(tcObj);
                        }
                        m.put("tool_calls", tcArray);
                    }
                }

                messages.put(m);
            }

            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", maxTokens);
            body.put("stream", true);

            if (allowTools) {
                body.put("tools", ToolSchema.build());
                body.put("tool_choice", "auto");
            }

            // reasoning_effort — поле DeepSeek; openai-compat часто отвечает 400
            if (thinkingEnabled && deepseekVendor) {
                body.put("reasoning_effort", "medium");
            }
        } catch (Exception e) {
            callback.onError("Ошибка сборки запроса: " + e.getMessage());
            return;
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        // копим tool_calls по index, они прилетают кусками (сначала name, потом arguments по частям)
        final Map<Integer, ToolCallInfo> toolCallsMap = new TreeMap<>();
        final boolean[] toolCallsTriggered = {false};
        final AtomicBoolean terminal = new AtomicBoolean(false);

        // полный накопленный content - нужен, чтобы поймать псевдо-tool-call формат целиком
        final StringBuilder rawContent = new StringBuilder();
        final int[] sentUpTo = {0};       // сколько символов rawContent уже ушло в callback.onChunk
        final boolean[] dsmlDetected = {false};
        final int[] dsmlStartIdx = {-1};

        final long myGeneration = streamGeneration.incrementAndGet();

        EventSource.Factory factory = EventSources.createFactory(client);
        currentEventSource = factory.newEventSource(request, new EventSourceListener() {

            private boolean isStale(EventSource eventSource) {
                return myGeneration != streamGeneration.get()
                        || (currentEventSource != null && eventSource != currentEventSource);
            }

            private boolean finishOnce(Runnable action) {
                if (!terminal.compareAndSet(false, true)) return false;
                action.run();
                return true;
            }

            @Override
            public void onEvent(@NonNull EventSource eventSource, String id, String type, @NonNull String data) {
                if (isStale(eventSource) || terminal.get()) return;

                if (data.equals("[DONE]")) {
                    boolean finished = finishContentTurn(callback, allowTools, toolCallsTriggered,
                            rawContent, sentUpTo, dsmlDetected, dsmlStartIdx, this::finishOnce);
                    if (!finished && !toolCallsTriggered[0]) {
                        finishOnce(callback::onComplete);
                    }
                    return;
                }

                // keep-alive пустышки — проверяем явно, без try-catch-всё
                if (data.trim().isEmpty() || data.trim().equals(": keep-alive")) {
                    return;
                }

                try {
                    JSONObject json = new JSONObject(data);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) return;
                    JSONObject choice = choices.getJSONObject(0);
                    JSONObject delta = choice.optJSONObject("delta");

                    if (delta != null) {
                        if (delta.has("content") && !delta.isNull("content")) {
                            String chunk = delta.getString("content");
                            rawContent.append(chunk);
                            processContentDelta(callback, rawContent, sentUpTo, dsmlDetected, dsmlStartIdx);
                        }
                        if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                            callback.onThinkingChunk(delta.getString("reasoning_content"));
                        }
                        if (delta.has("tool_calls")) {
                            JSONArray tcArray = delta.getJSONArray("tool_calls");
                            for (int i = 0; i < tcArray.length(); i++) {
                                JSONObject tc = tcArray.getJSONObject(i);
                                int index = tc.optInt("index", 0);

                                ToolCallInfo info = toolCallsMap.get(index);
                                if (info == null) {
                                    info = new ToolCallInfo();
                                    info.argumentsJson = "";
                                    toolCallsMap.put(index, info);
                                }
                                if (tc.has("id")) info.id = tc.getString("id");

                                JSONObject func = tc.optJSONObject("function");
                                if (func != null) {
                                    if (func.has("name") && !func.isNull("name")) {
                                        info.name = func.getString("name");
                                    }
                                    if (func.has("arguments") && !func.isNull("arguments")) {
                                        info.argumentsJson += func.getString("arguments");
                                    }
                                }
                            }
                        }
                    }

                    String finishReason = choice.optString("finish_reason", null);
                    if ("tool_calls".equals(finishReason)) {
                        toolCallsTriggered[0] = true;
                        finishOnce(() -> callback.onToolCallsReady(new ArrayList<>(toolCallsMap.values())));
                        try { eventSource.cancel(); } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка парсинга SSE-события: " + data, e);
                    finishOnce(() -> callback.onError("Ошибка парсинга ответа от сервера: " + e.getMessage()));
                    try { eventSource.cancel(); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onClosed(@NonNull EventSource eventSource) {
                if (isStale(eventSource)) return;
                if (terminal.get() || toolCallsTriggered[0]) return;

                boolean finished = finishContentTurn(callback, allowTools, toolCallsTriggered,
                        rawContent, sentUpTo, dsmlDetected, dsmlStartIdx, this::finishOnce);
                if (!finished && !terminal.get()) {
                    // чистый close без контента — complete, не ошибка (прокси/пустой choice)
                    finishOnce(callback::onComplete);
                }
            }

            @Override
            public void onFailure(@NonNull EventSource eventSource, Throwable t, Response response) {
                try {
                    if (isStale(eventSource)) return;
                    if (terminal.get()) return;
                    String base = t != null ? t.getMessage() : "Неизвестная хуйня со связью";
                    if (base == null) base = "Неизвестная хуйня со связью";
                    final String msg = response != null ? base + " (код " + response.code() + ")" : base;
                    // cancel / замена стрима — никогда не onError
                    if (t instanceof java.io.IOException
                            && (msg.contains("Canceled") || msg.contains("Socket closed")
                            || msg.contains("CANCEL") || msg.contains("cancelled"))) {
                        return;
                    }
                    finishOnce(() -> callback.onError(msg));
                } finally {
                    if (response != null) {
                        try { response.close(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private interface Once {
        boolean run(Runnable action);
    }

    /** Стримим только чистый текст; DSML копятся до конца хода. */
    private void processContentDelta(StreamCallback callback, StringBuilder rawContent,
                                     int[] sentUpTo, boolean[] dsmlDetected, int[] dsmlStartIdx) {
        if (dsmlDetected[0]) {
            sentUpTo[0] = rawContent.length();
            return;
        }

        Matcher startMatcher = DSML_START_PATTERN.matcher(rawContent);
        int searchFrom = Math.max(0, sentUpTo[0] - 40);
        if (startMatcher.find(searchFrom)) {
            int idx = startMatcher.start();
            dsmlDetected[0] = true;
            dsmlStartIdx[0] = idx;
            if (idx > sentUpTo[0]) {
                callback.onChunk(rawContent.substring(sentUpTo[0], idx));
            }
            sentUpTo[0] = rawContent.length();
            return;
        }

        // Возможный незакрытый старт DSML: придерживаем хвост от '<' или '|DSML'
        int holdFrom = findDsmlHoldIndex(rawContent, sentUpTo[0]);
        if (holdFrom > sentUpTo[0]) {
            callback.onChunk(rawContent.substring(sentUpTo[0], holdFrom));
            sentUpTo[0] = holdFrom;
        } else if (holdFrom < 0 && rawContent.length() > sentUpTo[0]) {
            callback.onChunk(rawContent.substring(sentUpTo[0]));
            sentUpTo[0] = rawContent.length();
        }
    }

    /** Индекс, с которого нельзя слать в чат (возможное начало DSML), либо -1 если слать всё. */
    private static int findDsmlHoldIndex(CharSequence raw, int sentUpTo) {
        String tail = raw.toString().substring(sentUpTo);
        int lt = tail.lastIndexOf('<');
        if (lt >= 0) {
            String after = tail.substring(lt);
            if (!after.contains(">") || DSML_MARK_PATTERN.matcher(after).find()
                    || after.matches("(?s)<\\s*[|\\sDSMLdsm]*")) {
                return sentUpTo + lt;
            }
        }
        // иногда модель начинает с |DSML без '<'
        int pipe = Math.max(tail.lastIndexOf("|DSML"), tail.lastIndexOf("| DSML"));
        if (pipe >= 0) return sentUpTo + pipe;
        return -1;
    }

    /** @return true если ход уже завершён (complete / tool_calls) */
    private boolean finishContentTurn(StreamCallback callback, boolean allowTools,
                                      boolean[] toolCallsTriggered, StringBuilder rawContent,
                                      int[] sentUpTo, boolean[] dsmlDetected, int[] dsmlStartIdx,
                                      Once finishOnce) {
        if (toolCallsTriggered[0]) return true;

        if (!dsmlDetected[0]) {
            int mark = indexOfDsmlMark(rawContent);
            if (mark >= 0) {
                dsmlDetected[0] = true;
                dsmlStartIdx[0] = mark;
                if (mark > sentUpTo[0]) {
                    callback.onChunk(rawContent.substring(sentUpTo[0], mark));
                }
                sentUpTo[0] = rawContent.length();
            }
        }

        if (dsmlDetected[0]) {
            String block = rawContent.substring(Math.max(0, dsmlStartIdx[0]));
            List<ToolCallInfo> parsed = parseDsmlToolCalls(block);
            if (!parsed.isEmpty() && allowTools) {
                return finishOnce.run(() -> callback.onToolCallsReady(parsed));
            }
            // сырой DSML в чат не досылаем
            return finishOnce.run(callback::onComplete);
        }

        if (sentUpTo[0] < rawContent.length()) {
            callback.onChunk(rawContent.substring(sentUpTo[0]));
            sentUpTo[0] = rawContent.length();
        }
        if (rawContent.length() > 0) {
            return finishOnce.run(callback::onComplete);
        }
        return false;
    }

    private static int indexOfDsmlMark(CharSequence raw) {
        Matcher m = DSML_START_PATTERN.matcher(raw);
        if (m.find()) return m.start();
        String s = raw.toString();
        int a = indexIgnoreCase(s, "< | DSML");
        int b = indexIgnoreCase(s, "<|DSML");
        int c = indexIgnoreCase(s, "|DSML|");
        int d = indexIgnoreCase(s, "invoke name=\"");
        int best = minPositive(a, b, c);
        if (best >= 0) return best;
        // invoke name= без обёртки — только если рядом есть DSML/tool_calls
        if (d >= 0 && (s.contains("DSML") || s.contains("tool_calls"))) return d;
        return -1;
    }

    private static int indexIgnoreCase(String s, String needle) {
        return s.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static int minPositive(int... vals) {
        int best = -1;
        for (int v : vals) {
            if (v >= 0 && (best < 0 || v < best)) best = v;
        }
        return best;
    }

    private List<ToolCallInfo> parseDsmlToolCalls(String block) {
        List<ToolCallInfo> result = parseDsmlWith(block, DSML_INVOKE, DSML_PARAM);
        if (result.isEmpty()) {
            result = parseDsmlWith(block, DSML_INVOKE_LOOSE, DSML_PARAM_LOOSE);
        }
        return result;
    }

    private List<ToolCallInfo> parseDsmlWith(String block, Pattern invokePat, Pattern paramPat) {
        List<ToolCallInfo> result = new ArrayList<>();
        Matcher invokeMatcher = invokePat.matcher(block);
        int counter = 0;

        while (invokeMatcher.find()) {
            String name = invokeMatcher.group(1);
            if (name == null || name.trim().isEmpty()) continue;
            String paramsBlock = invokeMatcher.group(2);
            if (paramsBlock == null) paramsBlock = "";

            JSONObject args = new JSONObject();
            Matcher paramMatcher = paramPat.matcher(paramsBlock);
            while (paramMatcher.find()) {
                String key = paramMatcher.group(1).trim();
                String value = paramMatcher.group(2);
                if (value != null) value = value.trim();
                try {
                    args.put(key, value);
                } catch (Exception ignored) {
                }
            }

            // аргументов нет — пропускать нельзя только если имя валидное tool; без path бесполезно
            if (args.length() == 0 && ("read_file".equals(name) || "write_file".equals(name)
                    || "list_files".equals(name))) {
                continue;
            }

            ToolCallInfo info = new ToolCallInfo();
            info.id = "dsml_" + System.currentTimeMillis() + "_" + (counter++);
            info.name = name.trim();
            info.argumentsJson = args.toString();
            result.add(info);
        }
        return result;
    }
}
