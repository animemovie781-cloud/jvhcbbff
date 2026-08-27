package genius.DMTech.Vectr;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;

/**
 * Экран 1/3 — приветствие.
 * Launcher: если онбординг уже пройден → сразу HomeActivity.
 *
 * Layout: activity_xml/activity_welcome.xml
 * Иконки: @drawable/ic_vectr_mark (добавь вручную), @drawable/ic_smart_toy (есть)
 */
public class WelcomeActivity extends VectrActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (OnboardingPrefs.isDone(this)) {
            goHome(true);
            return;
        }
        // политика уже принята (перезапуск посередине) — продолжаем с нужного шага
        if (OnboardingPrefs.isPolicyAccepted(this)) {
            startActivity(new Intent(this, PermissionsActivity.class));
            finish();
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_welcome);
        OnboardingInsets.apply(findViewById(R.id.welcome_root), 28, 16, 20);

        TextView brand = findViewById(R.id.welcome_brand);
        TextView title = findViewById(R.id.welcome_title);
        TextView subtitle = findViewById(R.id.welcome_subtitle);
        TextView version = findViewById(R.id.welcome_version);
        View mark = findViewById(R.id.welcome_mark);
        MaterialButton btnContinue = findViewById(R.id.btn_welcome_continue);

        if (version != null) version.setText(R.string.welcome_version);

        Anim.fadeSlideIn(mark, 40);
        Anim.fadeSlideIn(brand, 100);
        Anim.fadeSlideIn(title, 160);
        Anim.fadeSlideIn(subtitle, 220);
        Anim.fadeSlideIn(btnContinue, 300);
        Anim.fadeSlideIn(version, 360);

        btnContinue.setOnClickListener(v -> {
            VectrHaptics.tap(v);
            startActivity(new Intent(this, PolicyActivity.class));
            overridePendingTransition(R.anim.onboard_enter_right, R.anim.onboard_exit_left);
            finish();
        });
    }

    private void goHome(boolean clearTask) {
        Intent i = new Intent(this, HomeActivity.class);
        if (clearTask) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        startActivity(i);
        finish();
    }
}
