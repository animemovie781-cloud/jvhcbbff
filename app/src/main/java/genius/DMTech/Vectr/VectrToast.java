package genius.DMTech.Vectr;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public final class VectrToast {
    private VectrToast() {}

    public static void show(Context context, String text) {
        show(context, text, false);
    }

    public static void showError(Context context, String text) {
        show(context, text, true);
    }

    private static void show(Context context, String text, boolean error) {
        if (context == null || text == null) return;
        try {
            View v = LayoutInflater.from(context).inflate(R.layout.custom_toast, null);
            TextView tv = v.findViewById(R.id.toast_text);
            View accent = v.findViewById(R.id.toast_accent);
            tv.setText(text);
            if (accent != null) {
                accent.setBackgroundColor(context.getColor(error ? R.color.error : R.color.accent));
            }
            Toast toast = new Toast(context.getApplicationContext());
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(v);
            toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(context, 96));
            toast.show();
        } catch (Exception e) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
