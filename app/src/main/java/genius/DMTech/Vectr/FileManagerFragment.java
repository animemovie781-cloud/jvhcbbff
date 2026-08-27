package genius.DMTech.Vectr;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileManagerFragment extends Fragment {

    private TextView currentPath;
    private TextView emptyState;
    private RecyclerView fileList;
    private ProgressBar filesProgress;
    private ImageButton btnUp;
    private ImageButton btnNew;
    private FileAdapter fileAdapter;

    private static final String PREFS = "vectr_prefs";
    private static final String KEY_TREE_URI = "tree_uri";

    private DocumentFile projectRoot;
    private DocumentFile currentDir;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ProjectRepository projectRepository;
    private final List<String> pathStack = new ArrayList<>();
    private final List<DocumentFile> dirStack = new ArrayList<>();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int loadToken = 0;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_files, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        currentPath = view.findViewById(R.id.current_path);
        emptyState = view.findViewById(R.id.empty_state);
        fileList = view.findViewById(R.id.file_list);
        filesProgress = view.findViewById(R.id.files_progress);
        btnUp = view.findViewById(R.id.btn_up);
        btnNew = view.findViewById(R.id.btn_new);
        FloatingActionButton fabOpenFolder = view.findViewById(R.id.fab_open_folder);
        ImageButton btnRecentProjects = view.findViewById(R.id.btn_recent_projects);

        projectRepository = new ProjectRepository(requireContext().getApplicationContext());

        fileList.setLayoutManager(new LinearLayoutManager(getContext()));
        // без DividerItemDecoration — плотнее список, разделение за счёт фона/тапа
        fileAdapter = new FileAdapter(new FileAdapter.Listener() {
            @Override
            public void onOpen(FileEntry entry) {
                handleOpen(entry);
            }

            @Override
            public void onMore(FileEntry entry, View anchor) {
                showEntryMenu(entry, anchor);
            }
        });
        fileList.setAdapter(fileAdapter);

        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == getActivity().RESULT_OK && result.getData() != null) {
                        Uri treeUri = result.getData().getData();
                        handleFolderSelected(treeUri);
                    }
                }
        );

        fabOpenFolder.setOnClickListener(v -> openFolderPicker());
        btnRecentProjects.setOnClickListener(v -> showRecentProjectsDialog());
        btnUp.setOnClickListener(v -> navigateUp());
        btnNew.setOnClickListener(v -> showCreateMenu(v));

        restoreLastFolder();
    }

    @Override
    public void onDestroyView() {
        loadToken++;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        try {
            io.shutdownNow();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        folderPickerLauncher.launch(intent);
    }

    private void showRecentProjectsDialog() {
        new Thread(() -> {
            List<ProjectRepository.ProjectEntry> projects = projectRepository.listRecentProjects();
            main.post(() -> {
                if (!isAdded()) return;
                if (projects.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.files_no_recent, Toast.LENGTH_SHORT).show();
                    return;
                }

                String[] names = new String[projects.size()];
                for (int i = 0; i < projects.size(); i++) names[i] = projects.get(i).name;

                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.files_recent_title)
                        .setItems(names, (dialog, which) -> {
                            Uri uri = Uri.parse(projects.get(which).treeUri);
                            handleFolderSelected(uri);
                        })
                        .show();
            });
        }).start();
    }

    private void handleFolderSelected(Uri treeUri) {
        if (treeUri == null) return;

        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (SecurityException ignored) {
        }
        saveTreeUri(treeUri);

        DocumentFile rootDir = DocumentFile.fromTreeUri(requireContext(), treeUri);
        if (rootDir == null || !rootDir.exists()) {
            Toast.makeText(requireContext(), R.string.files_open_folder_fail, Toast.LENGTH_SHORT).show();
            return;
        }

        projectRoot = rootDir;
        currentDir = rootDir;
        pathStack.clear();
        dirStack.clear();
        pathStack.add(rootDir.getName() != null ? rootDir.getName() : "/");
        dirStack.add(rootDir);
        currentPath.setText(buildPathLabel());
        ProjectState.getInstance().projectRootUri = treeUri;
        btnNew.setVisibility(View.VISIBLE);
        updateUpButton();

        String name = rootDir.getName() != null ? rootDir.getName() : "Проект";
        new Thread(() -> projectRepository.saveOrTouchProject(treeUri.toString(), name)).start();

        loadFiles(rootDir);
    }

    private void handleOpen(FileEntry entry) {
        if (entry.isParent) {
            navigateUp();
            return;
        }
        if (entry.isDirectory) {
            currentDir = entry.file;
            pathStack.add(entry.name);
            dirStack.add(entry.file);
            currentPath.setText(buildPathLabel());
            updateUpButton();
            loadFiles(entry.file);
            return;
        }

        Fragment editor = getParentFragmentManager().findFragmentByTag("editor");
        if (editor instanceof EditorFragment) {
            ((EditorFragment) editor).openFile(entry.file.getUri(), entry.name, relativePathFor(entry.name));
        }
        requireView().post(() -> {
            if (getActivity() instanceof HomeActivity) {
                ((HomeActivity) getActivity())._switchFragment("editor");
            }
        });
    }

    private String relativePathFor(String fileName) {
        if (pathStack.size() <= 1) return fileName;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < pathStack.size(); i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(pathStack.get(i));
        }
        if (sb.length() > 0) sb.append('/');
        sb.append(fileName);
        return sb.toString();
    }

    private void navigateUp() {
        if (projectRoot == null || dirStack.size() <= 1) return;

        dirStack.remove(dirStack.size() - 1);
        if (!pathStack.isEmpty()) pathStack.remove(pathStack.size() - 1);
        currentDir = dirStack.get(dirStack.size() - 1);
        currentPath.setText(buildPathLabel());
        updateUpButton();
        loadFiles(currentDir);
    }

    private boolean isAtRoot() {
        return dirStack.size() <= 1;
    }

    private void updateUpButton() {
        boolean canUp = projectRoot != null && !isAtRoot();
        btnUp.setEnabled(canUp);
        btnUp.setAlpha(canUp ? 1f : 0.35f);
    }

    private void loadFiles(DocumentFile dir) {
        if (dir == null) return;
        final int token = ++loadToken;
        filesProgress.setVisibility(View.VISIBLE);

        io.execute(() -> {
            DocumentFile[] children;
            String listError = null;
            try {
                children = dir.listFiles();
            } catch (Exception e) {
                children = new DocumentFile[0];
                listError = e.getMessage();
            }
            if (children == null) children = new DocumentFile[0];

            List<FileEntry> dirs = new ArrayList<>();
            List<FileEntry> filesOnly = new ArrayList<>();
            for (DocumentFile child : children) {
                if (child == null || child.getName() == null) continue;
                FileEntry entry = FileEntry.from(child);
                if (entry.isDirectory) dirs.add(entry);
                else filesOnly.add(entry);
            }

            Comparator<FileEntry> byName = (a, b) ->
                    a.name.compareToIgnoreCase(b.name);
            Collections.sort(dirs, byName);
            Collections.sort(filesOnly, byName);

            List<FileEntry> result = new ArrayList<>();
            if (projectRoot != null && dirStack.size() > 1) {
                result.add(FileEntry.parent());
            }
            result.addAll(dirs);
            result.addAll(filesOnly);

            final List<FileEntry> finalList = result;
            final String errMsg = listError;
            main.post(() -> {
                if (!isAdded() || token != loadToken) return;
                filesProgress.setVisibility(View.GONE);
                if (errMsg != null) {
                    Toast.makeText(requireContext(), R.string.files_list_error, Toast.LENGTH_SHORT).show();
                }
                if (projectRoot == null) {
                    emptyState.setVisibility(View.VISIBLE);
                    emptyState.setText(R.string.files_empty_project);
                } else {
                    boolean noRealEntries = true;
                    for (FileEntry e : finalList) {
                        if (!e.isParent) { noRealEntries = false; break; }
                    }
                    if (noRealEntries && finalList.isEmpty()) {
                        emptyState.setVisibility(View.VISIBLE);
                        emptyState.setText(R.string.files_empty_folder);
                    } else {
                        emptyState.setVisibility(View.GONE);
                    }
                }
                fileAdapter.submit(finalList);
            });
        });
    }

    private void showCreateMenu(View anchor) {
        if (currentDir == null) {
            Toast.makeText(requireContext(), R.string.files_open_first, Toast.LENGTH_SHORT).show();
            return;
        }
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.files_new_file);
        menu.getMenu().add(0, 2, 1, R.string.files_new_folder);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) promptCreate(false);
            else if (item.getItemId() == 2) promptCreate(true);
            return true;
        });
        menu.show();
    }

    private void showEntryMenu(FileEntry entry, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.files_rename);
        menu.getMenu().add(0, 2, 1, R.string.files_delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) promptRename(entry);
            else if (item.getItemId() == 2) confirmDelete(entry);
            return true;
        });
        menu.show();
    }

    private void promptCreate(boolean directory) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(directory ? R.string.files_hint_folder : R.string.files_hint_filename);
        input.setTextColor(requireContext().getColor(R.color.text_primary));
        input.setHintTextColor(requireContext().getColor(R.color.text_tertiary));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(requireContext())
                .setTitle(directory ? R.string.files_new_folder : R.string.files_new_file)
                .setView(input)
                .setPositiveButton(R.string.files_create_action, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
                        Toast.makeText(requireContext(), R.string.files_invalid_name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createEntry(name, directory);
                })
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    private void createEntry(String name, boolean directory) {
        final DocumentFile dir = currentDir;
        if (dir == null) return;
        io.execute(() -> {
            try {
                DocumentFile existing = dir.findFile(name);
                if (existing != null && existing.exists()) {
                    main.post(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(), R.string.files_exists, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                DocumentFile created;
                if (directory) {
                    created = dir.createDirectory(name);
                } else {
                    created = dir.createFile(FileIcons.mimeForFile(name), name);
                }
                main.post(() -> {
                    if (!isAdded()) return;
                    if (created == null) {
                        Toast.makeText(requireContext(), R.string.files_create_failed, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.files_created, name), Toast.LENGTH_SHORT).show();
                        loadFiles(currentDir);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void promptRename(FileEntry entry) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(entry.name);
        input.setSelectAllOnFocus(true);
        input.setTextColor(requireContext().getColor(R.color.text_primary));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.files_rename)
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.equals(entry.name)) return;
                    if (name.contains("/") || name.contains("\\")) {
                        Toast.makeText(requireContext(), R.string.files_invalid_name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    renameEntry(entry, name);
                })
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    private void renameEntry(FileEntry entry, String newName) {
        final DocumentFile dir = currentDir;
        final Uri oldUri = entry.file.getUri();
        io.execute(() -> {
            boolean ok = false;
            Uri newUri = null;
            try {
                ok = entry.file.renameTo(newName);
                if (ok && dir != null) {
                    DocumentFile renamed = dir.findFile(newName);
                    if (renamed != null) newUri = renamed.getUri();
                }
            } catch (Exception ignored) {
            }
            boolean finalOk = ok;
            Uri finalNewUri = newUri;
            main.post(() -> {
                if (!isAdded()) return;
                if (finalOk) {
                    syncTabsAfterRename(oldUri, finalNewUri, newName);
                }
                Toast.makeText(requireContext(),
                        finalOk ? R.string.files_renamed : R.string.files_rename_failed,
                        Toast.LENGTH_SHORT).show();
                loadFiles(currentDir);
            });
        });
    }

    private void confirmDelete(FileEntry entry) {
        CharSequence msg = entry.isDirectory
                ? getString(R.string.files_delete_folder_hint, entry.name)
                : entry.name;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.files_delete_confirm)
                .setMessage(msg)
                .setPositiveButton(R.string.files_delete, (d, w) -> deleteEntry(entry))
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    private void deleteEntry(FileEntry entry) {
        final Uri deletedUri = entry.file.getUri();
        final boolean isDir = entry.isDirectory;
        final String name = entry.name;
        io.execute(() -> {
            boolean ok = false;
            try {
                ok = entry.file.delete();
            } catch (Exception ignored) {
            }
            boolean finalOk = ok;
            main.post(() -> {
                if (!isAdded()) return;
                if (finalOk) {
                    syncTabsAfterDelete(deletedUri, isDir, name);
                }
                Toast.makeText(requireContext(),
                        finalOk ? R.string.files_deleted : R.string.files_delete_failed,
                        Toast.LENGTH_SHORT).show();
                loadFiles(currentDir);
            });
        });
    }

    /** Обновить URI/имя открытых вкладок после rename. */
    private void syncTabsAfterRename(Uri oldUri, Uri newUri, String newName) {
        if (oldUri == null) return;
        ProjectState state = ProjectState.getInstance();
        boolean touched = false;
        for (OpenFileTab tab : state.openTabs) {
            if (tab.uri != null && oldUri.equals(tab.uri)) {
                if (newUri != null) tab.uri = newUri;
                tab.name = newName;
                if (tab.relativePath != null && tab.relativePath.contains("/")) {
                    int slash = tab.relativePath.lastIndexOf('/');
                    tab.relativePath = tab.relativePath.substring(0, slash + 1) + newName;
                } else {
                    tab.relativePath = newName;
                }
                touched = true;
            }
        }
        if (state.currentFileUri != null && oldUri.equals(state.currentFileUri)) {
            if (newUri != null) state.currentFileUri = newUri;
            state.currentFileName = newName;
            touched = true;
        }
        if (touched) refreshEditorTabs();
    }

    /** Закрыть вкладки удалённого файла (или всё под удалённой папкой — по имени в path). */
    private void syncTabsAfterDelete(Uri deletedUri, boolean wasDirectory, String name) {
        ProjectState state = ProjectState.getInstance();
        boolean touched = false;
        for (int i = state.openTabs.size() - 1; i >= 0; i--) {
            OpenFileTab tab = state.openTabs.get(i);
            boolean match = deletedUri != null && deletedUri.equals(tab.uri);
            if (!match && wasDirectory && tab.relativePath != null && name != null) {
                String rp = tab.relativePath.replace('\\', '/');
                match = rp.equals(name) || rp.startsWith(name + "/");
            }
            if (match) {
                state.closeTab(i);
                touched = true;
            }
        }
        if (touched) refreshEditorTabs();
    }

    private void refreshEditorTabs() {
        Fragment editor = getParentFragmentManager().findFragmentByTag("editor");
        if (editor instanceof EditorFragment) {
            ((EditorFragment) editor).refresh();
        }
    }

    private String buildPathLabel() {
        return "/" + String.join("/", pathStack);
    }

    private void saveTreeUri(Uri uri) {
        requireContext().getSharedPreferences(PREFS, 0)
                .edit().putString(KEY_TREE_URI, uri.toString()).apply();
    }

    private void restoreLastFolder() {
        String saved = requireContext().getSharedPreferences(PREFS, 0).getString(KEY_TREE_URI, null);
        if (saved == null) return;

        Uri uri = Uri.parse(saved);
        boolean stillValid = false;
        for (android.content.UriPermission perm : requireContext().getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(uri)) {
                stillValid = true;
                break;
            }
        }
        if (stillValid) handleFolderSelected(uri);
    }
}
