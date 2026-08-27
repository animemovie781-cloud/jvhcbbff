package genius.DMTech.Vectr;

import android.content.Context;
import android.content.res.Configuration;

import androidx.core.content.ContextCompat;

/** Цвета HTML для чата/markdown — day/night из ресурсов. */
public final class ChatHtmlTheme {

    public final String text;
    public final String accent;
    public final String muted;
    public final String codeBg;
    public final String codeFg;
    public final String border;
    public final String headerBg;
    public final String rowA;
    public final String rowB;
    public final String preFg;
    public final boolean night;

    private ChatHtmlTheme(String text, String accent, String muted, String codeBg, String codeFg,
                          String border, String headerBg, String rowA, String rowB, String preFg,
                          boolean night) {
        this.text = text;
        this.accent = accent;
        this.muted = muted;
        this.codeBg = codeBg;
        this.codeFg = codeFg;
        this.border = border;
        this.headerBg = headerBg;
        this.rowA = rowA;
        this.rowB = rowB;
        this.preFg = preFg;
        this.night = night;
    }

    public static ChatHtmlTheme from(Context context) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (night) {
            return fromNightFallback();
        }
        return new ChatHtmlTheme(
                "#12141A", "#2563EB", "#8B93A1",
                "#EEF1F5", "#0369A1", "#D8DEE6",
                "#E8ECF1", "#FFFFFF", "#F4F6F9",
                "#12141A", false);
    }

    /** Fallback без Context (старые вызовы) — тёмная палитра. */
    public static ChatHtmlTheme fromNightFallback() {
        return new ChatHtmlTheme(
                "#E8EAED", "#7EB8FF", "#6B7380",
                "#0E1014", "#9CDCFE", "#2A2E38",
                "#171A21", "#0B0C10", "#12141A",
                "#E8EAED", true);
    }

    /** CSS для WebView-пузыря ассистента. */
    public String bodyCss() {
        return "body { margin:0; padding:10px 2px; font-size:14.5px; font-family:sans-serif; color:" + text
                + "; background:transparent; word-wrap:break-word; line-height:1.45; }"
                + "a { color:" + accent + "; }"
                + "code { background:" + codeBg + "; color:" + codeFg
                + "; padding:1px 5px; border-radius:4px; font-family:monospace; font-size:13px; border:1px solid "
                + border + "; }"
                + "table { border-collapse:collapse; width:100%; font-size:13px; }"
                + "th, td { padding:6px 10px; border:1px solid " + border + "; text-align:left; }"
                + "th { background:" + headerBg + "; color:" + accent + "; }"
                + "td { color:" + text + "; }"
                + "h1, h2, h3, h4, h5, h6 { color:" + accent + "; margin:8px 0 4px; font-weight:600; }";
    }

    public static String hex(Context context, int colorRes) {
        int c = ContextCompat.getColor(context, colorRes);
        return String.format("#%06X", (0xFFFFFF & c));
    }
}
