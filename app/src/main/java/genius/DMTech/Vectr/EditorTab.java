package genius.DMTech.Vectr;

import android.net.Uri;

public class EditorTab {
    public Uri uri;
    public String fileName;
    public String content;
    public String mode;
    public boolean modified;
    public long lastOpened;

    public EditorTab(Uri uri, String fileName, String content, String mode) {
        this.uri = uri;
        this.fileName = fileName;
        this.content = content;
        this.mode = mode;
        this.modified = false;
        this.lastOpened = System.currentTimeMillis();
    }
}
