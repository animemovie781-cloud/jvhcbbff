package genius.DMTech.Vectr;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.view.Menu;
import android.view.MenuItem;
import genius.DMTech.Vectr.databinding.ActivityHomeBinding;

public class HomeActivity extends VectrActivity {

	private ActivityHomeBinding binding;
	private FragmentManager fragmentManager;
	private Fragment activeFragment;
	private Fragment filesFragment;
	private Fragment chatFragment;
	private Fragment editorFragment;
	private Fragment settingsFragment;
	private BottomNavigationView bottomNav;
	private View bottomNavDivider;

	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		AiStreamManager.getInstance().init(this);
		AiStreamManager.getInstance().ensureBound();
		// клавиатура сжимает layout, а не pan'ит поверх EditText
		getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
		binding = ActivityHomeBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initializeLogic(_savedInstanceState);
		setupKeyboardInsets();
	}

	/** Прячем bottom nav над клавиатурой, чтобы поле ввода не упиралось в IME. */
	private void setupKeyboardInsets() {
		View root = binding.getRoot();
		bottomNavDivider = binding.bottomNavDivider;
		bottomNav = binding.bottomNav;
		ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
			Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
			boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0;
			if (bottomNav != null) {
				bottomNav.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
			}
			if (bottomNavDivider != null) {
				bottomNavDivider.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
			}
			return insets;
		});
	}

	@Override
	protected void onStart() {
		super.onStart();
		// приложение снова на экране — убираем "Vectr работает", если стрим ещё идёт
		AiStreamManager.getInstance().setAppInForeground(true);
		AiStreamManager.getInstance().ensureBound();
		AiStreamManager.getInstance().dismissDoneNotification();
	}

	@Override
	protected void onStop() {
		// сворачивание / другой Activity — тогда (и только тогда) показываем working-нотиф
		AiStreamManager.getInstance().setAppInForeground(false);
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		// theme/locale recreate() — не рвём bind стрима
		if (isFinishing()) {
			AiStreamManager.getInstance().unbind();
		}
		super.onDestroy();
	}

	private void initializeLogic(Bundle savedInstanceState) {
		try {
			MaterialToolbar toolbar = binding.toolbar;
			setSupportActionBar(toolbar);

			fragmentManager = getSupportFragmentManager();

			if (savedInstanceState == null) {
				filesFragment = new FileManagerFragment();
				chatFragment = new ChatFragment();
				editorFragment = new EditorFragment();
				settingsFragment = new SettingsFragment();

				fragmentManager.beginTransaction()
					.add(R.id.fragment_container, settingsFragment, "settings").hide(settingsFragment)
					.add(R.id.fragment_container, editorFragment, "editor").hide(editorFragment)
					.add(R.id.fragment_container, chatFragment, "chat").hide(chatFragment)
					.add(R.id.fragment_container, filesFragment, "files")
					.commit();

				activeFragment = filesFragment;
			} else {
				filesFragment = fragmentManager.findFragmentByTag("files");
				chatFragment = fragmentManager.findFragmentByTag("chat");
				editorFragment = fragmentManager.findFragmentByTag("editor");
				settingsFragment = fragmentManager.findFragmentByTag("settings");
				activeFragment = filesFragment;
				Fragment[] all = {filesFragment, chatFragment, editorFragment, settingsFragment};
				for (Fragment f : all) {
					if (f != null && f.isAdded() && !f.isHidden()) {
						activeFragment = f;
						break;
					}
				}
			}

			bottomNav = binding.bottomNav;
			bottomNav.setOnItemSelectedListener(item -> {
				int id = item.getItemId();
				if (id == R.id.nav_files) _switchFragment("files");
				else if (id == R.id.nav_chat) _switchFragment("chat");
				else if (id == R.id.nav_editor) _switchFragment("editor");
				else _switchFragment("settings");
				return true;
			});
			invalidateOptionsMenu();

			// после bottomNav — иначе selectedItemId не применится
			handleOpenTabIntent(getIntent());
		} catch (Exception e) {
			android.util.Log.e("HomeActivity", "initializeLogic failed", e);
			VectrToast.showError(this, getString(R.string.error_init_failed));
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_save) {
			Fragment editor = getSupportFragmentManager().findFragmentByTag("editor");
			if (editor instanceof EditorFragment) {
				((EditorFragment) editor).saveCurrentFile();
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.toolbar_menu, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem save = menu.findItem(R.id.action_save);
		if (save != null) {
			// Save только на вкладке Code — на Files/Agent/Options бессмысленен
			save.setVisible(activeFragment == editorFragment);
		}
		return super.onPrepareOptionsMenu(menu);
	}

	public void _switchFragment(final String _tabId) {
		Fragment target;

		if (_tabId.equals("files")) target = filesFragment;
		else if (_tabId.equals("chat")) target = chatFragment;
		else if (_tabId.equals("editor")) target = editorFragment;
		else target = settingsFragment;

		if (target == activeFragment) return;

		int from = tabIndex(activeFragment);
		int to = tabIndex(target);
		boolean goingRight = to >= from;

		fragmentManager.beginTransaction()
			.setCustomAnimations(
					goingRight ? R.anim.tab_enter_right : R.anim.tab_enter_left,
					goingRight ? R.anim.tab_exit_left : R.anim.tab_exit_right)
			.hide(activeFragment)
			.show(target)
			.commitAllowingStateLoss();
		activeFragment = target;
		invalidateOptionsMenu();
	}

	private int tabIndex(Fragment f) {
		if (f == filesFragment) return 0;
		if (f == chatFragment) return 1;
		if (f == editorFragment) return 2;
		return 3;
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		handleOpenTabIntent(intent);
	}

	private void handleOpenTabIntent(Intent intent) {
		if (intent == null) return;
		String tab = intent.getStringExtra("open_tab");
		if (tab == null) return;
		if ("settings".equals(tab)) {
			_switchFragment("settings");
			if (bottomNav != null) bottomNav.setSelectedItemId(R.id.nav_settings);
		} else if ("chat".equals(tab)) {
			_switchFragment("chat");
			if (bottomNav != null) bottomNav.setSelectedItemId(R.id.nav_chat);
		}
	}

	/** Открыть чат и вставить готовый промпт (из редактора «Исправить…»). */
	public void sendToAgent(String prompt) {
		_switchFragment("chat");
		if (bottomNav != null) bottomNav.setSelectedItemId(R.id.nav_chat);
		if (chatFragment instanceof ChatFragment) {
			((ChatFragment) chatFragment).composeAndFocus(prompt);
		}
	}
}
