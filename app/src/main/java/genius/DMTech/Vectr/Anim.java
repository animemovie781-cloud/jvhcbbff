package genius.DMTech.Vectr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** Лёгкие UI-анимации для настроек и общих экранов. */
public final class Anim {
    private Anim() {}

    public static void fadeSlideIn(View view, int delayMs) {
        if (view == null) return;
        view.setAlpha(0f);
        view.setTranslationY(18f);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public static void staggerChildren(ViewGroup parent, int startDelay, int step) {
        if (parent == null) return;
        int delay = startDelay;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            fadeSlideIn(child, delay);
            delay += step;
        }
    }

    public static void expand(View view) {
        if (view == null) return;
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        int widthSpec = parent != null
                ? View.MeasureSpec.makeMeasureSpec(parent.getWidth(), View.MeasureSpec.EXACTLY)
                : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int target = Math.max(view.getMeasuredHeight(), 1);
        view.getLayoutParams().height = 0;
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofInt(0, target);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            view.getLayoutParams().height = (int) a.getAnimatedValue();
            view.requestLayout();
            view.setAlpha(a.getAnimatedFraction());
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.requestLayout();
                view.setAlpha(1f);
            }
        });
        anim.start();
    }

    public static void collapse(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        int start = view.getHeight();
        ValueAnimator anim = ValueAnimator.ofInt(start, 0);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            view.getLayoutParams().height = (int) a.getAnimatedValue();
            view.requestLayout();
            view.setAlpha(1f - a.getAnimatedFraction());
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                view.setVisibility(View.GONE);
                view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setAlpha(1f);
            }
        });
        anim.start();
    }

    public static void pulseSelect(View view) {
        if (view == null) return;
        ObjectAnimator sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.04f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.04f, 1f);
        sx.setDuration(180);
        sy.setDuration(180);
        sx.setInterpolator(new OvershootInterpolator(1.4f));
        sy.setInterpolator(new OvershootInterpolator(1.4f));
        sx.start();
        sy.start();
    }
}
