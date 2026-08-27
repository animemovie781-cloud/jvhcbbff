package genius.DMTech.Vectr;

import java.util.List;

public class ChatMessage {

    public enum Role { USER, ASSISTANT, TOOL }

    public Role role;
    public String text;
    public String thinking;
    public List<String> sources;
    public List<ToolCallInfo> toolCalls;
    public String toolCallId;
    public boolean isStreaming;

    /** Юзер раскрыл блок «Ход мыслей» — не сбрасывать при partial update. */
    public boolean thinkingExpanded = false;

    // время создания сообщения
    public long timestamp = System.currentTimeMillis();

    // флаг "работаю над файлом" (системное сообщение)
    public boolean isWorkingMessage = false;
    public String workingFileName;

    /** Чекпоинт сессии агента в ленте чата — тап открывает панель отката. */
    public boolean isCheckpointMessage = false;
    public int checkpointFileCount = 0;

    public ChatMessage(Role role, String text) {
        this.role = role;
        this.text = text;
    }

    /** Хелпер для создания working-сообщения */
    public static ChatMessage createWorking(String fileName) {
        ChatMessage msg = new ChatMessage(Role.ASSISTANT, "");
        msg.isWorkingMessage = true;
        msg.workingFileName = fileName;
        return msg;
    }

    public static ChatMessage createCheckpoint(int fileCount) {
        ChatMessage msg = new ChatMessage(Role.ASSISTANT, "");
        msg.isCheckpointMessage = true;
        msg.checkpointFileCount = fileCount;
        msg.text = "Checkpoint · " + fileCount + " файл(ов)";
        return msg;
    }
}
