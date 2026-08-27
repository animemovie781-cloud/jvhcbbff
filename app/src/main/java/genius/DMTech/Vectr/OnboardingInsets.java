package genius.DMTech.Vectr;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Сохраняет горизонтальный content-padding при системных insets (иначе XML-паддинг затирается). */
public final class OnboardingInsets {
    private OnboardingInsets() {}

    public static void apply(View root, int horizontalDp, int extraTopDp, int extraBottomDp) {
        if (root == null) return;
        float d = root.getResources().getDisplayMetrics().density;
        final int h = Math.round(horizontalDp * d);
        final int topExtra = Math.round(extraTopDp * d);
        final int bottomExtra = Math.round(extraBottomDp * d);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left + h, bars.top + topExtra, bars.right + h, bars.bottom + bottomExtra);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
