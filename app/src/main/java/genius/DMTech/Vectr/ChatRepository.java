package genius.DMTech.Vectr;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ChatRepository {

    private static final String TAG = "ChatRepository";
    private final VectrDatabaseHelper dbHelper;

    public ChatRepository(Context context) {
        dbHelper = VectrDatabaseHelper.getInstance(context);
    }

    public long createChat(String title, Long projectId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long now = System.currentTimeMillis();

        ContentValues values = new ContentValues();
        values.put("title", title);
        if (projectId != null) values.put("project_id", projectId);
        values.put("created_at", now);
        values.put("updated_at", now);

        return db.insert("chats", null, values);
    }

    public void touchChat(long chatId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        db.update("chats", values, "id = ?", new String[]{String.valueOf(chatId)});
    }

    public void updateChatTitle(long chatId, String title) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("title", title);
        db.update("chats", values, "id = ?", new String[]{String.valueOf(chatId)});
    }

    public void deleteLastMessage(long chatId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL(
                "DELETE FROM messages WHERE id = (SELECT id FROM messages WHERE chat_id = ? ORDER BY sort_order DESC LIMIT 1)",
                new Object[]{chatId}
        );
    }

    public void deleteMessagesFromOrder(long chatId, int fromOrderInclusive) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("messages", "chat_id = ? AND sort_order >= ?",
                new String[]{String.valueOf(chatId), String.valueOf(fromOrderInclusive)});
        touchChat(chatId);
    }

    public void deleteChat(long chatId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", "chat_id = ?", new String[]{String.valueOf(chatId)});
            db.delete("chats", "id = ?", new String[]{String.valueOf(chatId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void purgeEmptyChats() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL("DELETE FROM chats WHERE id NOT IN (SELECT DISTINCT chat_id FROM messages)");
    }

    public long getLastChatId() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT id FROM chats ORDER BY updated_at DESC LIMIT 1", null);
        try {
            long id = -1;
            if (cursor.moveToFirst()) id = cursor.getLong(0);
            return id;
        } finally {
            cursor.close();
        }
    }

    public List<ChatSummary> listChats() {
        List<ChatSummary> result = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT c.id, c.title, c.updated_at, " +
                        "(SELECT m.text FROM messages m WHERE m.chat_id = c.id ORDER BY m.sort_order DESC LIMIT 1) AS last_msg " +
                        "FROM chats c ORDER BY c.updated_at DESC", null);

        try {
            while (cursor.moveToNext()) {
                ChatSummary summary = new ChatSummary();
                summary.id = cursor.getLong(0);
                summary.title = cursor.getString(1);
                summary.updatedAt = cursor.getLong(2);
                summary.lastPreview = cursor.getString(3);
                result.add(summary);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /** Upsert по (chat_id, sort_order) — повторный persist того же хода не плодит дубли. */
    public long saveMessage(long chatId, ChatMessage msg, int order) {
        if (order < 0) {
            Log.w(TAG, "saveMessage: отказ, sort_order=" + order);
            return -1;
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("chat_id", chatId);
        values.put("role", msg.role.name());
        values.put("text", msg.text);
        values.put("thinking", msg.thinking);
        values.put("tool_call_id", msg.toolCallId);
        values.put("sort_order", order);
        values.put("created_at", msg.timestamp);

        if (msg.toolCalls != null) {
            values.put("tool_calls_json", serializeToolCalls(msg.toolCalls));
        }
        if (msg.sources != null) {
            values.put("sources_json", new JSONArray(msg.sources).toString());
        }

        touchChat(chatId);

        int updated = db.update("messages", values,
                "chat_id = ? AND sort_order = ?",
                new String[]{String.valueOf(chatId), String.valueOf(order)});
        if (updated > 0) {
            Cursor c = db.rawQuery(
                    "SELECT id FROM messages WHERE chat_id = ? AND sort_order = ? LIMIT 1",
                    new String[]{String.valueOf(chatId), String.valueOf(order)});
            try {
                return c.moveToFirst() ? c.getLong(0) : 0;
            } finally {
                c.close();
            }
        }
        return db.insert("messages", null, values);
    }

    public List<ChatMessage> loadMessages(long chatId) {
        List<ChatMessage> result = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT role, text, thinking, tool_calls_json, tool_call_id, sources_json, created_at " +
                        "FROM messages WHERE chat_id = ? ORDER BY sort_order ASC",
                new String[]{String.valueOf(chatId)}
        );

        try {
            while (cursor.moveToNext()) {
                String roleStr = cursor.getString(0);
                ChatMessage.Role role;
                try {
                    role = ChatMessage.Role.valueOf(roleStr);
                } catch (Exception e) {
                    Log.w(TAG, "Пропуск сообщения с неизвестной ролью: " + roleStr);
                    continue;
                }
                ChatMessage msg = new ChatMessage(role, cursor.getString(1));
                msg.thinking = cursor.getString(2);
                msg.toolCallId = cursor.getString(4);
                msg.timestamp = cursor.getLong(6);

                String toolCallsJson = cursor.getString(3);
                if (toolCallsJson != null) msg.toolCalls = deserializeToolCalls(toolCallsJson);

                String sourcesJson = cursor.getString(5);
                if (sourcesJson != null) msg.sources = deserializeSources(sourcesJson);

                result.add(msg);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    private String serializeToolCalls(List<ToolCallInfo> calls) {
        JSONArray arr = new JSONArray();
        try {
            for (ToolCallInfo call : calls) {
                JSONObject obj = new JSONObject();
                obj.put("id", call.id);
                obj.put("name", call.name);
                obj.put("argumentsJson", call.argumentsJson);
                obj.put("result", call.result);
                obj.put("done", call.done);
                obj.put("diffAdded", call.diffAdded);
                obj.put("diffRemoved", call.diffRemoved);
                obj.put("targetFile", call.targetFile);
                arr.put(obj);
            }
        } catch (JSONException e) {
            Log.e(TAG, "serializeToolCalls failed", e);
        }
        return arr.toString();
    }

    private List<ToolCallInfo> deserializeToolCalls(String json) {
        List<ToolCallInfo> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ToolCallInfo call = new ToolCallInfo();
                call.id = obj.optString("id", null);
                call.name = obj.optString("name", null);
                call.argumentsJson = obj.optString("argumentsJson", null);
                call.result = obj.optString("result", null);
                call.done = obj.optBoolean("done", false);
                call.diffAdded = obj.optInt("diffAdded", -1);
                call.diffRemoved = obj.optInt("diffRemoved", -1);
                call.targetFile = obj.optString("targetFile", null);
                result.add(call);
            }
        } catch (JSONException e) {
            Log.e(TAG, "deserializeToolCalls failed", e);
        }
        return result;
    }

    private List<String> deserializeSources(String json) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) result.add(arr.getString(i));
        } catch (JSONException e) {
            Log.e(TAG, "deserializeSources failed", e);
        }
        return result;
    }

    public static class ChatSummary {
        public long id;
        public String title;
        public long updatedAt;
        public String lastPreview;
    }
}
