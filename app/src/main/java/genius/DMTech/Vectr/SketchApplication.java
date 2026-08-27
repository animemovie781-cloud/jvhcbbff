package genius.DMTech.Vectr;

import android.app.Application;

public class SketchApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SecurePrefsProvider.init(this);
    }
}
