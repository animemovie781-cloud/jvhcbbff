package genius.DMTech.Vectr;

import genius.DMTech.Vectr.WelcomeActivity;
import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.Intent;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.net.Uri;
import android.os.*;
import android.os.Bundle;
import android.text.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.View;
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import genius.DMTech.Vectr.databinding.*;
import java.io.*;
import java.io.InputStream;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import android.view.Menu;
import android.view.MenuItem;

public class MainActivity extends AppCompatActivity {
	
	private MainBinding binding;
	private FragmentManager fragmentManager;
	private Fragment activeFragment;
	private Fragment filesFragment;
	private Fragment chatFragment;
	private Fragment editorFragment;
	private Fragment settingsFragment;
	
	private Intent u = new Intent();
	private Intent intent = new Intent();
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = MainBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		
		binding.btnContinue.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				u.setClass(getApplicationContext(), HomeActivity.class);
				startActivity(u);
				finish();
			}
		});
	}
	
	private void initializeLogic() {
	}
	
	public void _switchFragment(final String _tabId) {
		Fragment target;
		int targetId;
		
		if (_tabId.equals("files")) { target = filesFragment; targetId = R.id.nav_files; }
		else if (_tabId.equals("chat")) { target = chatFragment; targetId = R.id.nav_chat; }
		else if (_tabId.equals("editor")) { target = editorFragment; targetId = R.id.nav_editor; }
		else { target = settingsFragment; targetId = R.id.nav_settings; }
		
		if (target != activeFragment) {
			fragmentManager.beginTransaction()
			.hide(activeFragment)
			.show(target)
			.commit();
			activeFragment = target;
		}
		
		/* if (bottomNav.getSelectedItemId() != targetId) {
        bottomNav.setSelectedItemId(targetId);
    }*/
	}
	
}