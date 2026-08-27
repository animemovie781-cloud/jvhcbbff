package genius.DMTech.Vectr;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/**
 * Язык UI: 0 = русский (values-ru), 1 = english (values).
 */
public final class LocaleHelper {

    public static final int LANG_RU = 0;
    public static final int LANG_EN = 1;

    private LocaleHelper() {}

    public static Context wrap(Context context) {
        SecurePrefsProvider.init(context);
        int index = getLangIndex();
        return updateResources(context, index == LANG_EN ? "en" : "ru");
    }

    public static int getLangIndex() {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null ? p.getInt("lang_index", LANG_RU) : LANG_RU;
    }

    public static void setLangIndex(int index) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p != null) p.edit().putInt("lang_index", index).apply();
    }

    public static String languageTag(int index) {
        return index == LANG_EN ? "en" : "ru";
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
