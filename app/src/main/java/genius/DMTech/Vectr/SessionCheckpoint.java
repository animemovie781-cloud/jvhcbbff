package genius.DMTech.Vectr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Снимок файлов до правок агента в текущем ходе.
 * Restore записывает все сохранённые oldContent обратно.
 */
public class SessionCheckpoint {

    private final Map<String, String> originals = new LinkedHashMap<>();
    private boolean active = false;

    public synchronized void begin() {
        originals.clear();
        active = true;
    }

    public synchronized void clear() {
        originals.clear();
        active = false;
    }

    /** Запоминает первое (оригинальное) содержимое пути в сессии. */
    public synchronized void rememberOriginal(String path, String oldContent) {
        if (!active || path == null) return;
        if (!originals.containsKey(path)) {
            originals.put(path, oldContent != null ? oldContent : "");
        }
    }

    public synchronized boolean hasSnapshots() {
        return !originals.isEmpty();
    }

    public synchronized int size() {
        return originals.size();
    }

    public synchronized Map<String, String> snapshotCopy() {
        return new LinkedHashMap<>(originals);
    }

    public synchronized boolean isActive() {
        return active;
    }
}
