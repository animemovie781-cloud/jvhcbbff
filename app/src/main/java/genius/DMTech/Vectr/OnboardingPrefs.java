package genius.DMTech.Vectr;

import android.content.Context;
import android.content.SharedPreferences;

/** Флаги первого запуска / онбординга (обычные prefs, не secure). */
public final class OnboardingPrefs {

    private static final String PREFS = "vectr_prefs";
    private static final String KEY_DONE = "onboarding_done";
    private static final String KEY_LANG_PICKED = "lang_picked";
    private static final String KEY_POLICY = "policy_accepted_v1";
    private static final String KEY_NOTIF_ASKED = "onboarding_notif_asked";
    private static final String KEY_TERMUX_ASKED = "onboarding_termux_asked";

    private OnboardingPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isDone(Context context) {
        return prefs(context).getBoolean(KEY_DONE, false);
    }

    public static void setDone(Context context, boolean done) {
        prefs(context).edit().putBoolean(KEY_DONE, done).apply();
    }

    public static boolean isLangPicked(Context context) {
        return prefs(context).getBoolean(KEY_LANG_PICKED, false);
    }

    public static void setLangPicked(Context context, boolean picked) {
        prefs(context).edit().putBoolean(KEY_LANG_PICKED, picked).apply();
    }

    public static boolean isPolicyAccepted(Context context) {
        return prefs(context).getBoolean(KEY_POLICY, false);
    }

    public static void setPolicyAccepted(Context context, boolean accepted) {
        prefs(context).edit().putBoolean(KEY_POLICY, accepted).apply();
    }

    public static boolean wasNotifAsked(Context context) {
        return prefs(context).getBoolean(KEY_NOTIF_ASKED, false);
    }

    public static void setNotifAsked(Context context, boolean asked) {
        prefs(context).edit().putBoolean(KEY_NOTIF_ASKED, asked).apply();
    }

    public static boolean wasTermuxAsked(Context context) {
        return prefs(context).getBoolean(KEY_TERMUX_ASKED, false);
    }

    public static void setTermuxAsked(Context context, boolean asked) {
        prefs(context).edit().putBoolean(KEY_TERMUX_ASKED, asked).apply();
    }
}
