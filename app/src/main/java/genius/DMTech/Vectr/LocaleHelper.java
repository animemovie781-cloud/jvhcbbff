package genius.DMTech.Vectr;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/**
 * Язык UI: 0 = русский (values-ru), 1 = english (values), 2 = hindi (values-hi).
 */
public final class LocaleHelper {

    public static final int LANG_RU = 0;
    public static final int LANG_EN = 1;
    public static final int LANG_HI = 2;

    private LocaleHelper() {}

    public static Context wrap(Context context) {
        SecurePrefsProvider.init(context);
        int index = getLangIndex();
        String lang = "en";
        if (index == LANG_RU) lang = "ru";
        else if (index == LANG_HI) lang = "hi";
        return updateResources(context, lang);
    }

    public static int getLangIndex() {
        SharedPreferences p = SecurePrefsProvider.get();
        // Default to English if not set
        return p != null ? p.getInt("lang_index", LANG_EN) : LANG_EN;
    }

    public static void setLangIndex(int index) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p != null) p.edit().putInt("lang_index", index).apply();
    }

    public static String languageTag(int index) {
        if (index == LANG_RU) return "ru";
        if (index == LANG_HI) return "hi";
        return "en";
    }

    public static Context updateResources(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            return context.createConfigurationContext(config);
        } else {
            config.locale = locale;
            res.updateConfiguration(config, res.getDisplayMetrics());
            return context;
        }
    }
}
