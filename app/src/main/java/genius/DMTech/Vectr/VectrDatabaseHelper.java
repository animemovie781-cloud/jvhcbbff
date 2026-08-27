package genius.DMTech.Vectr;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class VectrDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "vectr.db";
    private static final int DB_VERSION = 4;

    private static VectrDatabaseHelper instance;

    public static synchronized VectrDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new VectrDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private VectrDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projects (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "tree_uri TEXT NOT NULL UNIQUE, " +
                "last_opened INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE chats (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "project_id INTEGER, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE, " +
                "role TEXT NOT NULL, " +
                "text TEXT, " +
                "thinking TEXT, " +
                "tool_calls_json TEXT, " +
                "tool_call_id TEXT, " +
                "sources_json TEXT, " +
                "created_at INTEGER NOT NULL DEFAULT 0, " +
                "sort_order INTEGER NOT NULL)");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_id ON messages(chat_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_sort ON messages(chat_id, sort_order)");

        db.execSQL("CREATE TABLE presets (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "provider TEXT, " +
                "model TEXT, " +
                "system_prompt TEXT, " +
                "max_tokens INTEGER NOT NULL DEFAULT 4096)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS presets (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, " +
                    "provider TEXT, " +
                    "model TEXT, " +
                    "system_prompt TEXT, " +
                    "max_tokens INTEGER NOT NULL DEFAULT 4096)");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_id ON messages(chat_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_sort ON messages(chat_id, sort_order)");
        }
    }
}
