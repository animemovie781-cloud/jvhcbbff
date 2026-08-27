package genius.DMTech.Vectr;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.core.view.WindowCompat;
import com.google.android.material.button.MaterialButton;

public class LanguageActivity extends VectrActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // If language already picked, move to WelcomeActivity or Home
        if (OnboardingPrefs.isLangPicked(this)) {
            startNextActivity();
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_language);

        MaterialButton btnEnglish = findViewById(R.id.btn_english);
        MaterialButton btnHindi = findViewById(R.id.btn_hindi);
        MaterialButton btnRussian = findViewById(R.id.btn_russian);

        btnEnglish.setOnClickListener(v -> {
            selectLanguage(LocaleHelper.LANG_EN);
        });

        btnHindi.setOnClickListener(v -> {
            selectLanguage(LocaleHelper.LANG_HI);
        });

        btnRussian.setOnClickListener(v -> {
            selectLanguage(LocaleHelper.LANG_RU);
        });
    }

    private void selectLanguage(int langIndex) {
        LocaleHelper.setLangIndex(langIndex);
        OnboardingPrefs.setLangPicked(this, true);
        
        // Restart activity to apply language immediately if needed, 
        // but since we are moving to the next screen, we can just proceed.
        startNextActivity();
    }

    private void startNextActivity() {
        Intent intent = new Intent(this, WelcomeActivity.class);
        startActivity(intent);
        finish();
    }
}
