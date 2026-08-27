package genius.DMTech.Vectr;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public class ProjectRepository {

    private final VectrDatabaseHelper dbHelper;

    public ProjectRepository(Context context) {
        dbHelper = VectrDatabaseHelper.getInstance(context);
    }

    public List<ProjectEntry> listRecentProjects() {
        List<ProjectEntry> result = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT name, tree_uri FROM projects ORDER BY last_opened DESC LIMIT 20",
                null
        );
        try {
            while (cursor.moveToNext()) {
                ProjectEntry entry = new ProjectEntry();
                entry.name = cursor.getString(0);
                entry.treeUri = cursor.getString(1);
                result.add(entry);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /** UPDATE по tree_uri, иначе INSERT — не трогаем id (chats.project_id не сиротеет). */
    public void saveOrTouchProject(String treeUri, String name) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long now = System.currentTimeMillis();

        ContentValues touch = new ContentValues();
        touch.put("name", name);
        touch.put("last_opened", now);
        int updated = db.update("projects", touch, "tree_uri = ?", new String[]{treeUri});
        if (updated == 0) {
            ContentValues values = new ContentValues();
            values.put("tree_uri", treeUri);
            values.put("name", name);
            values.put("last_opened", now);
            db.insert("projects", null, values);
        }
    }

    public static class ProjectEntry {
        public String name;
        public String treeUri;
    }
}
