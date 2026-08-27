package genius.DMTech.Vectr;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class EditorFragment extends Fragment {

    private LinearLayout tabContainer;
    private HorizontalScrollView tabBar;
    private TextView filenameLabel;
    private WebView editorWebView;
    private ImageButton btnCloseDiff;
    private LinearLayout diagPanel;
    private TextView diagTitle, diagText;
    private TextView btnFixSelection, btnFixDiagnostics;
    private boolean pageReady = false;
    private String pendingContent, pendingMode;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private List<EditorContext.Diagnostic> lastDiagnostics = new ArrayList<>();

    // список вкладок для синхронизации с UI
    private final List<String> renderedTabUris = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tabContainer = view.findViewById(R.id.tab_container);
        tabBar = view.findViewById(R.id.tab_bar);
        filenameLabel = view.findViewById(R.id.editor_filename);
        editorWebView = view.findViewById(R.id.editor_webview);
        btnCloseDiff = view.findViewById(R.id.btn_close_diff);
        diagPanel = view.findViewById(R.id.editor_diag_panel);
        diagTitle = view.findViewById(R.id.editor_diag_title);
        diagText = view.findViewById(R.id.editor_diag_text);
        btnFixSelection = view.findViewById(R.id.btn_fix_selection);
        btnFixDiagnostics = view.findViewById(R.id.btn_fix_diagnostics);

        editorWebView.getSettings().setJavaScriptEnabled(true);
        editorWebView.getSettings().setAllowFileAccess(true);
        editorWebView.getSettings().setDomStorageEnabled(false);
        // локальные asset-скрипты; не даём file→http утечки
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            editorWebView.getSettings().setAllowFileAccessFromFileURLs(false);
            editorWebView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        }
        editorWebView.addJavascriptInterface(new EditorBridge(new EditorBridge.Callbacks() {
            @Override
            public void onContent(String content) {
                onContentFromJs(content);
            }

            @Override
            public void onEditorState(String json) {
                onEditorStateFromJs(json);
            }
        }), "Android");

        editorWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // никакой навигации из редактора (только asset HTML)
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                    android.webkit.WebResourceRequest request) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingContent != null) {
                    injectContent(pendingContent, pendingMode);
                    pendingContent = null;
                }
            }
        });

        // base = android_asset — скрипты из assets/codemirror/ (offline, без CDN)
        editorWebView.loadDataWithBaseURL(
                "file:///android_asset/", buildHtml(), "text/html", "utf-8", null
        );

        btnCloseDiff.setOnClickListener(v -> {
            editorWebView.evaluateJavascript("backToEditor();", null);
            btnCloseDiff.setVisibility(View.GONE);
            renderTabs();
        });

        btnFixSelection.setOnClickListener(v -> requestFixSelection());
        btnFixDiagnostics.setOnClickListener(v -> requestFixDiagnostics());

        setupSymbolBar(view);

        renderTabs();
        loadCurrentTab();
    }

    private void setupSymbolBar(View view) {
        View btnUndo = view.findViewById(R.id.btn_editor_undo);
        View btnRedo = view.findViewById(R.id.btn_editor_redo);
        View btnFind = view.findViewById(R.id.btn_editor_find);
        if (btnUndo != null) btnUndo.setOnClickListener(v -> evalJs("editorUndo();"));
        if (btnRedo != null) btnRedo.setOnClickListener(v -> evalJs("editorRedo();"));
        if (btnFind != null) btnFind.setOnClickListener(v -> evalJs("openFind();"));

        bindSym(view, R.id.btn_sym_tab, null, true);
        bindSym(view, R.id.btn_sym_brace_l, "{", false);
        bindSym(view, R.id.btn_sym_brace_r, "}", false);
        bindSym(view, R.id.btn_sym_paren_l, "(", false);
        bindSym(view, R.id.btn_sym_paren_r, ")", false);
        bindSym(view, R.id.btn_sym_brack_l, "[", false);
        bindSym(view, R.id.btn_sym_brack_r, "]", false);
        bindSym(view, R.id.btn_sym_lt, "<", false);
        bindSym(view, R.id.btn_sym_gt, ">", false);
        bindSym(view, R.id.btn_sym_semi, ";", false);
        bindSym(view, R.id.btn_sym_eq, "=", false);
        bindSym(view, R.id.btn_sym_dq, "\"", false);
        bindSym(view, R.id.btn_sym_sq, "'", false);
        bindSym(view, R.id.btn_sym_slash, "/", false);
        bindSym(view, R.id.btn_sym_bslash, "\\", false);
        bindSym(view, R.id.btn_sym_us, "_", false);
        bindSym(view, R.id.btn_sym_arrow, "->", false);
    }

    private void bindSym(View root, int id, String text, boolean tab) {
        View v = root.findViewById(id);
        if (v == null) return;
        v.setOnClickListener(clicked -> {
            if (tab) evalJs("insertTab();");
            else evalJs("insertText(" + JSONObject.quote(text) + ");");
        });
    }

    private void evalJs(String js) {
        if (!pageReady || editorWebView == null) return;
        editorWebView.evaluateJavascript(js, null);
    }

    private void refreshEditor() {
        evalJs("editorRefresh();");
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            mainHandler.postDelayed(this::refreshEditor, 80);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mainHandler.postDelayed(this::refreshEditor, 80);
    }

    @Override
    public void onDestroyView() {
        pageReady = false;
        pendingContent = null;
        pendingMode = null;
        super.onDestroyView();
        if (editorWebView != null) {
            ViewGroup parent = (ViewGroup) editorWebView.getParent();
            if (parent != null) parent.removeView(editorWebView);
            editorWebView.removeAllViews();
            editorWebView.destroy();
            editorWebView = null;
        }
    }

    // ========== Публичные методы ==========

    public void refresh() {
        loadCurrentTab();
    }

    /** Открыть файл — если уже открыт, переключиться, если нет — добавить таб */
    public void openFile(Uri uri, String name) {
        openFile(uri, name, name);
    }

    public void openFile(Uri uri, String name, String relativePath) {
        ProjectState state = ProjectState.getInstance();

        // ищем существующий таб
        int existing = state.findTabByUri(uri);
        if (existing >= 0) {
            state.currentTabIndex = existing;
            state.currentFileUri = uri;
            state.currentFileName = name;
            OpenFileTab existingTab = state.openTabs.get(existing);
            if (relativePath != null && !relativePath.isEmpty()) {
                existingTab.relativePath = relativePath;
            }
            renderTabs();
            scrollTabToEnd();
            loadCurrentTab();
            return;
        }

        // новый таб
        String mode = detectMode(name);
        OpenFileTab tab = new OpenFileTab(uri, name, mode);
        tab.relativePath = relativePath != null ? relativePath : name;
        state.openTabs.add(tab);
        state.currentTabIndex = state.openTabs.size() - 1;
        state.currentFileUri = uri;
        state.currentFileName = name;

        filenameLabel.setVisibility(View.GONE);
        // один load — не дергаем loadCurrentTab (иначе второй reader на пустой content)
        loadFileContent(tab);
        renderTabs();
        scrollTabToEnd();
    }

    /** Синхронизировать курсор/выделение из WebView перед уходом к агенту. */
    public void syncEditorStateNow() {
        if (!pageReady || editorWebView == null) return;
        editorWebView.evaluateJavascript("pushEditorState();", null);
    }

    public void goToLine(int line1Based) {
        if (!pageReady || editorWebView == null) return;
        editorWebView.evaluateJavascript("goToLine(" + Math.max(1, line1Based) + ");", null);
    }

    /** Закрыть вкладку */
    public void closeTab(int index) {
        ProjectState state = ProjectState.getInstance();
        if (index < 0 || index >= state.openTabs.size()) return;

        OpenFileTab tab = state.openTabs.get(index);
        if (tab.isDirty) {
            String name = tab.name != null ? tab.name : "file";
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.editor_unsaved_title)
                    .setMessage(getString(R.string.editor_unsaved_message, name))
                    .setPositiveButton(R.string.action_save, (d, w) ->
                            saveTabThenClose(tab, index))
                    .setNeutralButton(R.string.editor_discard, (d, w) ->
                            finishCloseTab(index))
                    .setNegativeButton(R.string.files_cancel, null)
                    .show();
            return;
        }
        finishCloseTab(index);
    }

    private void saveTabThenClose(OpenFileTab tab, int index) {
        ProjectState state = ProjectState.getInstance();
        if (index < 0 || index >= state.openTabs.size()) return;

        Runnable afterSave = () -> finishCloseTab(index);

        // активная вкладка — забрать свежий текст из CodeMirror
        if (index == state.currentTabIndex && pageReady && editorWebView != null) {
            editorWebView.evaluateJavascript(
                    "(function(){ try { return cmEditor.getValue(); } catch(e) { return null; } })()",
                    value -> {
                        String c = decodeJsString(value);
                        if (c != null) {
                            tab.content = c;
                            tab.contentLoaded = true;
                        }
                        writeTabToDisk(tab, afterSave);
                    });
        } else {
            writeTabToDisk(tab, afterSave);
        }
    }

    private void writeTabToDisk(OpenFileTab tab, Runnable onDoneUi) {
        Context ctx = getContext();
        if (ctx == null || tab.uri == null) {
            if (onDoneUi != null) onDoneUi.run();
            return;
        }
        final android.content.ContentResolver resolver = ctx.getContentResolver();
        final String content = tab.content != null ? tab.content : "";
        final Uri uri = tab.uri;
        new Thread(() -> {
            try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
                if (out == null) throw new Exception("не удалось открыть поток записи");
                out.write(content.getBytes(StandardCharsets.UTF_8));
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        tab.isDirty = false;
                        if (isAdded()) renderTabs();
                        if (onDoneUi != null) onDoneUi.run();
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(),
                                "Не сохранил: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        }).start();
    }

    private void finishCloseTab(int index) {
        ProjectState state = ProjectState.getInstance();
        if (index < 0 || index >= state.openTabs.size()) return;

        state.closeTab(index);
        renderTabs();

        if (state.openTabs.isEmpty()) {
            filenameLabel.setVisibility(View.VISIBLE);
            filenameLabel.setText(R.string.editor_no_file);
            clearEditorContent();
        } else {
            loadCurrentTab();
        }
    }

    public void saveCurrentFile() {
        if (!pageReady) return;
        editorWebView.evaluateJavascript("Android.receiveContent(cmEditor.getValue());", null);
    }

    public void showDiff(String oldContent, String newContent, String filename) {
        if (!pageReady) return;

        new Thread(() -> {
            DiffUtil.DiffResult diff = DiffUtil.diffLines(oldContent, newContent);

            JSONArray opsArray = new JSONArray();
            try {
                for (DiffUtil.DiffOp op : diff.ops) {
                    JSONObject opObj = new JSONObject();
                    opObj.put("type", op.type.name().toLowerCase());
                    opObj.put("text", op.text);
                    opsArray.put(opObj);
                }
            } catch (Exception ignored) {}

            String opsJson = opsArray.toString();
            String escapedOpsJson = JSONObject.quote(opsJson);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    editorWebView.evaluateJavascript("showDiffOps(" + escapedOpsJson + ");", null);
                    btnCloseDiff.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    // ========== Внутренние методы ==========

    private void loadCurrentTab() {
        ProjectState state = ProjectState.getInstance();
        OpenFileTab tab = state.getCurrentTab();
        if (tab == null) {
            filenameLabel.setVisibility(View.VISIBLE);
            filenameLabel.setText(R.string.editor_no_file);
            return;
        }

        filenameLabel.setVisibility(View.GONE);

        if (!tab.contentLoaded) {
            loadFileContent(tab);
        } else {
            if (pageReady) {
                injectContent(tab.content, tab.mode);
            } else {
                pendingContent = tab.content;
                pendingMode = tab.mode;
            }
        }
    }

    private void loadFileContent(OpenFileTab tab) {
        Context ctx = getContext();
        if (ctx == null) return;
        final android.content.ContentResolver resolver = ctx.getContentResolver();
        new Thread(() -> {
            String loaded;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resolver.openInputStream(tab.uri), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                loaded = sb.toString();
            } catch (Exception e) {
                loaded = "// Ошибка чтения файла: " + e.getMessage();
            }
            final String content = loaded;
            tab.content = content;
            tab.contentLoaded = true;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded() || editorWebView == null) return;
                    ProjectState state = ProjectState.getInstance();
                    if (state.getCurrentTab() == tab && pageReady) {
                        injectContent(tab.content, tab.mode);
                    } else if (state.getCurrentTab() == tab) {
                        pendingContent = tab.content;
                        pendingMode = tab.mode;
                    }
                    renderTabs();
                });
            }
        }).start();
    }

    private void injectContent(String content, String mode) {
        String escaped = JSONObject.quote(content != null ? content : "");
        editorWebView.evaluateJavascript("setContent(" + escaped + ", '" + mode + "');", null);
        refreshDiagnostics(content);
    }

    private void clearEditorContent() {
        editorWebView.evaluateJavascript("setContent('', 'text');", null);
        if (diagPanel != null) diagPanel.setVisibility(View.GONE);
        lastDiagnostics.clear();
    }

    private void onContentFromJs(String content) {
        ProjectState state = ProjectState.getInstance();
        OpenFileTab tab = state.getCurrentTab();
        if (tab == null) return;

        tab.content = content;
        tab.contentLoaded = true;
        tab.isDirty = true;
        refreshDiagnostics(content);

        Context ctx = getContext();
        if (ctx == null) return;
        final android.content.ContentResolver resolver = ctx.getContentResolver();
        final Uri uri = tab.uri;
        new Thread(() -> {
            try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
                if (out == null) throw new Exception("не удалось открыть поток записи");
                out.write(content.getBytes(StandardCharsets.UTF_8));
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        tab.isDirty = false;
                        renderTabs();
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(),
                                "Не сохранил: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        }).start();
    }

    private void onEditorStateFromJs(String json) {
        mainHandler.post(() -> {
            try {
                JSONObject o = new JSONObject(json);
                OpenFileTab tab = ProjectState.getInstance().getCurrentTab();
                if (tab == null) return;
                tab.cursorLine = o.optInt("line", 1);
                tab.cursorCh = o.optInt("ch", 0);
                tab.selection = o.optString("selection", "");
                if (o.has("content")) {
                    String c = o.optString("content", null);
                    if (c != null) {
                        if (!c.equals(tab.content)) {
                            tab.isDirty = true;
                        }
                        tab.content = c;
                        tab.contentLoaded = true;
                        refreshDiagnostics(c);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void refreshDiagnostics(String content) {
        OpenFileTab tab = ProjectState.getInstance().getCurrentTab();
        String name = tab != null ? tab.name : "";
        lastDiagnostics = EditorContext.analyze(content, name);
        if (diagPanel == null) return;
        if (lastDiagnostics.isEmpty()) {
            diagPanel.setVisibility(View.GONE);
            return;
        }
        int errors = 0, warns = 0;
        StringBuilder sb = new StringBuilder();
        int show = Math.min(lastDiagnostics.size(), 4);
        for (int i = 0; i < lastDiagnostics.size(); i++) {
            EditorContext.Diagnostic d = lastDiagnostics.get(i);
            if ("error".equals(d.severity)) errors++;
            else warns++;
            if (i < show) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("L").append(d.line).append(" · ").append(d.message);
            }
        }
        if (lastDiagnostics.size() > show) {
            sb.append("\n…ещё ").append(lastDiagnostics.size() - show);
        }
        diagTitle.setText(getString(R.string.editor_diagnostics) + " · " + errors + " err · " + warns + " warn");
        diagText.setText(sb.toString());
        diagPanel.setVisibility(View.VISIBLE);
        diagPanel.setOnClickListener(v -> {
            if (!lastDiagnostics.isEmpty()) goToLine(lastDiagnostics.get(0).line);
        });
    }

    private void requestFixSelection() {
        syncEditorStateNow();
        mainHandler.postDelayed(() -> {
            OpenFileTab tab = ProjectState.getInstance().getCurrentTab();
            if (tab == null) {
                Toast.makeText(requireContext(), "Нет открытого файла", Toast.LENGTH_SHORT).show();
                return;
            }
            String path = tab.relativePath != null ? tab.relativePath : tab.name;
            String sel = tab.selection;
            if (sel == null || sel.trim().isEmpty()) {
                Toast.makeText(requireContext(), "Сначала выдели код", Toast.LENGTH_SHORT).show();
                return;
            }
            String prompt = "Исправь выделенный код в файле `" + path + "` (курсор ~L"
                    + tab.cursorLine + "). Используй search_replace.\n\n```\n"
                    + sel + "\n```";
            sendPromptToAgent(prompt);
        }, 120);
    }

    private void requestFixDiagnostics() {
        syncEditorStateNow();
        mainHandler.postDelayed(() -> {
            OpenFileTab tab = ProjectState.getInstance().getCurrentTab();
            if (tab == null) {
                Toast.makeText(requireContext(), "Нет открытого файла", Toast.LENGTH_SHORT).show();
                return;
            }
            if (lastDiagnostics.isEmpty() && tab.content != null) {
                lastDiagnostics = EditorContext.analyze(tab.content, tab.name);
            }
            if (lastDiagnostics.isEmpty()) {
                Toast.makeText(requireContext(), "Проблем не найдено", Toast.LENGTH_SHORT).show();
                return;
            }
            String path = tab.relativePath != null ? tab.relativePath : tab.name;
            StringBuilder sb = new StringBuilder();
            sb.append("Исправь проблемы в файле `").append(path).append("`. Используй search_replace / apply_patch.\n\n");
            sb.append("Диагностика:\n");
            int n = Math.min(lastDiagnostics.size(), 15);
            for (int i = 0; i < n; i++) {
                EditorContext.Diagnostic d = lastDiagnostics.get(i);
                sb.append("- L").append(d.line).append(" [").append(d.severity).append("] ")
                        .append(d.message).append("\n");
            }
            sb.append("\nФрагмент около первой проблемы:\n```\n");
            sb.append(EditorContext.extractNearby(tab.content, lastDiagnostics.get(0).line, 12));
            sb.append("\n```");
            sendPromptToAgent(sb.toString());
        }, 120);
    }

    private void sendPromptToAgent(String prompt) {
        if (getActivity() instanceof HomeActivity) {
            ((HomeActivity) getActivity()).sendToAgent(prompt);
        }
    }

    // ========== Табы ==========

    private void renderTabs() {
        ProjectState state = ProjectState.getInstance();
        tabContainer.removeAllViews();
        renderedTabUris.clear();

        if (state.openTabs.isEmpty()) {
            tabBar.setVisibility(View.GONE);
            return;
        }

        tabBar.setVisibility(View.VISIBLE);
        int dp4 = dp(4);
        int dp8 = dp(8);

        for (int i = 0; i < state.openTabs.size(); i++) {
            OpenFileTab tab = state.openTabs.get(i);
            boolean isActive = (i == state.currentTabIndex);

            LinearLayout tabItem = new LinearLayout(requireContext());
            tabItem.setOrientation(LinearLayout.HORIZONTAL);
            tabItem.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tabItem.setPadding(dp8, 0, dp4, 0);

            // фон
            if (isActive) {
                tabItem.setBackgroundColor(0xFF171A21);
            } else {
                tabItem.setBackgroundColor(0xFF12141A);
            }

            // разделитель справа
            tabItem.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));

            // имя файла
            TextView nameView = new TextView(requireContext());
            nameView.setText(tab.name);
            nameView.setTextSize(12f);
            nameView.setTextColor(isActive
                    ? requireContext().getColor(R.color.text_primary)
                    : requireContext().getColor(R.color.text_secondary));
            nameView.setPadding(dp4, dp8, dp4, dp8);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            nameView.setMaxWidth(dp(180));

            if (tab.isDirty) {
                nameView.setText("● " + tab.name);
                nameView.setTextColor(requireContext().getColor(R.color.accent));
            }

            tabItem.addView(nameView);

            ImageView closeBtn = new ImageView(requireContext());
            int closeSize = dp(16);
            LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(closeSize, closeSize);
            closeParams.setMarginStart(dp4);
            closeBtn.setLayoutParams(closeParams);
            closeBtn.setImageResource(R.drawable.ic_close);
            closeBtn.setColorFilter(requireContext().getColor(R.color.text_tertiary));
            closeBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            closeBtn.setPadding(dp(3), dp(3), dp(3), dp(3));
            closeBtn.setBackgroundResource(android.R.color.transparent);
            closeBtn.setClickable(true);

            final int tabIndex = i;
            closeBtn.setOnClickListener(v -> closeTab(tabIndex));
            tabItem.addView(closeBtn);

            tabItem.setOnClickListener(v -> switchToTab(tabIndex));

            tabContainer.addView(tabItem);
            renderedTabUris.add(tab.uri.toString());
        }
    }

    private void switchToTab(int index) {
        ProjectState state = ProjectState.getInstance();
        if (index < 0 || index >= state.openTabs.size()) return;
        if (index == state.currentTabIndex) return;

        final OpenFileTab prev = state.getCurrentTab();
        // перед сменой — забрать актуальный текст из CodeMirror (не async receiveContent)
        if (pageReady && editorWebView != null && prev != null) {
            final int target = index;
            editorWebView.evaluateJavascript(
                    "(function(){ try { return cmEditor.getValue(); } catch(e) { return null; } })()",
                    value -> {
                        String c = decodeJsString(value);
                        if (c != null) {
                            prev.content = c;
                            prev.contentLoaded = true;
                        }
                        applyTabSwitch(target);
                    });
        } else {
            applyTabSwitch(index);
        }
    }

    private void applyTabSwitch(int index) {
        ProjectState state = ProjectState.getInstance();
        if (index < 0 || index >= state.openTabs.size()) return;
        state.currentTabIndex = index;
        state.currentFileUri = state.openTabs.get(index).uri;
        state.currentFileName = state.openTabs.get(index).name;
        renderTabs();
        scrollTabToEnd();
        loadCurrentTab();
    }

    /** evaluateJavascript отдаёт JSON-строку или null. */
    private static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            return new org.json.JSONArray("[" + value + "]").getString(0);
        } catch (Exception e) {
            return value;
        }
    }

    /** Сохранить без уведомления юзера (при переключении вкладок) */
    private void saveCurrentFileSilent() {
        if (!pageReady || editorWebView == null) return;
        ProjectState state = ProjectState.getInstance();
        OpenFileTab tab = state.getCurrentTab();
        if (tab == null || !tab.isDirty) return;

        editorWebView.evaluateJavascript("Android.receiveContent(cmEditor.getValue());", null);
    }

    private void scrollTabToEnd() {
        tabBar.post(() -> tabBar.fullScroll(View.FOCUS_RIGHT));
    }

    // ========== Хелперы ==========

    private int dp(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density);
    }

    private String detectMode(String filename) {
        if (filename == null) return "text";
        String lower = filename.toLowerCase();

        if (lower.endsWith(".java")) return "text/x-java";
        if (lower.endsWith(".c") || lower.endsWith(".h") || lower.endsWith(".cpp")
                || lower.endsWith(".hpp") || lower.endsWith(".ino")) return "text/x-c++src";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".html")) return "htmlmixed";
        return "text";
    }

    private String buildHtml() {
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        String bg, surface, stroke, text, tertiary, accent, keyword, def, variable2,
                stringC, number, property, operator, tag, attribute, sel, addBg, delBg, addFg, delFg, raised;
        if (night) {
            bg = "#0B0C10"; surface = "#12141A"; raised = "#1C1F28"; stroke = "#2A2E38"; text = "#E8EAED";
            tertiary = "#6B7380"; accent = "#7EB8FF"; keyword = "#C792EA"; def = "#82AAFF";
            variable2 = "#9CDCFE"; stringC = "#C3E88D"; number = "#F78C6C"; property = "#9CDCFE";
            operator = "#89DDFF"; tag = "#F07178"; attribute = "#FFCB6B"; sel = "#7EB8FF33";
            addBg = "#12261c"; delBg = "#2a1418"; addFg = "#7FD99A"; delFg = "#F07178";
        } else {
            bg = "#F4F6F9"; surface = "#FFFFFF"; raised = "#FFFFFF"; stroke = "#D8DEE6"; text = "#12141A";
            tertiary = "#8B93A1"; accent = "#2563EB"; keyword = "#7C3AED"; def = "#1D4ED8";
            variable2 = "#0369A1"; stringC = "#15803D"; number = "#C2410C"; property = "#0369A1";
            operator = "#0284C7"; tag = "#DC2626"; attribute = "#B45309"; sel = "#2563EB33";
            addBg = "#DCFCE7"; delBg = "#FEE2E2"; addFg = "#15803D"; delFg = "#DC2626";
        }

        String lblFind = escHtml(getString(R.string.editor_find_hint));
        String lblReplace = escHtml(getString(R.string.editor_replace_hint));
        String lblNext = escHtml(getString(R.string.editor_find_next));
        String lblPrev = escHtml(getString(R.string.editor_find_prev));
        String lblRep = escHtml(getString(R.string.editor_replace_one));
        String lblAll = escHtml(getString(R.string.editor_replace_all));

        return "<!DOCTYPE html><html><head>" +
                "<meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>" +
                "<link rel='stylesheet' href='codemirror/codemirror.min.css'>" +
                "<script src='codemirror/codemirror.min.js'></script>" +
                "<script src='codemirror/mode/clike/clike.min.js'></script>" +
                "<script src='codemirror/mode/javascript/javascript.min.js'></script>" +
                "<script src='codemirror/mode/xml/xml.min.js'></script>" +
                "<script src='codemirror/mode/python/python.min.js'></script>" +
                "<script src='codemirror/mode/css/css.min.js'></script>" +
                "<script src='codemirror/mode/htmlmixed/htmlmixed.min.js'></script>" +
                "<script src='codemirror/addon/edit/matchbrackets.min.js'></script>" +
                "<script src='codemirror/addon/edit/closebrackets.min.js'></script>" +
                "<script src='codemirror/addon/selection/active-line.min.js'></script>" +
                "<script src='codemirror/addon/search/searchcursor.min.js'></script>" +
                "<style>" +
                "html, body { margin: 0; padding: 0; height: 100%; background: " + bg + "; overflow: hidden; }" +
                "#wrap { position: relative; height: 100%; }" +
                "#editorContainer { height: 100%; }" +
                ".CodeMirror { height: 100%; font-size: 14px; font-family: ui-monospace, monospace; background: " + bg + " !important; color: " + text + " !important; }" +
                ".cm-s-vectr .CodeMirror-gutters { background: " + surface + " !important; border-right: 1px solid " + stroke + "; }" +
                ".cm-s-vectr .CodeMirror-linenumber { color: " + tertiary + " !important; }" +
                ".cm-s-vectr .CodeMirror-cursor { border-left: 2px solid " + accent + " !important; }" +
                ".cm-s-vectr .CodeMirror-selected { background: " + sel + " !important; }" +
                ".cm-s-vectr .CodeMirror-activeline-background { background: " + surface + " !important; }" +
                ".cm-s-vectr .cm-keyword { color: " + keyword + " !important; font-weight: bold; }" +
                ".cm-s-vectr .cm-def { color: " + def + " !important; }" +
                ".cm-s-vectr .cm-variable { color: " + text + " !important; }" +
                ".cm-s-vectr .cm-variable-2 { color: " + variable2 + " !important; }" +
                ".cm-s-vectr .cm-string { color: " + stringC + " !important; }" +
                ".cm-s-vectr .cm-number { color: " + number + " !important; }" +
                ".cm-s-vectr .cm-comment { color: " + tertiary + " !important; font-style: italic; }" +
                ".cm-s-vectr .cm-property { color: " + property + " !important; }" +
                ".cm-s-vectr .cm-operator { color: " + operator + " !important; }" +
                ".cm-s-vectr .cm-tag { color: " + tag + " !important; }" +
                ".cm-s-vectr .cm-attribute { color: " + attribute + " !important; }" +
                ".cm-s-vectr .cm-meta { color: " + tertiary + " !important; }" +
                ".cm-s-vectr .cm-punctuation { color: " + text + " !important; }" +
                ".cm-s-vectr .CodeMirror-matchingbracket { color: " + accent + " !important; background: " + sel + "; }" +
                ".cm-diag-error { background: #F0717822; border-bottom: 2px wavy #F07178; }" +
                ".cm-diag-warn { background: #E6B45022; border-bottom: 2px dotted #E6B450; }" +
                "#diffContainer { display: none; height: 100%; overflow: auto; font-family: monospace; font-size: 13px; background:" + bg + "; }" +
                "#findBar { display: none; position: absolute; top: 0; left: 0; right: 0; z-index: 20;" +
                " background: " + raised + "; border-bottom: 1px solid " + stroke + ";" +
                " padding: 8px 10px; box-sizing: border-box; }" +
                "#findBar .row { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; }" +
                "#findBar .row:last-child { margin-bottom: 0; }" +
                "#findBar input { flex: 1; min-width: 0; background: " + bg + "; color: " + text + ";" +
                " border: 1px solid " + stroke + "; border-radius: 8px; padding: 8px 10px; font-size: 14px; outline: none; }" +
                "#findBar input:focus { border-color: " + accent + "; }" +
                "#findBar button { background: " + surface + "; color: " + text + "; border: 1px solid " + stroke + ";" +
                " border-radius: 8px; padding: 7px 10px; font-size: 12px; white-space: nowrap; }" +
                "#findBar button.accent { color: " + accent + "; border-color: " + accent + "; }" +
                "#findBar #findStatus { color: " + tertiary + "; font-size: 11px; min-width: 48px; text-align: right; }" +
                "</style>" +
                "</head><body>" +
                "<div id='wrap'>" +
                "<div id='findBar'>" +
                "  <div class='row'>" +
                "    <input id='findInput' type='search' placeholder='" + lblFind + "' autocomplete='off' />" +
                "    <span id='findStatus'></span>" +
                "    <button type='button' onclick='findPrev()'>" + lblPrev + "</button>" +
                "    <button type='button' class='accent' onclick='findNext()'>" + lblNext + "</button>" +
                "    <button type='button' onclick='closeFind()'>✕</button>" +
                "  </div>" +
                "  <div class='row'>" +
                "    <input id='replaceInput' type='search' placeholder='" + lblReplace + "' autocomplete='off' />" +
                "    <button type='button' onclick='replaceOne()'>" + lblRep + "</button>" +
                "    <button type='button' onclick='replaceAll()'>" + lblAll + "</button>" +
                "  </div>" +
                "</div>" +
                "<div id='editorContainer'><textarea id='raw'></textarea></div>" +
                "<div id='diffContainer'></div>" +
                "</div>" +
                "<script>" +
                "var cmEditor = CodeMirror.fromTextArea(document.getElementById('raw'), {" +
                "  lineNumbers: true," +
                "  theme: 'vectr'," +
                "  matchBrackets: true," +
                "  autoCloseBrackets: true," +
                "  styleActiveLine: true," +
                "  indentUnit: 4," +
                "  tabSize: 4," +
                "  indentWithTabs: false," +
                "  mode: 'text'," +
                "  extraKeys: {" +
                "    'Tab': function(cm) {" +
                "      if (cm.somethingSelected()) cm.indentSelection('add');" +
                "      else cm.replaceSelection(Array((cm.getOption('indentUnit')||4)+1).join(' '), 'end');" +
                "    }," +
                "    'Shift-Tab': function(cm) { cm.indentSelection('subtract'); }," +
                "    'Ctrl-F': function(cm) { openFind(); }," +
                "    'Cmd-F': function(cm) { openFind(); }," +
                "    'Ctrl-H': function(cm) { openFind(true); }," +
                "    'Cmd-Alt-F': function(cm) { openFind(true); }," +
                "    'Esc': function(cm) { closeFind(); }" +
                "  }" +
                "});" +
                "var stateTimer = null;" +
                "var findCursor = null;" +
                "var lastQuery = '';" +
                "function pushEditorState() {" +
                "  try {" +
                "    var cur = cmEditor.getCursor();" +
                "    var sel = cmEditor.getSelection() || '';" +
                "    var payload = JSON.stringify({" +
                "      line: cur.line + 1," +
                "      ch: cur.ch," +
                "      selection: sel.length > 8000 ? sel.substring(0,8000) : sel," +
                "      content: cmEditor.getValue()" +
                "    });" +
                "    Android.onEditorState(payload);" +
                "  } catch(e) {}" +
                "}" +
                "function scheduleState() {" +
                "  if (stateTimer) clearTimeout(stateTimer);" +
                "  stateTimer = setTimeout(pushEditorState, 180);" +
                "}" +
                "cmEditor.on('cursorActivity', scheduleState);" +
                "cmEditor.on('changes', scheduleState);" +
                "function setContent(text, mode) {" +
                "  cmEditor.setValue(text || '');" +
                "  cmEditor.setOption('mode', mode || 'text');" +
                "  findCursor = null;" +
                "  scheduleState();" +
                "  setTimeout(function(){ cmEditor.refresh(); }, 40);" +
                "}" +
                "function goToLine(line1) {" +
                "  var l = Math.max(0, (line1||1) - 1);" +
                "  cmEditor.setCursor({line:l, ch:0});" +
                "  cmEditor.scrollIntoView({line:l, ch:0}, 80);" +
                "  cmEditor.focus();" +
                "}" +
                "function insertText(ch) {" +
                "  cmEditor.replaceSelection(ch == null ? '' : String(ch), 'end');" +
                "  cmEditor.focus();" +
                "  scheduleState();" +
                "}" +
                "function insertTab() {" +
                "  if (cmEditor.somethingSelected()) cmEditor.indentSelection('add');" +
                "  else {" +
                "    var n = cmEditor.getOption('indentUnit') || 4;" +
                "    cmEditor.replaceSelection(Array(n+1).join(' '), 'end');" +
                "  }" +
                "  cmEditor.focus();" +
                "  scheduleState();" +
                "}" +
                "function editorUndo() { cmEditor.undo(); cmEditor.focus(); scheduleState(); }" +
                "function editorRedo() { cmEditor.redo(); cmEditor.focus(); scheduleState(); }" +
                "function editorRefresh() { try { cmEditor.refresh(); } catch(e) {} }" +
                "function openFind(focusReplace) {" +
                "  var bar = document.getElementById('findBar');" +
                "  bar.style.display = 'block';" +
                "  var h = bar.offsetHeight;" +
                "  var ed = document.getElementById('editorContainer');" +
                "  ed.style.height = 'calc(100% - ' + h + 'px)';" +
                "  ed.style.marginTop = h + 'px';" +
                "  cmEditor.refresh();" +
                "  var fi = document.getElementById('findInput');" +
                "  var sel = cmEditor.getSelection();" +
                "  if (sel && sel.indexOf('\\n') < 0 && sel.length < 200) fi.value = sel;" +
                "  if (focusReplace) document.getElementById('replaceInput').focus();" +
                "  else { fi.focus(); fi.select(); }" +
                "}" +
                "function closeFind() {" +
                "  document.getElementById('findBar').style.display = 'none';" +
                "  var ed = document.getElementById('editorContainer');" +
                "  ed.style.height = '100%';" +
                "  ed.style.marginTop = '0';" +
                "  document.getElementById('findStatus').textContent = '';" +
                "  findCursor = null;" +
                "  cmEditor.refresh();" +
                "  cmEditor.focus();" +
                "}" +
                "function ensureCursor(rev) {" +
                "  var q = document.getElementById('findInput').value;" +
                "  if (!q) { document.getElementById('findStatus').textContent = ''; return null; }" +
                "  if (!findCursor || q !== lastQuery) {" +
                "    lastQuery = q;" +
                "    var from = cmEditor.getCursor(rev ? 'from' : 'to');" +
                "    findCursor = cmEditor.getSearchCursor(q, from, {caseFold: true});" +
                "  }" +
                "  return findCursor;" +
                "}" +
                "function findNext() {" +
                "  var c = ensureCursor(false);" +
                "  if (!c) return;" +
                "  if (!c.findNext()) {" +
                "    findCursor = cmEditor.getSearchCursor(lastQuery, {line:0,ch:0}, {caseFold: true});" +
                "    if (!findCursor.findNext()) {" +
                "      document.getElementById('findStatus').textContent = '0';" +
                "      return;" +
                "    }" +
                "    c = findCursor;" +
                "  }" +
                "  cmEditor.setSelection(c.from(), c.to());" +
                "  cmEditor.scrollIntoView({from:c.from(), to:c.to()}, 60);" +
                "  document.getElementById('findStatus').textContent = '✓';" +
                "}" +
                "function findPrev() {" +
                "  var q = document.getElementById('findInput').value;" +
                "  if (!q) return;" +
                "  lastQuery = q;" +
                "  findCursor = cmEditor.getSearchCursor(q, cmEditor.getCursor('from'), {caseFold: true});" +
                "  if (!findCursor.findPrevious()) {" +
                "    var last = cmEditor.lastLine();" +
                "    findCursor = cmEditor.getSearchCursor(q, {line:last, ch: cmEditor.getLine(last).length}, {caseFold: true});" +
                "    if (!findCursor.findPrevious()) {" +
                "      document.getElementById('findStatus').textContent = '0';" +
                "      return;" +
                "    }" +
                "  }" +
                "  cmEditor.setSelection(findCursor.from(), findCursor.to());" +
                "  cmEditor.scrollIntoView({from:findCursor.from(), to:findCursor.to()}, 60);" +
                "  document.getElementById('findStatus').textContent = '✓';" +
                "}" +
                "function replaceOne() {" +
                "  var q = document.getElementById('findInput').value;" +
                "  var r = document.getElementById('replaceInput').value;" +
                "  if (!q) return;" +
                "  if (cmEditor.getSelection().toLowerCase() === q.toLowerCase()) {" +
                "    cmEditor.replaceSelection(r, 'around');" +
                "    scheduleState();" +
                "  }" +
                "  findCursor = null;" +
                "  findNext();" +
                "}" +
                "function replaceAll() {" +
                "  var q = document.getElementById('findInput').value;" +
                "  var r = document.getElementById('replaceInput').value;" +
                "  if (!q) return;" +
                "  var cur = cmEditor.getSearchCursor(q, {line:0,ch:0}, {caseFold: true});" +
                "  var n = 0;" +
                "  cmEditor.operation(function() {" +
                "    while (cur.findNext()) { cur.replace(r); n++; }" +
                "  });" +
                "  document.getElementById('findStatus').textContent = String(n);" +
                "  findCursor = null;" +
                "  scheduleState();" +
                "}" +
                "document.getElementById('findInput').addEventListener('keydown', function(e) {" +
                "  if (e.key === 'Enter') { e.preventDefault(); if (e.shiftKey) findPrev(); else findNext(); }" +
                "  if (e.key === 'Escape') { e.preventDefault(); closeFind(); }" +
                "});" +
                "document.getElementById('replaceInput').addEventListener('keydown', function(e) {" +
                "  if (e.key === 'Enter') { e.preventDefault(); replaceOne(); }" +
                "  if (e.key === 'Escape') { e.preventDefault(); closeFind(); }" +
                "});" +
                "function escapeHtml(s) {" +
                "  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');" +
                "}" +
                "function showDiffOps(opsJson) {" +
                "  closeFind();" +
                "  var ops = JSON.parse(opsJson);" +
                "  var html = '';" +
                "  for (var i = 0; i < ops.length; i++) {" +
                "    var op = ops[i];" +
                "    var bg = op.type === 'add' ? '" + addBg + "' : (op.type === 'del' ? '" + delBg + "' : 'transparent');" +
                "    var prefix = op.type === 'add' ? '+ ' : (op.type === 'del' ? '- ' : '  ');" +
                "    var color = op.type === 'add' ? '" + addFg + "' : (op.type === 'del' ? '" + delFg + "' : '" + text + "');" +
                "    html += '<div style=\"background:' + bg + '; color:' + color + '; padding:1px 8px; white-space:pre-wrap;\">' + prefix + escapeHtml(op.text) + '</div>';" +
                "  }" +
                "  document.getElementById('diffContainer').innerHTML = html;" +
                "  document.getElementById('diffContainer').style.display = 'block';" +
                "  document.getElementById('editorContainer').style.display = 'none';" +
                "}" +
                "function backToEditor() {" +
                "  document.getElementById('diffContainer').style.display = 'none';" +
                "  document.getElementById('editorContainer').style.display = 'block';" +
                "  cmEditor.refresh();" +
                "}" +
                "</script>" +
                "</body></html>";
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
