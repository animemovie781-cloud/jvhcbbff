package genius.DMTech.Vectr;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class SecurePrefsProvider {
    private static final String PREFS_FILE = "vectr_secure_prefs";
    private static final String TAG = "SecurePrefs";
    private static SharedPreferences cachedPrefs;
    private static boolean initialized = false;

    public static void init(Context context) {
        if (initialized) return;
        Context appCtx = context.getApplicationContext();

        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                cachedPrefs = createPrefs(appCtx);
                initialized = true;
                return;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "open prefs attempt " + (attempt + 1) + " failed", e);
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Сносим файл только при явной порче/несходимости ключа Keystore.
        // Иначе (OOM, краткий сбой) get() вернёт null, но ключи на диске останутся.
        if (!shouldWipeCorruptedPrefs(last)) {
            cachedPrefs = null;
            initialized = false;
            Log.e(TAG, "prefs unavailable without wipe (not a decrypt/corruption error)", last);
            return;
        }

        Log.e(TAG, "Не смогли расшифровать prefs (бэкап/реинсталл без Keystore) — сносим битый файл", last);
        try {
            appCtx.deleteSharedPreferences(PREFS_FILE);
            cachedPrefs = createPrefs(appCtx);
            initialized = true;
        } catch (Exception e2) {
            cachedPrefs = null;
            initialized = false;
            Log.e(TAG, "Не смогли создать prefs даже после сноса файла", e2);
        }
    }

    private static boolean shouldWipeCorruptedPrefs(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String name = t.getClass().getName();
            if (name.contains("AEADBadTag")
                    || name.contains("BadPadding")
                    || name.contains("InvalidCipherText")
                    || name.contains("GeneralSecurityException")
                    || name.contains("InvalidProtocolBuffer")
                    || name.contains("AuthenticationFailed")) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("decrypt")
                        || lower.contains("mac check")
                        || lower.contains("keystore")
                        || lower.contains("signature")
                        || lower.contains("ciphertext")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        // неизвестная ошибка — не сносим ключи; следующий запуск / retry может пройти
        return false;
    }

    private static SharedPreferences createPrefs(Context appCtx) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        return EncryptedSharedPreferences.create(
                appCtx, PREFS_FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    public static SharedPreferences get() { return cachedPrefs; }
}
