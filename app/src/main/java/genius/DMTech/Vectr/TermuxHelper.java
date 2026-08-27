package genius.DMTech.Vectr;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Проверка Termux / Termux:Tasker и разрешение RUN_COMMAND.
 * Для Android 11+ в манифесте нужны &lt;queries&gt; на пакеты.
 */
public final class TermuxHelper {

    public static final String PKG_TERMUX = "com.termux";
    public static final String PKG_TERMUX_TASKER = "com.termux.tasker";
    /** Особое разрешение «Run commands in Termux environment». */
    public static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    public static final String FDROID_TERMUX =
            "https://f-droid.org/packages/com.termux/";
    public static final String FDROID_TERMUX_TASKER =
            "https://f-droid.org/packages/com.termux.tasker/";
    public static final String FDROID_APP =
            "https://f-droid.org/packages/org.fdroid.fdroid/";

    private TermuxHelper() {}

    public static boolean isPackageInstalled(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        try {
            PackageManager pm = context.getPackageManager();
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
            } else {
                pm.getPackageInfo(packageName, 0);
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isTermuxInstalled(Context context) {
        return isPackageInstalled(context, PKG_TERMUX);
    }

    public static boolean isTermuxTaskerInstalled(Context context) {
        return isPackageInstalled(context, PKG_TERMUX_TASKER);
    }

    public static boolean hasRunCommandPermission(Context context) {
        if (context == null) return false;
        return ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void openUrl(Context context, String url) {
        if (context == null || url == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    public static void openAppDetails(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + context.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    public static void openTermuxApp(Context context) {
        if (context == null) return;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(PKG_TERMUX);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
            } else {
                openUrl(context, FDROID_TERMUX);
            }
        } catch (Exception e) {
            openUrl(context, FDROID_TERMUX);
        }
    }
}
