package genius.DMTech.Vectr;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public class PresetRepository {

    private final VectrDatabaseHelper dbHelper;

    public PresetRepository(Context context) {
        dbHelper = VectrDatabaseHelper.getInstance(context);
    }

    public void save(Preset preset) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", preset.name);
        cv.put("provider", preset.provider);
        cv.put("model", preset.model);
        cv.put("system_prompt", preset.systemPrompt);
        cv.put("max_tokens", preset.maxTokens);
        db.insert("presets", null, cv);
    }

    public void delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("presets", "id = ?", new String[]{String.valueOf(id)});
    }

    public List<Preset> getAll() {
        List<Preset> result = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, name, provider, model, system_prompt, max_tokens FROM presets ORDER BY id DESC", null);
        while (c.moveToNext()) {
            Preset p = new Preset();
            p.id = c.getLong(0);
            p.name = c.getString(1);
            p.provider = c.getString(2);
            p.model = c.getString(3);
            p.systemPrompt = c.getString(4);
            p.maxTokens = c.getInt(5);
            result.add(p);
        }
        c.close();
        return result;
    }

    public static class Preset {
        public long id;
        public String name;
        public String provider;
        public String model;
        public String systemPrompt;
        public int maxTokens;
    }
}
