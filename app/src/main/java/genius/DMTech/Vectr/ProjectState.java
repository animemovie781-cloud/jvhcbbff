package genius.DMTech.Vectr;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

public class ProjectState {
    private static volatile ProjectState instance;

    public Uri currentFileUri;
    public String currentFileName;
    public Uri projectRootUri;

    // табы
    public List<OpenFileTab> openTabs = new ArrayList<>();
    public int currentTabIndex = -1;

    public static ProjectState getInstance() {
        if (instance == null) {
            synchronized (ProjectState.class) {
                if (instance == null) instance = new ProjectState();
            }
        }
        return instance;
    }

    public OpenFileTab getCurrentTab() {
        if (currentTabIndex >= 0 && currentTabIndex < openTabs.size()) {
            return openTabs.get(currentTabIndex);
        }
        return null;
    }

    /** Открыть файл — вернуть существующий таб или null если такого нет */
    public int findTabByUri(Uri uri) {
        if (uri == null) return -1;
        for (int i = 0; i < openTabs.size(); i++) {
            Uri tabUri = openTabs.get(i).uri;
            if (tabUri != null && uri.equals(tabUri)) return i;
        }
        return -1;
    }

    /** Закрыть таб, вернуть индекс на который переключиться */
    public int closeTab(int index) {
        if (index < 0 || index >= openTabs.size()) return -1;
        openTabs.remove(index);

        if (openTabs.isEmpty()) {
            currentTabIndex = -1;
            currentFileUri = null;
            currentFileName = null;
            return -1;
        }

        // выбираем соседний слева, либо первый
        if (currentTabIndex >= openTabs.size()) {
            currentTabIndex = openTabs.size() - 1;
        } else if (currentTabIndex == index) {
            // закрыли активный — переходим на ближайший слева
            if (index > 0) currentTabIndex = index - 1;
            else currentTabIndex = 0;
        } else if (currentTabIndex > index) {
            currentTabIndex--;
        }

        OpenFileTab newCurrent = openTabs.get(currentTabIndex);
        currentFileUri = newCurrent.uri;
        currentFileName = newCurrent.name;
        return currentTabIndex;
    }
}
