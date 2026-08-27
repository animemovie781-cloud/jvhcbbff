package genius.DMTech.Vectr;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Базовая Activity: тема (DayNight) + локаль (ru/en) из SecurePrefs. */
public abstract class VectrActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        SecurePrefsProvider.init(newBase);
        ThemeHelper.applyFromPrefs();
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SecurePrefsProvider.init(this);
        ThemeHelper.applyFromPrefs();
        super.onCreate(savedInstanceState);
    }
}
