package genius.DMTech.Vectr;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;

/**
 * Экран 2/3 — политика конфиденциальности (обязательное согласие для Play).
 * Layout: activity_xml/activity_policy.xml
 * Иконка: @drawable/ic_shield (добавь) или fallback ic_lock_outline
 */
public class PolicyActivity extends VectrActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_policy);
        OnboardingInsets.apply(findViewById(R.id.policy_root), 24, 12, 16);

        TextView body = findViewById(R.id.policy_body);
        CheckBox accept = findViewById(R.id.policy_accept);
        MaterialButton btnContinue = findViewById(R.id.btn_policy_continue);
        MaterialButton btnBack = findViewById(R.id.btn_policy_back);

        if (body != null) body.setText(R.string.policy_full_text);
        btnContinue.setEnabled(false);

        accept.setOnCheckedChangeListener((buttonView, isChecked) -> {
            btnContinue.setEnabled(isChecked);
            if (isChecked) Anim.pulseSelect(btnContinue);
        });

        btnBack.setOnClickListener(v -> {
            startActivity(new Intent(this, WelcomeActivity.class));
            overridePendingTransition(R.anim.onboard_enter_left, R.anim.onboard_exit_right);
            finish();
        });

        btnContinue.setOnClickListener(v -> {
            if (!accept.isChecked()) {
                VectrToast.showError(this, getString(R.string.policy_must_accept));
                return;
            }
            VectrHaptics.tap(v);
            OnboardingPrefs.setPolicyAccepted(this, true);
            startActivity(new Intent(this, PermissionsActivity.class));
            overridePendingTransition(R.anim.onboard_enter_right, R.anim.onboard_exit_left);
            finish();
        });

        View header = findViewById(R.id.policy_header);
        Anim.fadeSlideIn(header, 60);
        Anim.fadeSlideIn(findViewById(R.id.policy_scroll), 120);
        Anim.fadeSlideIn(findViewById(R.id.policy_footer), 200);
    }
}
