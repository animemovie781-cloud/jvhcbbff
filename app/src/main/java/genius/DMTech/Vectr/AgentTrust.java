package genius.DMTech.Vectr;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whitelist бинарей для run_command + auto-accept правок файлов.
 * Матч: первый токен команды → basename → lowercase (ls матчит «ls -la»).
 */
public final class AgentTrust {

    public static final String RUNNER_SHELL = "shell";
    public static final String RUNNER_TERMUX = "termux";

    private static final String KEY_WHITELIST = "cmd_whitelist";
    private static final String KEY_AUTO_ACCEPT = "auto_accept_edits";
    private static final String KEY_RUNNER = "cmd_auto_runner";

    private AgentTrust() {}

    public static String extractBinary(String command) {
        if (command == null) return "";
        String c = command.trim();
        if (c.isEmpty()) return "";
        int sp = -1;
        for (int i = 0; i < c.length(); i++) {
            if (Character.isWhitespace(c.charAt(i))) {
                sp = i;
                break;
            }
        }
        String first = sp < 0 ? c : c.substring(0, sp);
        int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
        if (slash >= 0 && slash < first.length() - 1) {
            first = first.substring(slash + 1);
        }
        return first.toLowerCase(Locale.US);
    }

    public static boolean isCommandTrusted(String command) {
        String bin = extractBinary(command);
        if (bin.isEmpty()) return false;
        return getWhitelist().contains(bin);
    }

    public static List<String> getWhitelist() {
        SharedPreferences p = SecurePrefsProvider.get();
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        String raw = p.getString(KEY_WHITELIST, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim().toLowerCase(Locale.US);
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void addBinary(String binary) {
        if (binary == null) return;
        String b = binary.trim().toLowerCase(Locale.US);
        if (b.isEmpty()) return;
        List<String> list = getWhitelist();
        if (list.contains(b)) return;
        list.add(b);
        saveWhitelist(list);
    }

    public static void addBinariesFromCommands(List<String> commands) {
        if (commands == null) return;
        for (String cmd : commands) {
            String b = extractBinary(cmd);
            if (!b.isEmpty()) addBinary(b);
        }
    }

    public static void removeBinary(String binary) {
        if (binary == null) return;
        String b = binary.trim().toLowerCase(Locale.US);
        List<String> list = getWhitelist();
        if (!list.remove(b)) return;
        saveWhitelist(list);
    }

    public static void clearWhitelist() {
        saveWhitelist(new ArrayList<>());
    }

    private static void saveWhitelist(List<String> list) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p == null) return;
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        p.edit().putString(KEY_WHITELIST, arr.toString()).apply();
    }

    public static boolean isAutoAcceptEdits() {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null && p.getBoolean(KEY_AUTO_ACCEPT, false);
    }

    public static void setAutoAcceptEdits(boolean on) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p != null) p.edit().putBoolean(KEY_AUTO_ACCEPT, on).apply();
    }

    public static String getAutoRunner() {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p == null) return RUNNER_SHELL;
        String r = p.getString(KEY_RUNNER, RUNNER_SHELL);
        return RUNNER_TERMUX.equals(r) ? RUNNER_TERMUX : RUNNER_SHELL;
    }

    public static void setAutoRunner(String runner) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p == null) return;
        String r = RUNNER_TERMUX.equals(runner) ? RUNNER_TERMUX : RUNNER_SHELL;
        p.edit().putString(KEY_RUNNER, r).apply();
    }
}
