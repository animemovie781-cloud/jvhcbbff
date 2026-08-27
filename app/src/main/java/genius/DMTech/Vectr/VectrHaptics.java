package genius.DMTech.Vectr;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.view.HapticFeedbackConstants;

/** Короткие тактильные импульсы (отправка / ответ агента). */
public final class VectrHaptics {
    private VectrHaptics() {}

    public static void send(Context context) {
        pulse(context, 5);
    }

    public static void replyDone(Context context) {
        pulse(context, 10);
    }

    public static void pulse(Context context, long millis) {
        if (context == null || millis <= 0) return;
        try {
            Vibrator vibrator = resolve(context);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(millis,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(millis);
            }
        } catch (Exception ignored) {}
    }

    /** Лёгкий системный тап (fallback для кнопок). */
    public static void tap(View view) {
        if (view == null) return;
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static Vibrator resolve(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = context.getSystemService(VibratorManager.class);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
