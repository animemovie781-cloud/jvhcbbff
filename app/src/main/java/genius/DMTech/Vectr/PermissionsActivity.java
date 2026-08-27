package genius.DMTech.Vectr;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * Экран 3/3 — уведомления + Termux (установка F-Droid + RUN_COMMAND).
 * Layout: activity_xml/activity_permissions.xml
 *
 * Иконки (добавь вручную, если нет):
 *   ic_notifications, ic_termux, ic_fdroid, ic_open_external, ic_check_circle
 * Уже есть и можно подставить: ic_campaign, ic_file_shell, ic_done_all, ic_lock_outline
 */
public class PermissionsActivity extends VectrActivity {

    private TextView notifStatus;
    private TextView termuxPermStatus;
    private TextView pkgTermuxStatus;
    private TextView pkgTaskerStatus;
    private MaterialButton btnNotif;
    private MaterialButton btnTermuxPerm;

    private final ActivityResultLauncher<String> notifLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                OnboardingPrefs.setNotifAsked(this, true);
                refreshStatuses();
                if (granted) {
                    VectrToast.show(this, getString(R.string.perm_notif_granted));
                }
            });

    private final ActivityResultLauncher<String> termuxPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                OnboardingPrefs.setTermuxAsked(this, true);
                refreshStatuses();
                if (granted) {
                    VectrToast.show(this, getString(R.string.perm_termux_granted));
                } else {
                    // на части прошивок диалог не показывается — ведём в настройки приложения
                    VectrToast.show(this, getString(R.string.perm_termux_open_settings_hint));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_permissions);
        OnboardingInsets.apply(findViewById(R.id.permissions_root), 24, 12, 16);

        notifStatus = findViewById(R.id.perm_notif_status);
        termuxPermStatus = findViewById(R.id.perm_termux_status);
        pkgTermuxStatus = findViewById(R.id.pkg_termux_status);
        pkgTaskerStatus = findViewById(R.id.pkg_tasker_status);
        btnNotif = findViewById(R.id.btn_perm_notif);
        btnTermuxPerm = findViewById(R.id.btn_perm_termux);

        btnNotif.setOnClickListener(v -> requestNotifications());
        btnTermuxPerm.setOnClickListener(v -> requestTermuxPermission());

        findViewById(R.id.btn_open_app_settings).setOnClickListener(v ->
                TermuxHelper.openAppDetails(this));

        findViewById(R.id.btn_install_fdroid).setOnClickListener(v ->
                TermuxHelper.openUrl(this, TermuxHelper.FDROID_APP));
        findViewById(R.id.btn_install_termux).setOnClickListener(v ->
                TermuxHelper.openUrl(this, TermuxHelper.FDROID_TERMUX));
        findViewById(R.id.btn_install_tasker).setOnClickListener(v ->
                TermuxHelper.openUrl(this, TermuxHelper.FDROID_TERMUX_TASKER));

        MaterialButton btnFinish = findViewById(R.id.btn_perm_finish);
        MaterialButton btnSkip = findViewById(R.id.btn_perm_skip);

        btnFinish.setOnClickListener(v -> finishOnboarding());
        btnSkip.setOnClickListener(v -> finishOnboarding());

        MaterialCardView cardNotif = findViewById(R.id.card_perm_notif);
        MaterialCardView cardTermux = findViewById(R.id.card_perm_termux);
        MaterialCardView cardInstall = findViewById(R.id.card_perm_install);
        Anim.fadeSlideIn(cardNotif, 60);
        Anim.fadeSlideIn(cardTermux, 120);
        Anim.fadeSlideIn(cardInstall, 180);
        Anim.fadeSlideIn(btnFinish, 240);

        refreshStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatuses();
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) {
            VectrToast.show(this, getString(R.string.perm_notif_not_needed));
            refreshStatuses();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            refreshStatuses();
            return;
        }
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void requestTermuxPermission() {
        if (TermuxHelper.hasRunCommandPermission(this)) {
            refreshStatuses();
            return;
        }
        // без установленных Termux/Tasker разрешение часто бесполезно — мягко подскажем
        if (!TermuxHelper.isTermuxInstalled(this) || !TermuxHelper.isTermuxTaskerInstalled(this)) {
            VectrToast.show(this, getString(R.string.perm_termux_install_first));
        }
        termuxPermLauncher.launch(TermuxHelper.PERMISSION_RUN_COMMAND);
    }

    private void refreshStatuses() {
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        setStatus(notifStatus, notifOk,
                getString(R.string.perm_status_granted),
                getString(R.string.perm_status_needed));
        btnNotif.setText(notifOk ? R.string.perm_btn_done : R.string.perm_btn_allow);
        btnNotif.setEnabled(!notifOk);

        boolean termuxOk = TermuxHelper.hasRunCommandPermission(this);
        setStatus(termuxPermStatus, termuxOk,
                getString(R.string.perm_status_granted),
                getString(R.string.perm_status_needed));
        btnTermuxPerm.setText(termuxOk ? R.string.perm_btn_done : R.string.perm_btn_allow);

        boolean tInstalled = TermuxHelper.isTermuxInstalled(this);
        boolean taskerInstalled = TermuxHelper.isTermuxTaskerInstalled(this);
        setStatus(pkgTermuxStatus, tInstalled,
                getString(R.string.perm_pkg_installed),
                getString(R.string.perm_pkg_missing));
        setStatus(pkgTaskerStatus, taskerInstalled,
                getString(R.string.perm_pkg_installed),
                getString(R.string.perm_pkg_missing));
    }

    private void setStatus(TextView view, boolean ok, String okText, String badText) {
        if (view == null) return;
        view.setText(ok ? okText : badText);
        view.setTextColor(getColor(ok ? R.color.success : R.color.warning_orange));
    }

    private void finishOnboarding() {
        OnboardingPrefs.setDone(this, true);
        VectrHaptics.send(this);
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        overridePendingTransition(R.anim.onboard_enter_right, R.anim.onboard_exit_left);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatuses();
    }
}
