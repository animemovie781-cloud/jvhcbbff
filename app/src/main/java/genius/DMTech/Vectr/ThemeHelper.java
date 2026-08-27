package genius.DMTech.Vectr;

import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Тема: 0 = тёмная, 1 = светлая, 2 = системная.
 */
public final class ThemeHelper {

    public static final int THEME_DARK = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_SYSTEM = 2;

    private ThemeHelper() {}

    public static int getThemeIndex() {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null ? p.getInt("theme_index", THEME_DARK) : THEME_DARK;
    }

    public static void setThemeIndex(int index) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p != null) p.edit().putInt("theme_index", index).apply();
    }

    /** Применить night mode сразу (до setContentView). */
    public static void applyFromPrefs() {
        apply(getThemeIndex());
    }

    public static void apply(int themeIndex) {
        int mode;
        switch (themeIndex) {
            case THEME_LIGHT:
                mode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case THEME_SYSTEM:
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
            case THEME_DARK:
            default:
                mode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
