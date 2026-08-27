package genius.DMTech.Vectr;

import java.util.List;

public interface AiClient {
    interface StreamCallback {
        void onChunk(String textDelta);
        void onThinkingChunk(String thinkingDelta);
        void onToolCallsReady(List<ToolCallInfo> calls);
        void onComplete();
        void onError(String message);
    }

    void streamChat(String apiKey, String model, String systemPrompt,
                     List<ChatMessage> history, int maxTokens, boolean thinkingEnabled,
                     boolean allowTools, StreamCallback callback);

    // обрывает текущий стрим (кнопка "стоп" в чате)
    void cancel();
}