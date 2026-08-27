package genius.DMTech.Vectr;

import android.net.Uri;

public class OpenFileTab {
    public Uri uri;
    public String name;
    public String relativePath;  // путь от корня проекта, если известен
    public String content;       // закэшированное содержимое
    /** false = ещё не читали с диска; "" при contentLoaded=true — реально пустой файл */
    public boolean contentLoaded;
    public String mode;          // syntax highlight mode
    public boolean isDirty;
    public int scrollPosition;
    public int cursorLine = 1;
    public int cursorCh = 0;
    public String selection = "";

    public OpenFileTab(Uri uri, String name, String mode) {
        this.uri = uri;
        this.name = name;
        this.mode = mode;
        this.content = "";
        this.contentLoaded = false;
        this.isDirty = false;
        this.scrollPosition = 0;
        this.cursorLine = 1;
        this.cursorCh = 0;
        this.selection = "";
        this.relativePath = name;
    }
}
