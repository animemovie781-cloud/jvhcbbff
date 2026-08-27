package genius.DMTech.Vectr;

import android.content.Context;

import androidx.documentfile.provider.DocumentFile;

/** Элемент списка файлового браузера (включая виртуальный «..»). */
public class FileEntry {
    public final DocumentFile file;
    public final String name;
    public final boolean isDirectory;
    public final boolean isParent;
    public final long sizeBytes;

    private FileEntry(DocumentFile file, String name, boolean isDirectory, boolean isParent, long sizeBytes) {
        this.file = file;
        this.name = name;
        this.isDirectory = isDirectory;
        this.isParent = isParent;
        this.sizeBytes = sizeBytes;
    }

    public static FileEntry parent() {
        return new FileEntry(null, "..", true, true, -1);
    }

    public static FileEntry from(DocumentFile file) {
        String name = file.getName() != null ? file.getName() : "?";
        boolean dir = file.isDirectory();
        long size = dir ? -1 : file.length();
        return new FileEntry(file, name, dir, false, size);
    }

    public String subtitle(Context context) {
        if (isParent) return context.getString(R.string.files_parent_up);
        if (isDirectory) return context.getString(R.string.files_folder);
        return FileIcons.formatSize(sizeBytes);
    }
}
