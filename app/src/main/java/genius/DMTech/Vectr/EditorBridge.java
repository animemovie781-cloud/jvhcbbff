package genius.DMTech.Vectr;

import android.webkit.JavascriptInterface;

public class EditorBridge {

    public interface Callbacks {
        void onContent(String content);
        void onEditorState(String json);
    }

    private final Callbacks callbacks;

    public EditorBridge(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    /** Обратная совместимость со старым конструктором. */
    public EditorBridge(OnContentReceived legacy) {
        this.callbacks = new Callbacks() {
            @Override public void onContent(String content) { legacy.onContent(content); }
            @Override public void onEditorState(String json) {}
        };
    }

    public interface OnContentReceived {
        void onContent(String content);
    }

    @JavascriptInterface
    public void receiveContent(String content) {
        if (callbacks != null) callbacks.onContent(content);
    }

    @JavascriptInterface
    public void onEditorState(String json) {
        if (callbacks != null) callbacks.onEditorState(json);
    }
}
