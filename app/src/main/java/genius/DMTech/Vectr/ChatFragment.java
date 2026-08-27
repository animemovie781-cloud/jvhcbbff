package genius.DMTech.Vectr;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.autofill.AutofillManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatFragment extends Fragment {

    private static final int MAX_TOOL_DEPTH = 8;
    private static final String PREFS = "vectr_prefs";
    private static final String KEY_DRAFT = "chat_draft_text";
    private static final int MAX_RETRIES = 2;
    private static final long WORKING_MIN_MS = 600;

    private RecyclerView messageList;
    private ChatAdapter adapter;
    private List<ChatMessage> messages = new ArrayList<>();
    private List<ChatMessage> apiMessages = new ArrayList<>();

    private EditText editMessage;
    private ImageButton btnSend, btnAttach, btnHistory, btnNewChat;
    private TextView modelIndicator, attachedFileChip;
    private View emptyChatState;
    private View pendingDiffBar;
    private TextView pendingDiffFile, pendingDiffStats, pendingDiffCount;
    private TextView btnPendingReview, btnPendingAccept, btnPendingReject;
    private View pendingBulkRow;
    private TextView btnPendingAcceptAll, btnPendingRejectAll, btnCheckpointRestore;
    private final SessionCheckpoint sessionCheckpoint = new SessionCheckpoint();
    /** true = плашка свёрнута в карточку чата; не всплывать снова сама */
    private boolean checkpointBarCollapsed = false;
    private ChatMessage activeCheckpointMsg;
    private Runnable hideCheckpointBarRunnable;
    private static final long CHECKPOINT_AUTO_HIDE_MS = 4500;

    private static class PendingFileChange {
        final String path;
        final String oldContent;
        final String newContent;
        final int added;
        final int removed;

        PendingFileChange(String path, String oldContent, String newContent, int added, int removed) {
            this.path = path;
            this.oldContent = oldContent;
            this.newContent = newContent;
            this.added = added;
            this.removed = removed;
        }
    }

    private final List<PendingFileChange> pendingChanges = new ArrayList<>();

    private ActivityResultLauncher<String[]> filePickerLauncher;
    private ActivityResultLauncher<String[]> imagePickerLauncher;
    private ActivityResultLauncher<String> notifPermissionLauncher;
    private String attachedFileContent;
    private PopupWindow composerPopup;

    private FileToolExecutor toolExecutor;
    private ChatRepository chatRepository;
    private long currentChatId = -1;

    private List<String> projectFileIndex = new ArrayList<>();
    private PopupWindow mentionPopup;
    private int mentionStart = -1;
    private static final Pattern AT_FILE_PATTERN = Pattern.compile("@([\\w./\\\\-]+)");
    private static final int MAX_MENTION_FILE_CHARS = 12000;

    private boolean isGenerating = false;
    private volatile boolean currentTurnCancelled = false;
    /** false пока truncatePersistedFrom ещё пишет в БД — send ждёт, иначе DELETE после INSERT. */
    private volatile boolean chatDbGateOpen = true;
    private Runnable pendingAfterDbGate;
    private int liveTurnDepth = 0;
    private int liveRetryAttempt = 1;
    private boolean liveAllowTools = true;
    private String liveApiKey = "";
    private android.app.Dialog pendingCommandDialog;
    private List<ToolCallInfo> pendingCommandCalls;
    /** Сессия ожидания команды (sheet или нотиф). */
    private String pendingCmdRequestId;
    private ChatMessage pendingCmdDisplayMsg;
    private List<ToolCallInfo> pendingCmdNeedConfirm;
    private List<ToolCallInfo> pendingCmdAllCalls;
    private String pendingCmdAssistantText;
    private String pendingCmdApiKey;
    private int pendingCmdDepth;
    private final ApprovalBus.Listener approvalListener = new ApprovalBus.Listener() {
        @Override
        public void onCommandApproved(String requestId) {
            runOnUi(() -> handleNotifCommandApproved(requestId));
        }

        @Override
        public void onCommandRejected(String requestId) {
            runOnUi(() -> handleNotifCommandRejected(requestId));
        }

        @Override
        public void onFileAccepted(String path) {
            runOnUi(() -> acceptPendingByPath(path));
        }

        @Override
        public void onFileRejected(String path) {
            runOnUi(() -> rejectPendingByPath(path));
        }
    };
    // на последнем разрешённом круге тулзов модель иногда всё равно пытается их вызвать (DSML-мусор),
    // мы это прячем и не выполняем - но тогда даём ОДИН принудительный запрос с явным запретом,
    // чтобы не оставить юзера с пустым ответом. Больше одного раза не делаем, иначе новый цикл.
    private boolean forcedFinalAttemptDone = false;
    // модель написала «сейчас прочитаю...» без tool_calls — дожимаем один раз
    private boolean forcedToolNudgeDone = false;
    private ChatMessage currentUserMsg;
    private ChatMessage currentAssistantMsg;
    private String currentUserRawText;

    private volatile boolean assistantAddedToApi = false;

    private static class WorkingEntry {
        ChatMessage msg;
        long addedAt;
        WorkingEntry(ChatMessage msg, long addedAt) {
            this.msg = msg;
            this.addedAt = addedAt;
        }
    }
    private final List<WorkingEntry> activeWorkingEntries = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // жёсткий потолок на весь обмен (все круги тулзов вместе) - если за это время
    // не долетело ни onComplete ни onError ни от чего, глушим руками сами
    private static final long GENERATION_WATCHDOG_MS = 5 * 60 * 1000;
    // если 3.5 минут нет ни чанка, ни thinking, ни tool_calls — соединение скорее всего зависло
    // (keep-alive сбрасывает readTimeout, но прогресса нет). Не ставить слишком жёстко:
    // у reasoning/pro первый токен может идти долго.
    private static final long INACTIVITY_WATCHDOG_MS = 210 * 1000;
    private Runnable generationWatchdog;
    private Runnable inactivityWatchdog;

    private boolean userScrolling = false;
    private Runnable pendingThinkingUi;
    private Runnable pendingStreamTextUi;
    private ChatMessage pendingThinkingMsg;
    private ChatMessage pendingStreamTextMsg;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        messageList = view.findViewById(R.id.message_list);
        editMessage = view.findViewById(R.id.edit_message);
        suppressComposerAutofill(view.findViewById(R.id.input_bar), editMessage);
        btnSend = view.findViewById(R.id.btn_send);
        btnAttach = view.findViewById(R.id.btn_attach);
        btnHistory = view.findViewById(R.id.btn_history);
        btnNewChat = view.findViewById(R.id.btn_new_chat);
        modelIndicator = view.findViewById(R.id.model_indicator);
        attachedFileChip = view.findViewById(R.id.attached_file_chip);
        emptyChatState = view.findViewById(R.id.empty_chat_state);
        pendingDiffBar = view.findViewById(R.id.pending_diff_bar);
        pendingDiffFile = view.findViewById(R.id.pending_diff_file);
        pendingDiffStats = view.findViewById(R.id.pending_diff_stats);
        pendingDiffCount = view.findViewById(R.id.pending_diff_count);
        btnPendingReview = view.findViewById(R.id.btn_pending_review);
        btnPendingAccept = view.findViewById(R.id.btn_pending_accept);
        btnPendingReject = view.findViewById(R.id.btn_pending_reject);
        pendingBulkRow = view.findViewById(R.id.pending_bulk_row);
        btnPendingAcceptAll = view.findViewById(R.id.btn_pending_accept_all);
        btnPendingRejectAll = view.findViewById(R.id.btn_pending_reject_all);
        btnCheckpointRestore = view.findViewById(R.id.btn_checkpoint_restore);

        btnPendingReview.setOnClickListener(v -> reviewCurrentPending());
        btnPendingAccept.setOnClickListener(v -> acceptCurrentPending());
        btnPendingReject.setOnClickListener(v -> rejectCurrentPending());
        btnPendingAcceptAll.setOnClickListener(v -> acceptAllPending());
        btnPendingRejectAll.setOnClickListener(v -> rejectAllPending());
        btnCheckpointRestore.setOnClickListener(v -> restoreSessionCheckpoint());

        adapter = new ChatAdapter(messages);
        messageList.setLayoutManager(new LinearLayoutManager(getContext()));
        messageList.setAdapter(adapter);
        // change-анимации на стриме дают дёрганье при раскрытом thinking
        RecyclerView.ItemAnimator animator = messageList.getItemAnimator();
        if (animator instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            ((androidx.recyclerview.widget.SimpleItemAnimator) animator)
                    .setSupportsChangeAnimations(false);
        }

        messageList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolling = true;
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    userScrolling = !isAtBottom();
                }
            }
        });

        adapter.setOnDiffClickListener(call -> {
            if (call.oldContent == null || call.newContent == null) {
                toast("Diff недоступен (чат был перезагружен)");
                return;
            }
            Fragment editor = getParentFragmentManager().findFragmentByTag("editor");
            if (editor instanceof EditorFragment) {
                ((EditorFragment) editor).showDiff(call.oldContent, call.newContent, call.targetFile);
            }
            requireView().post(() -> ((HomeActivity) requireActivity())._switchFragment("editor"));
        });

        adapter.setOnFilePathClickListener(this::openProjectFileInEditor);

        adapter.setOnActionListener(new ChatAdapter.OnActionListener() {
            @Override
            public void onCopyMessage(ChatMessage message) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("vectr_response", message.text);
                clipboard.setPrimaryClip(clip);
                toast("Скопировано");
            }

            @Override
            public void onRefreshMessage(ChatMessage message) {
                int aiIdx = messages.indexOf(message);
                if (aiIdx == -1) return;

                int userIdx = -1;
                for (int i = aiIdx - 1; i >= 0; i--) {
                    if (messages.get(i).role == ChatMessage.Role.USER) {
                        userIdx = i;
                        break;
                    }
                }
                if (userIdx == -1) return;

                String apiKey = AiConfig.getApiKey(requireContext());
                if (apiKey == null || apiKey.isEmpty()) {
                    toast("Сначала вбей API ключ в настройках");
                    return;
                }

                abortOngoingGeneration();

                final long keepChatId = currentChatId;
                ChatMessage userMsg = messages.get(userIdx);
                final String text = userMsg.text;

                // убираем user + всё после — proceedSendMessage добавит user заново
                while (messages.size() > userIdx) {
                    ChatMessage removed = messages.remove(messages.size() - 1);
                    if (removed.isCheckpointMessage && removed == activeCheckpointMsg) {
                        activeCheckpointMsg = null;
                    }
                }
                rebuildApiMessages();
                adapter.notifyDataSetChanged();
                updateEmptyChatState();
                scrollToBottom();

                Runnable resend = () -> {
                    currentChatId = keepChatId;
                    proceedSendMessage(text, apiKey);
                };

                if (keepChatId != -1) {
                    currentChatId = keepChatId;
                    truncatePersistedFromThen(userIdx, resend);
                } else {
                    resend.run();
                }
            }
        });

        adapter.setOnUserMessageListener(this::showUserMessageContextMenu);
        adapter.setOnCheckpointClickListener(msg -> reopenCheckpointBar());

        toolExecutor = new FileToolExecutor(requireContext().getApplicationContext());
        chatRepository = new ChatRepository(requireContext().getApplicationContext());
        refreshProjectFileIndex();

        modelIndicator.setText(AiConfig.getDisplayName(requireContext()));

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) attachFile(uri); }
        );
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) attachFile(uri); }
        );

        // без этого на Android 13+ нотифы AiStreamService молча не покажутся
        notifPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {});
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            String perm = android.Manifest.permission.POST_NOTIFICATIONS;
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), perm)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(perm);
            }
        }

        btnAttach.setOnClickListener(v -> showComposerMenu(v));
        refreshComposerPlusState();
        btnSend.setOnClickListener(v -> {
            if (isGenerating) stopGeneration();
            else sendMessage();
        });
        btnNewChat.setOnClickListener(v -> startNewChat());
        btnHistory.setOnClickListener(v -> showHistoryDialog());

        restoreDraft();
        editMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                saveDraft(s.toString());
                handleMentionQuery(s);
            }
        });

        // если стрим ещё жив — перецепляемся; иначе гарантируем кнопку «отправить»
        if (AiStreamManager.getInstance().isStreaming()) {
            reattachStreamCallback();
            setGeneratingState(true);
            AiStreamManager.getInstance().dismissDoneNotification();
        } else {
            setGeneratingState(false);
        }

        loadLastOrNewChat();
        updateEmptyChatState();
        ApprovalBus.get().setListener(approvalListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        modelIndicator.setText(AiConfig.getDisplayName(requireContext()));
        refreshProjectFileIndex();
        refreshComposerPlusState();

        AiStreamManager.getInstance().dismissDoneNotification();

        if (AiStreamManager.getInstance().isStreaming()) {
            if (!isGenerating) {
                reattachStreamCallback();
                setGeneratingState(true);
            }
        } else if (isGenerating) {
            // сервис уже не стримит — не держим Stop на пустом чате
            setGeneratingState(false);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden || getView() == null) return;
        refreshComposerPlusState();
        // hide/show вкладок не вызывает onResume — синхронизируем кнопку сами
        if (AiStreamManager.getInstance().isStreaming()) {
            if (!isGenerating) {
                reattachStreamCallback();
                setGeneratingState(true);
            }
        } else if (isGenerating) {
            setGeneratingState(false);
        }
    }

    /** Samsung Pass и др. autofill не должны всплывать над полем чата. */
    private void suppressComposerAutofill(View inputBar, EditText field) {
        if (field == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        int flags = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;
        if (inputBar != null) inputBar.setImportantForAutofill(flags);
        field.setImportantForAutofill(flags);
        try {
            field.setAutofillHints((String[]) null);
        } catch (Exception ignored) {}

        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus || getContext() == null) return;
            try {
                AutofillManager afm = requireContext().getSystemService(AutofillManager.class);
                if (afm != null) afm.cancel();
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onDestroyView() {
        dismissMentionPopup();
        dismissComposerMenu();
        clearInactivityWatchdog();
        ApprovalBus.get().clearListener(approvalListener);
        // не detachCallback тут — hide/show и низкая память на телефоне иначе
        // обнуляют колбэк посреди ответа. detach сделает HomeActivity.onDestroy → unbind.
        super.onDestroyView();
    }

    private void reattachStreamCallback() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.role == ChatMessage.Role.ASSISTANT && m.isStreaming) {
                currentAssistantMsg = m;
                break;
            }
        }
        if (currentAssistantMsg == null) return;
        assistantAddedToApi = true;
        String apiKey = liveApiKey;
        if ((apiKey == null || apiKey.isEmpty()) && getContext() != null) {
            apiKey = AiConfig.getApiKey(requireContext());
        }
        // сидим буферы уже полученным текстом — иначе reattach затирает ответ пустыми StringBuilder
        AiStreamManager.getInstance().attachCallback(createStreamCallback(
                currentAssistantMsg, apiKey, liveTurnDepth, liveRetryAttempt, liveAllowTools,
                currentAssistantMsg.text != null ? currentAssistantMsg.text : "",
                currentAssistantMsg.thinking != null ? currentAssistantMsg.thinking : ""));
    }

    private void rebuildApiMessages() {
        apiMessages.clear();
        for (ChatMessage msg : messages) {
            if (msg.isWorkingMessage || msg.isCheckpointMessage) continue;
            if (msg.role == ChatMessage.Role.USER || msg.role == ChatMessage.Role.ASSISTANT) {
                apiMessages.add(msg);
            }
            if (msg.toolCalls != null) {
                for (ToolCallInfo call : msg.toolCalls) {
                    ChatMessage toolMsg = new ChatMessage(ChatMessage.Role.TOOL, call.result);
                    toolMsg.toolCallId = call.id;
                    apiMessages.add(toolMsg);
                }
            }
        }
    }

    private void saveDraft(String text) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS, 0);
        if (text == null || text.isEmpty()) {
            prefs.edit().remove(KEY_DRAFT).apply();
        } else {
            prefs.edit().putString(KEY_DRAFT, text).apply();
        }
    }

    private void restoreDraft() {
        String saved = requireContext().getSharedPreferences(PREFS, 0).getString(KEY_DRAFT, null);
        if (saved != null && !saved.isEmpty()) {
            editMessage.setText(saved);
            editMessage.setSelection(saved.length());
        }
    }

    private void abortOngoingGeneration() {
        if (!isGenerating) return;
        currentTurnCancelled = true;
        dismissPendingCommandDialog(true);
        AiStreamManager.getInstance().cancelStream();
        currentUserMsg = null;
        currentAssistantMsg = null;
        clearAllWorking();
        setGeneratingState(false);
    }

    /** Закрыть диалог run_command; при stop — без continueToolTurn (не засоряем apiMessages). */
    private void dismissPendingCommandDialog(boolean cancelledByStop) {
        List<ToolCallInfo> cmds = pendingCmdNeedConfirm != null
                ? pendingCmdNeedConfirm : pendingCommandCalls;
        if (pendingCommandDialog != null) {
            android.app.Dialog d = pendingCommandDialog;
            pendingCommandDialog = null;
            try {
                d.dismiss();
            } catch (Exception ignored) {}
        }
        if (cancelledByStop) {
            if (cmds != null) {
                for (ToolCallInfo c : cmds) {
                    if (!c.done) {
                        c.result = "Отменено пользователем";
                        c.done = true;
                    }
                }
            }
            clearPendingCommandSession();
        }
    }

    private void clearAllWorking() {
        for (WorkingEntry e : activeWorkingEntries) {
            messages.remove(e.msg);
        }
        activeWorkingEntries.clear();
        adapter.notifyDataSetChanged();
        updateEmptyChatState();
    }

    private void showWorking(String fileName) {
        ChatMessage wm = ChatMessage.createWorking(fileName);
        messages.add(wm);
        WorkingEntry entry = new WorkingEntry(wm, System.currentTimeMillis());
        activeWorkingEntries.add(entry);

        // всегда notify — иначе при userScrolling список и adapter разъезжаются
        adapter.notifyItemInserted(messages.size() - 1);
        if (!userScrolling) scrollToBottom();
    }

    private void hideWorking(String fileName) {
        WorkingEntry found = null;
        for (WorkingEntry e : activeWorkingEntries) {
            if (fileName.equals(e.msg.workingFileName)) {
                found = e;
                break;
            }
        }
        if (found == null) return;

        long elapsed = System.currentTimeMillis() - found.addedAt;
        long remaining = WORKING_MIN_MS - elapsed;

        if (remaining <= 0) {
            doRemoveWorking(found);
        } else {
            WorkingEntry f = found;
            mainHandler.postDelayed(() -> {
                if (currentTurnCancelled) return;
                doRemoveWorking(f);
            }, remaining);
        }
    }

    private void doRemoveWorking(WorkingEntry entry) {
        if (!activeWorkingEntries.contains(entry)) return;
        activeWorkingEntries.remove(entry);
        int idx = messages.indexOf(entry.msg);
        if (idx != -1) {
            messages.remove(idx);
            adapter.notifyItemRemoved(idx);
        }
    }

    private void loadLastOrNewChat() {
        new Thread(() -> {
            chatRepository.purgeEmptyChats();
            long lastId = chatRepository.getLastChatId();
            if (lastId == -1) {
                runOnUi(() -> {
                    currentChatId = -1;
                    updateEmptyChatState();
                });
            } else {
                loadChatInternal(lastId);
            }
        }).start();
    }

    private void loadChatInternal(long chatId) {
        List<ChatMessage> loaded = chatRepository.loadMessages(chatId);
        List<ChatMessage> newApiMessages = new ArrayList<>();
        for (ChatMessage msg : loaded) {
            newApiMessages.add(msg);
            if (msg.toolCalls != null) {
                for (ToolCallInfo call : msg.toolCalls) {
                    ChatMessage toolMsg = new ChatMessage(ChatMessage.Role.TOOL, call.result);
                    toolMsg.toolCallId = call.id;
                    newApiMessages.add(toolMsg);
                }
            }
        }
        runOnUi(() -> {
            abortOngoingGeneration();
            currentChatId = chatId;
            messages.clear();
            messages.addAll(loaded);
            apiMessages.clear();
            apiMessages.addAll(newApiMessages);
            adapter.notifyDataSetChanged();
            updateEmptyChatState();
            scrollToBottom();
        });
    }

    private void startNewChat() {
        if (messages.isEmpty()) {
            toast("Уже новый чат");
            return;
        }
        abortOngoingGeneration();
        currentChatId = -1;
        messages.clear();
        apiMessages.clear();
        pendingChanges.clear();
        sessionCheckpoint.clear();
        refreshPendingDiffBar();
        adapter.notifyDataSetChanged();
        updateEmptyChatState();
    }

    private void showHistoryDialog() {
        new Thread(() -> {
            List<ChatRepository.ChatSummary> chats = chatRepository.listChats();
            runOnUi(() -> {
                if (chats.isEmpty()) {
                    toast("История пуста");
                    return;
                }
                ChatHistoryBottomSheet sheet = new ChatHistoryBottomSheet();
                sheet.setChats(chats);
                sheet.setOnChatSelectedListener(chatId -> {
                    new Thread(() -> loadChatInternal(chatId)).start();
                });
                sheet.setOnChatDeleteListener(chatId -> {
                    // БД уже удалила в bottomsheet — только сбросить UI если это текущий чат
                    if (chatId == currentChatId) {
                        abortOngoingGeneration();
                        currentChatId = -1;
                        messages.clear();
                        apiMessages.clear();
                        adapter.notifyDataSetChanged();
                        updateEmptyChatState();
                    }
                });
                sheet.show(getParentFragmentManager(), "chat_history");
            });
        }).start();
    }

    private void updateEmptyChatState() {
        if (emptyChatState == null || messageList == null) return;
        boolean empty = messages.isEmpty();
        // после «Редактировать» чат пустой, но id живой — не показываем hero «нового чата»
        boolean showHero = empty && currentChatId == -1;
        emptyChatState.setVisibility(showHero ? View.VISIBLE : View.GONE);
        messageList.setVisibility(empty && showHero ? View.INVISIBLE : View.VISIBLE);
    }

    private void toast(String text) {
        if (getContext() == null) return;
        VectrToast.show(requireContext(), text);
    }

    private void toastError(String text) {
        if (getContext() == null) return;
        VectrToast.showError(requireContext(), text);
    }

    private void hideKeyboard() {
        if (editMessage != null) editMessage.clearFocus();
        View root = getView();
        if (root == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    private void showKeyboard(View target) {
        if (target == null) return;
        target.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void copyToClipboard(String label, String text) {
        if (text == null) text = "";
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        toast("Скопировано");
    }

    private void showUserMessageContextMenu(TextView textView, ChatMessage message) {
        PopupMenu menu = new PopupMenu(requireContext(), textView);
                menu.getMenu().add(0, 1, 0, R.string.chat_copy);
        menu.getMenu().add(0, 2, 1, R.string.chat_copy_selection);
        menu.getMenu().add(0, 3, 2, "Редактировать");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                copyToClipboard("vectr_user", message.text != null ? message.text : "");
                return true;
            }
            if (id == 2) {
                // если уже выделено в пузыре — копируем сразу, иначе диалог
                int selStart = textView.getSelectionStart();
                int selEnd = textView.getSelectionEnd();
                if (selStart >= 0 && selEnd > selStart) {
                    CharSequence selected = textView.getText().subSequence(selStart, selEnd);
                    copyToClipboard("vectr_selection", selected.toString());
                } else {
                    showSelectiveCopyDialog(message.text != null ? message.text : "");
                }
                return true;
            }
            if (id == 3) {
                editUserMessage(message);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showSelectiveCopyDialog(String fullText) {
        Context ctx = requireContext();
        EditText et = new EditText(ctx);
        et.setText(fullText);
        et.setTextIsSelectable(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setMinLines(5);
        et.setMaxLines(14);
        et.setTextSize(14f);
        et.setTextColor(ctx.getColor(R.color.text_primary));
        et.setBackgroundResource(R.drawable.bg_input_field);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        et.setSelectAllOnFocus(false);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(et);
        int outer = (int) (20 * getResources().getDisplayMetrics().density);
        scroll.setPadding(outer, outer / 2, outer, 0);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.chat_copy_selection)
                .setMessage(R.string.chat_copy_selection_hint)
                .setView(scroll)
                .setPositiveButton(R.string.chat_copy, (d, w) -> {
                    int start = et.getSelectionStart();
                    int end = et.getSelectionEnd();
                    String toCopy;
                    if (start >= 0 && end > start) {
                        toCopy = et.getText().subSequence(start, end).toString();
                    } else {
                        toCopy = et.getText().toString();
                    }
                    copyToClipboard("vectr_selection", toCopy);
                })
                .setNegativeButton("Отмена", null)
                .show();

        et.post(() -> {
            et.requestFocus();
            if (et.length() > 0) et.setSelection(0, et.length());
        });
    }

    /** Убирает вложения/@file-расширения из текста для правки промпта. */
    private String extractEditablePrompt(String text) {
        if (text == null || text.isEmpty()) return "";
        String t = text;
        if (t.startsWith("📎 Файл:")) {
            int sep = t.indexOf("\n\n");
            if (sep >= 0) t = t.substring(sep + 2);
        }
        int fileMarker = t.indexOf("\n\n--- File: ");
        if (fileMarker >= 0) t = t.substring(0, fileMarker);
        return t.trim();
    }

    private void editUserMessage(ChatMessage message) {
        int userIdx = messages.indexOf(message);
        if (userIdx < 0) return;

        // обязательно сохраняем id чата — иначе после очистки ленты sendMessage создаст «Новый чат»
        final long keepChatId = currentChatId;

        abortOngoingGeneration();

        String prompt = extractEditablePrompt(message.text);
        while (messages.size() > userIdx) {
            ChatMessage removed = messages.remove(messages.size() - 1);
            if (removed.isCheckpointMessage && removed == activeCheckpointMsg) {
                activeCheckpointMsg = null;
            }
        }
        rebuildApiMessages();
        pendingChanges.clear();
        checkpointBarCollapsed = false;
        refreshPendingDiffBar();
        adapter.notifyDataSetChanged();
        updateEmptyChatState();

        editMessage.setText(prompt);
        editMessage.setSelection(prompt.length());
        showKeyboard(editMessage);
        toast("Правка в этом чате — отправь снова");

        if (keepChatId != -1) {
            currentChatId = keepChatId;
            // ждём DELETE в БД, иначе быстрый resend успеет INSERT до truncate
            truncatePersistedFromThen(userIdx, null);
        }
    }

    private void truncatePersistedFromThen(int fromOrderInclusive, @Nullable Runnable afterOnUi) {
        if (currentChatId == -1) {
            chatDbGateOpen = true;
            if (afterOnUi != null) afterOnUi.run();
            flushPendingAfterDbGate();
            return;
        }
        chatDbGateOpen = false;
        long chatId = currentChatId;
        new Thread(() -> {
            chatRepository.deleteMessagesFromOrder(chatId, fromOrderInclusive);
            runOnUi(() -> {
                chatDbGateOpen = true;
                if (afterOnUi != null) afterOnUi.run();
                flushPendingAfterDbGate();
            });
        }).start();
    }

    private void flushPendingAfterDbGate() {
        Runnable queued = pendingAfterDbGate;
        pendingAfterDbGate = null;
        if (queued != null) queued.run();
    }

    private void enqueuePendingChange(PendingFileChange change) {
        if (change == null || change.path == null) return;
        sessionCheckpoint.rememberOriginal(change.path, change.oldContent);

        if (AgentTrust.isAutoAcceptEdits()) {
            refreshEditorIfOpen(change.path, change.newContent);
            return;
        }

        // новые правки снова показывают плашку
        checkpointBarCollapsed = false;
        cancelHideCheckpointBar();
        for (int i = 0; i < pendingChanges.size(); i++) {
            if (change.path.equals(pendingChanges.get(i).path)) {
                PendingFileChange prev = pendingChanges.get(i);
                pendingChanges.set(i, new PendingFileChange(
                        change.path, prev.oldContent, change.newContent,
                        change.added, change.removed));
                refreshPendingDiffBar();
                maybeNotifyFileApproval(pendingChanges.get(i));
                return;
            }
        }
        pendingChanges.add(change);
        refreshPendingDiffBar();
        maybeNotifyFileApproval(change);
    }

    private void maybeNotifyFileApproval(PendingFileChange change) {
        if (change == null || getContext() == null) return;
        if (AiStreamManager.getInstance().isAppInForeground()) return;
        String name = change.path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        String text = name + " · +" + change.added + " −" + change.removed;
        AiStreamService.postFileApproval(requireContext(), change.path,
                getString(R.string.notif_file_title), text);
    }

    private void refreshPendingDiffBar() {
        if (pendingDiffBar == null) return;
        boolean hasPending = !pendingChanges.isEmpty();
        boolean hasCheckpoint = sessionCheckpoint.hasSnapshots();

        // только pending — всегда показываем компактную панель
        // только checkpoint без pending — не висит постоянно (свёрнуто в чат)
        if (!hasPending) {
            if (hasCheckpoint && !checkpointBarCollapsed) {
                // короткий показ + автосворачивание в карточку
                showFloatingBarForCheckpointOnly();
                scheduleCollapseCheckpointBar();
            } else {
                hideFloatingBarAnimated();
                ensureCheckpointCardInChat();
            }
            return;
        }

        cancelHideCheckpointBar();
        checkpointBarCollapsed = false;
        pendingDiffBar.animate().cancel();
        pendingDiffBar.setAlpha(1f);
        pendingDiffBar.setTranslationY(0f);
        pendingDiffBar.setVisibility(View.VISIBLE);

        PendingFileChange cur = pendingChanges.get(0);
        String name = cur.path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);

        pendingDiffFile.setText(name);
        pendingDiffStats.setText("+" + cur.added + " −" + cur.removed);
        pendingDiffFile.setVisibility(View.VISIBLE);
        pendingDiffStats.setVisibility(View.VISIBLE);

        if (pendingChanges.size() > 1) {
            pendingDiffCount.setVisibility(View.VISIBLE);
            int left = pendingChanges.size() - 1;
            pendingDiffCount.setText(getString(R.string.pending_more_short, left));
            if (pendingBulkRow != null) pendingBulkRow.setVisibility(View.VISIBLE);
        } else {
            pendingDiffCount.setVisibility(View.GONE);
            if (pendingBulkRow != null) pendingBulkRow.setVisibility(View.GONE);
        }
        btnPendingReview.setVisibility(View.VISIBLE);
        btnPendingAccept.setVisibility(View.VISIBLE);
        btnPendingReject.setVisibility(View.VISIBLE);
        if (btnCheckpointRestore != null) {
            btnCheckpointRestore.setVisibility(hasCheckpoint ? View.VISIBLE : View.GONE);
            if (hasCheckpoint) {
                btnCheckpointRestore.setText("↩ откат · " + sessionCheckpoint.size());
            }
        }
    }

    private void showFloatingBarForCheckpointOnly() {
        pendingDiffBar.animate().cancel();
        pendingDiffBar.setVisibility(View.VISIBLE);
        pendingDiffBar.setAlpha(1f);
        pendingDiffBar.setTranslationY(0f);
        pendingDiffFile.setText(R.string.pending_agent_session);
        pendingDiffStats.setText(sessionCheckpoint.size() + "ф");
        pendingDiffCount.setVisibility(View.GONE);
        if (pendingBulkRow != null) pendingBulkRow.setVisibility(View.GONE);
        btnPendingReview.setVisibility(View.GONE);
        btnPendingAccept.setVisibility(View.GONE);
        btnPendingReject.setVisibility(View.GONE);
        if (btnCheckpointRestore != null) {
            btnCheckpointRestore.setVisibility(View.VISIBLE);
            btnCheckpointRestore.setText("↩ Откатить сессию · " + sessionCheckpoint.size());
        }
    }

    private void scheduleCollapseCheckpointBar() {
        cancelHideCheckpointBar();
        hideCheckpointBarRunnable = () -> {
            if (!pendingChanges.isEmpty()) return;
            if (!sessionCheckpoint.hasSnapshots()) {
                hideFloatingBarAnimated();
                return;
            }
            checkpointBarCollapsed = true;
            ensureCheckpointCardInChat();
            hideFloatingBarAnimated();
        };
        mainHandler.postDelayed(hideCheckpointBarRunnable, CHECKPOINT_AUTO_HIDE_MS);
    }

    private void cancelHideCheckpointBar() {
        if (hideCheckpointBarRunnable != null) {
            mainHandler.removeCallbacks(hideCheckpointBarRunnable);
            hideCheckpointBarRunnable = null;
        }
    }

    private void hideFloatingBarAnimated() {
        if (pendingDiffBar == null) return;
        if (pendingDiffBar.getVisibility() != View.VISIBLE) return;
        pendingDiffBar.animate().cancel();
        pendingDiffBar.animate()
                .alpha(0f)
                .translationY(dp(12))
                .setDuration(220)
                .withEndAction(() -> {
                    pendingDiffBar.setVisibility(View.GONE);
                    pendingDiffBar.setAlpha(1f);
                    pendingDiffBar.setTranslationY(0f);
                })
                .start();
    }

    private void ensureCheckpointCardInChat() {
        if (!sessionCheckpoint.hasSnapshots()) return;
        int count = sessionCheckpoint.size();
        if (activeCheckpointMsg != null && messages.contains(activeCheckpointMsg)) {
            activeCheckpointMsg.checkpointFileCount = count;
            activeCheckpointMsg.text = "Checkpoint · " + count;
            int idx = messages.indexOf(activeCheckpointMsg);
            if (idx >= 0) adapter.notifyItemChanged(idx);
            return;
        }
        // убрать старые checkpoint-карточки
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isCheckpointMessage) {
                messages.remove(i);
                adapter.notifyItemRemoved(i);
            }
        }
        ChatMessage card = ChatMessage.createCheckpoint(count);
        activeCheckpointMsg = card;
        messages.add(card);
        adapter.notifyItemInserted(messages.size() - 1);
        if (!userScrolling) scrollToBottom();
    }

    private void reopenCheckpointBar() {
        if (!sessionCheckpoint.hasSnapshots()) {
            toast("Checkpoint уже неактуален");
            return;
        }
        checkpointBarCollapsed = false;
        cancelHideCheckpointBar();
        showFloatingBarForCheckpointOnly();
        scheduleCollapseCheckpointBar();
        toast("Панель checkpoint");
    }

    private void reviewCurrentPending() {
        if (pendingChanges.isEmpty()) return;
        PendingFileChange cur = pendingChanges.get(0);
        Fragment editor = getParentFragmentManager().findFragmentByTag("editor");
        if (editor instanceof EditorFragment) {
            ((EditorFragment) editor).showDiff(cur.oldContent, cur.newContent, cur.path);
        }
        requireView().post(() -> ((HomeActivity) requireActivity())._switchFragment("editor"));
    }

    private void acceptCurrentPending() {
        if (pendingChanges.isEmpty()) return;
        PendingFileChange cur = pendingChanges.remove(0);
        cancelFileNotif(cur.path);
        toast("Оставлено: " + cur.path);
        refreshPendingDiffBar();
        refreshEditorIfOpen(cur.path, cur.newContent);
    }

    private void acceptPendingByPath(String path) {
        if (path == null) return;
        for (int i = 0; i < pendingChanges.size(); i++) {
            if (path.equals(pendingChanges.get(i).path)) {
                PendingFileChange cur = pendingChanges.remove(i);
                cancelFileNotif(cur.path);
                toast("Оставлено: " + cur.path);
                refreshPendingDiffBar();
                refreshEditorIfOpen(cur.path, cur.newContent);
                return;
            }
        }
    }

    private void rejectPendingByPath(String path) {
        if (path == null) return;
        for (int i = 0; i < pendingChanges.size(); i++) {
            if (path.equals(pendingChanges.get(i).path)) {
                PendingFileChange cur = pendingChanges.remove(i);
                cancelFileNotif(cur.path);
                new Thread(() -> {
                    String result = toolExecutor.writeFile(cur.path,
                            cur.oldContent != null ? cur.oldContent : "");
                    runOnUi(() -> {
                        if (result != null && result.startsWith("ОШИБКА")) {
                            toastError(result);
                            pendingChanges.add(0, cur);
                        } else {
                            toast("Откатил: " + cur.path);
                            refreshEditorIfOpen(cur.path,
                                    cur.oldContent != null ? cur.oldContent : "");
                        }
                        refreshPendingDiffBar();
                    });
                }).start();
                return;
            }
        }
    }

    private void cancelFileNotif(String path) {
        if (path == null || getContext() == null) return;
        AiStreamService.cancelFileApproval(requireContext(), path);
    }

    private void acceptAllPending() {
        if (pendingChanges.isEmpty()) return;
        int n = pendingChanges.size();
        List<PendingFileChange> copy = new ArrayList<>(pendingChanges);
        pendingChanges.clear();
        for (PendingFileChange cur : copy) {
            cancelFileNotif(cur.path);
            refreshEditorIfOpen(cur.path, cur.newContent);
        }
        toast("Оставлено файлов: " + n);
        refreshPendingDiffBar();
    }

    private void rejectCurrentPending() {
        if (pendingChanges.isEmpty()) return;
        PendingFileChange cur = pendingChanges.remove(0);
        cancelFileNotif(cur.path);
        new Thread(() -> {
            String result = toolExecutor.writeFile(cur.path,
                    cur.oldContent != null ? cur.oldContent : "");
            runOnUi(() -> {
                if (result != null && result.startsWith("ОШИБКА")) {
                    toastError(result);
                    pendingChanges.add(0, cur);
                } else {
                    toast("Откатил: " + cur.path);
                    refreshEditorIfOpen(cur.path, cur.oldContent != null ? cur.oldContent : "");
                }
                refreshPendingDiffBar();
            });
        }).start();
    }

    private void rejectAllPending() {
        if (pendingChanges.isEmpty()) return;
        List<PendingFileChange> copy = new ArrayList<>(pendingChanges);
        pendingChanges.clear();
        for (PendingFileChange cur : copy) {
            cancelFileNotif(cur.path);
        }
        refreshPendingDiffBar();
        new Thread(() -> {
            int ok = 0;
            List<PendingFileChange> failed = new ArrayList<>();
            for (PendingFileChange cur : copy) {
                String result = toolExecutor.writeFile(cur.path,
                        cur.oldContent != null ? cur.oldContent : "");
                if (result != null && result.startsWith("ОШИБКА")) {
                    failed.add(cur);
                } else {
                    ok++;
                    String path = cur.path;
                    String old = cur.oldContent != null ? cur.oldContent : "";
                    runOnUi(() -> refreshEditorIfOpen(path, old));
                }
            }
            int finalOk = ok;
            runOnUi(() -> {
                if (!failed.isEmpty()) {
                    pendingChanges.addAll(0, failed);
                    toastError("Откатил " + finalOk + ", не удалось: " + failed.size());
                } else {
                    toast("Откатил файлов: " + finalOk);
                }
                refreshPendingDiffBar();
            });
        }).start();
    }

    private void restoreSessionCheckpoint() {
        if (!sessionCheckpoint.hasSnapshots()) {
            toast("Нет checkpoint");
            return;
        }
        java.util.Map<String, String> snap = sessionCheckpoint.snapshotCopy();
        pendingChanges.clear();
        refreshPendingDiffBar();
        new Thread(() -> {
            int ok = 0;
            for (java.util.Map.Entry<String, String> e : snap.entrySet()) {
                String result = toolExecutor.writeFile(e.getKey(), e.getValue() != null ? e.getValue() : "");
                if (result == null || !result.startsWith("ОШИБКА")) {
                    ok++;
                    String path = e.getKey();
                    String content = e.getValue() != null ? e.getValue() : "";
                    runOnUi(() -> refreshEditorIfOpen(path, content));
                }
            }
            int finalOk = ok;
            runOnUi(() -> {
                sessionCheckpoint.clear();
                checkpointBarCollapsed = true;
                cancelHideCheckpointBar();
                removeCheckpointCards();
                activeCheckpointMsg = null;
                hideFloatingBarAnimated();
                toast("Сессия откачена · " + finalOk);
            });
        }).start();
    }

    private void removeCheckpointCards() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isCheckpointMessage) {
                messages.remove(i);
                adapter.notifyItemRemoved(i);
            }
        }
    }

    /** Вставить промпт в поле ввода и сфокусировать (из редактора). */
    public void composeAndFocus(String prompt) {
        if (editMessage == null) return;
        if (prompt == null) prompt = "";
        editMessage.setText(prompt);
        editMessage.setSelection(prompt.length());
        showKeyboard(editMessage);
        updateEmptyChatState();
    }

    private void refreshEditorIfOpen(String path, String content) {
        if (path == null || content == null) return;
        Fragment editor = getParentFragmentManager().findFragmentByTag("editor");
        if (!(editor instanceof EditorFragment)) return;
        Uri uri = toolExecutor.resolveFileUri(path);
        if (uri == null) return;
        ProjectState state = ProjectState.getInstance();
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int tabIdx = state.findTabByUri(uri);
        if (tabIdx >= 0) {
            OpenFileTab tab = state.openTabs.get(tabIdx);
            tab.content = content;
            tab.relativePath = path;
            tab.isDirty = false;
            if (state.currentTabIndex == tabIdx) {
                ((EditorFragment) editor).openFile(uri, name, path);
            }
        }
    }

    private void openProjectFileInEditor(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) return;
        if (ProjectState.getInstance().projectRootUri == null) {
            toast("Сначала открой проект");
            return;
        }
        Uri uri = toolExecutor.resolveFileUri(relativePath.trim());
        if (uri == null) {
            toast("Файл не найден: " + relativePath);
            return;
        }
        String name = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;
        Fragment editor = getParentFragmentManager().findFragmentByTag("editor");
        if (editor instanceof EditorFragment) {
            ((EditorFragment) editor).openFile(uri, name, relativePath.trim());
        }
        requireView().post(() -> ((HomeActivity) requireActivity())._switchFragment("editor"));
    }

    private void refreshProjectFileIndex() {
        if (toolExecutor == null) return;
        new Thread(() -> {
            List<String> paths = toolExecutor.listProjectFilePaths(500);
            runOnUi(() -> projectFileIndex = paths);
        }).start();
    }

    private void handleMentionQuery(Editable s) {
        if (s == null || editMessage == null) return;
        int cursor = editMessage.getSelectionStart();
        if (cursor < 0) {
            dismissMentionPopup();
            return;
        }
        String before = s.subSequence(0, cursor).toString();
        int at = before.lastIndexOf('@');
        if (at < 0) {
            dismissMentionPopup();
            return;
        }
        if (at > 0 && !Character.isWhitespace(before.charAt(at - 1))) {
            dismissMentionPopup();
            return;
        }
        String query = before.substring(at + 1);
        if (query.indexOf(' ') >= 0 || query.indexOf('\n') >= 0) {
            dismissMentionPopup();
            return;
        }
        mentionStart = at;
        showMentionSuggestions(query.toLowerCase(Locale.ROOT));
    }

    private void showMentionSuggestions(String query) {
        if (projectFileIndex.isEmpty()) {
            refreshProjectFileIndex();
            dismissMentionPopup();
            return;
        }
        List<String> filtered = new ArrayList<>();
        for (String path : projectFileIndex) {
            if (query.isEmpty() || path.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(path);
                if (filtered.size() >= 12) break;
            }
        }
        if (filtered.isEmpty()) {
            dismissMentionPopup();
            return;
        }

        if (mentionPopup != null && mentionPopup.isShowing()) {
            ListView lv = (ListView) mentionPopup.getContentView();
            @SuppressWarnings("unchecked")
            ArrayAdapter<String> adapterPopup = (ArrayAdapter<String>) lv.getAdapter();
            adapterPopup.clear();
            adapterPopup.addAll(filtered);
            adapterPopup.notifyDataSetChanged();
            return;
        }

        ListView listView = new ListView(requireContext());
        ArrayAdapter<String> adapterPopup = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, new ArrayList<>(filtered));
        listView.setAdapter(adapterPopup);
        listView.setBackgroundColor(requireContext().getColor(R.color.surface));
        listView.setDividerHeight(1);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String path = adapterPopup.getItem(position);
            insertMention(path);
            dismissMentionPopup();
        });

        mentionPopup = new PopupWindow(listView,
                Math.max(editMessage.getWidth(), dp(200)),
                dp(220),
                true);
        mentionPopup.setOutsideTouchable(true);
        mentionPopup.setElevation(12f);
        mentionPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                requireContext().getColor(R.color.surface)));
        int yOff = -(dp(220) + editMessage.getHeight() + dp(8));
        try {
            mentionPopup.showAsDropDown(editMessage, 0, yOff);
        } catch (Exception e) {
            mentionPopup.showAsDropDown(editMessage, 0, 0);
        }
    }

    private void insertMention(String path) {
        if (path == null || editMessage == null || mentionStart < 0) return;
        Editable editable = editMessage.getText();
        int cursor = editMessage.getSelectionStart();
        if (cursor < mentionStart) cursor = editable.length();
        editable.replace(mentionStart, cursor, "@" + path + " ");
        editMessage.setSelection(mentionStart + path.length() + 2);
        mentionStart = -1;
    }

    private void dismissMentionPopup() {
        if (mentionPopup != null && mentionPopup.isShowing()) {
            mentionPopup.dismiss();
        }
        mentionPopup = null;
        mentionStart = -1;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** Injects contents of @path mentions into the outgoing user message. */
    private String expandAtMentions(String text) {
        if (text == null || !text.contains("@")) return text;
        Matcher m = AT_FILE_PATTERN.matcher(text);
        Set<String> seen = new HashSet<>();
        StringBuilder appendix = new StringBuilder();
        while (m.find()) {
            String path = m.group(1).replace('\\', '/');
            if (!seen.add(path)) continue;
            String content = toolExecutor.readFile(path);
            if (content == null || content.startsWith("ОШИБКА")) continue;
            if (content.length() > MAX_MENTION_FILE_CHARS) {
                content = content.substring(0, MAX_MENTION_FILE_CHARS) + "\n…(обрезано)";
            }
            appendix.append("\n\n--- File: ").append(path).append(" ---\n```\n")
                    .append(content).append("\n```");
        }
        if (appendix.length() == 0) return text;
        return text + appendix;
    }

    private void showComposerMenu(View anchor) {
        dismissComposerMenu();
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.popup_composer_menu, null, false);

        View checkThinking = content.findViewById(R.id.composer_check_thinking);
        View checkWeb = content.findViewById(R.id.composer_check_web);
        View checkPlan = content.findViewById(R.id.composer_check_plan);

        boolean thinking = AiConfig.isThinkingEnabled(requireContext());
        boolean web = AiConfig.isWebSearchEnabled(requireContext());
        boolean plan = AiConfig.isPlanModeEnabled(requireContext());
        checkThinking.setVisibility(thinking ? View.VISIBLE : View.GONE);
        checkWeb.setVisibility(web ? View.VISIBLE : View.GONE);
        checkPlan.setVisibility(plan ? View.VISIBLE : View.GONE);

        content.findViewById(R.id.composer_item_thinking).setOnClickListener(v -> {
            boolean next = !AiConfig.isThinkingEnabled(requireContext());
            AiConfig.setThinkingEnabled(requireContext(), next);
            toast(getString(next ? R.string.composer_thinking_on : R.string.composer_thinking_off));
            VectrHaptics.tap(v);
            dismissComposerMenu();
            refreshComposerPlusState();
        });
        content.findViewById(R.id.composer_item_web).setOnClickListener(v -> {
            boolean next = !AiConfig.isWebSearchEnabled(requireContext());
            AiConfig.setWebSearchEnabled(requireContext(), next);
            toast(getString(next ? R.string.composer_web_on : R.string.composer_web_off));
            VectrHaptics.tap(v);
            dismissComposerMenu();
            refreshComposerPlusState();
        });
        content.findViewById(R.id.composer_item_plan).setOnClickListener(v -> {
            boolean next = !AiConfig.isPlanModeEnabled(requireContext());
            AiConfig.setPlanModeEnabled(requireContext(), next);
            toast(getString(next ? R.string.composer_plan_on : R.string.composer_plan_off));
            VectrHaptics.tap(v);
            dismissComposerMenu();
            refreshComposerPlusState();
        });
        content.findViewById(R.id.composer_item_image).setOnClickListener(v -> {
            dismissComposerMenu();
            imagePickerLauncher.launch(new String[]{"image/*"});
        });
        content.findViewById(R.id.composer_item_file).setOnClickListener(v -> {
            dismissComposerMenu();
            filePickerLauncher.launch(new String[]{"*/*"});
        });

        content.measure(
                View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupH = content.getMeasuredHeight();

        composerPopup = new PopupWindow(content, dp(280),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        composerPopup.setOutsideTouchable(true);
        composerPopup.setElevation(dp(8));
        composerPopup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        // якорим над кнопкой +, с небольшим зазором
        int yOff = -(popupH + anchor.getHeight() + dp(6));
        try {
            composerPopup.showAsDropDown(anchor, 0, yOff);
        } catch (Exception e) {
            composerPopup.showAsDropDown(anchor, 0, -dp(8));
        }
    }

    private void dismissComposerMenu() {
        if (composerPopup != null && composerPopup.isShowing()) {
            composerPopup.dismiss();
        }
        composerPopup = null;
    }

    private void refreshComposerPlusState() {
        if (btnAttach == null || !isAdded()) return;
        boolean any = AiConfig.isThinkingEnabled(requireContext())
                || AiConfig.isPlanModeEnabled(requireContext())
                || !AiConfig.isWebSearchEnabled(requireContext());
        // web выкл тоже «особый» режим — подсветим плюс
        int color = requireContext().getColor(any ? R.color.accent : R.color.text_secondary);
        btnAttach.setColorFilter(color);
    }

    private void attachFile(Uri uri) {
        try {
            String mime = requireContext().getContentResolver().getType(uri);
            String name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) {
                name = name.substring(name.lastIndexOf('/') + 1);
            }
            if (mime != null && mime.startsWith("image/")) {
                attachedFileContent = "🖼 Изображение: " + (name != null ? name : "image")
                        + " (" + mime + ")\n"
                        + "Пользователь прикрепил картинку. Vision в Vectr пока не подключён — "
                        + "если нужно разобрать содержимое, попроси коротко описать изображение текстом.";
                attachedFileChip.setText("🖼 " + (name != null ? name : "image"));
                attachedFileChip.setVisibility(View.VISIBLE);
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(requireContext().getContentResolver().openInputStream(uri),
                            StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                attachedFileContent = sb.toString();
                attachedFileChip.setText("📎 " + (name != null ? name : "файл"));
                attachedFileChip.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            toastError("Не смог прочитать файл: " + e.getMessage());
        }
    }

    private boolean isAtBottom() {
        if (messages.isEmpty()) return true;
        LinearLayoutManager lm = (LinearLayoutManager) messageList.getLayoutManager();
        if (lm == null) return true;
        int lastVisible = lm.findLastCompletelyVisibleItemPosition();
        return lastVisible >= messages.size() - 1;
    }

    // ========== Отправка ==========

    private void sendMessage() {
        if (isGenerating) return;
        String text = editMessage.getText().toString().trim();
        if (text.isEmpty()) return;

        String apiKey = AiConfig.getApiKey(requireContext());
        if (apiKey.isEmpty()) {
            toast("Сначала вбей API ключ в настройках");
            return;
        }

        if (!chatDbGateOpen) {
            // truncate после edit/regenerate ещё не закончил — отправим следом
            pendingAfterDbGate = this::sendMessage;
            return;
        }

        hideKeyboard();

        if (currentChatId == -1) {
            new Thread(() -> {
                long newId = chatRepository.createChat("Новый чат", null);
                runOnUi(() -> {
                    currentChatId = newId;
                    proceedSendMessage(text, apiKey);
                });
            }).start();
        } else {
            proceedSendMessage(text, apiKey);
        }
    }

    private void proceedSendMessage(String text, String apiKey) {
        if (isGenerating) return;
        dismissMentionPopup();
        currentUserRawText = text;
        assistantAddedToApi = false;

        // курсор/выделение из редактора → в system prompt (AiConfig + EditorContext)
        Fragment editorFrag = getParentFragmentManager().findFragmentByTag("editor");
        if (editorFrag instanceof EditorFragment) {
            ((EditorFragment) editorFrag).syncEditorStateNow();
        }
        sessionCheckpoint.begin();
        checkpointBarCollapsed = false;
        cancelHideCheckpointBar();
        removeCheckpointCards();
        activeCheckpointMsg = null;
        refreshPendingDiffBar();

        VectrHaptics.send(requireContext());

        String displayText = expandAtMentions(text);
        if (attachedFileContent != null) {
            displayText = "📎 Файл:\n```\n" + attachedFileContent + "\n```\n\n" + displayText;
            attachedFileContent = null;
            attachedFileChip.setVisibility(View.GONE);
        }

        ChatMessage userMsg = new ChatMessage(ChatMessage.Role.USER, displayText);
        currentUserMsg = userMsg;
        adapter.addMessage(userMsg);
        apiMessages.add(userMsg);
        persistMessage(userMsg, messages.size() - 1);
        updateEmptyChatState();

        editMessage.setText("");
        saveDraft(null);

        userScrolling = false;
        scrollToBottom();

        if (messages.size() == 1) {
            String title = text.length() > 40 ? text.substring(0, 40) + "..." : text;
            long chatIdSnapshot = currentChatId;
            new Thread(() -> chatRepository.updateChatTitle(chatIdSnapshot, title)).start();
        }

        ChatMessage assistantMsg = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        assistantMsg.isStreaming = true;
        currentAssistantMsg = assistantMsg;
        adapter.addMessage(assistantMsg);
        scrollToBottom();

        setGeneratingState(true);
        currentTurnCancelled = false;
        forcedFinalAttemptDone = false;
        forcedToolNudgeDone = false;
        performTurn(assistantMsg, apiKey, 0, 1);
    }

    private void setGeneratingState(boolean generating) {
        isGenerating = generating;
        btnSend.setImageResource(generating ? R.drawable.ic_stop : R.drawable.ic_send);

        if (generationWatchdog != null) {
            mainHandler.removeCallbacks(generationWatchdog);
            generationWatchdog = null;
        }
        if (!generating) {
            clearInactivityWatchdog();
            // ход закончен — если правок нет, уводим checkpoint в ленту
            mainHandler.postDelayed(() -> {
                if (!isGenerating && pendingChanges.isEmpty()
                        && sessionCheckpoint.hasSnapshots()
                        && !checkpointBarCollapsed) {
                    scheduleCollapseCheckpointBar();
                }
            }, 600);
            return;
        }
        generationWatchdog = () -> {
            if (!isGenerating) return;
            Log.w("ChatFragment", "watchdog: генерация висит дольше " + GENERATION_WATCHDOG_MS + "мс, глушу принудительно");
            forceStopGeneration("⚠️ Остановлено по таймауту - модель не ответила слишком долго.");
        };
        mainHandler.postDelayed(generationWatchdog, GENERATION_WATCHDOG_MS);
        bumpInactivityWatchdog();
    }

    private void bumpInactivityWatchdog() {
        clearInactivityWatchdog();
        if (!isGenerating) return;
        inactivityWatchdog = () -> {
            if (!isGenerating || currentTurnCancelled) return;
            Log.w("ChatFragment", "inactivity watchdog: нет прогресса " + INACTIVITY_WATCHDOG_MS + "мс");
            forceStopGeneration("⚠️ Соединение зависло - долго нет ответа от модели. Попробуй ещё раз.");
        };
        mainHandler.postDelayed(inactivityWatchdog, INACTIVITY_WATCHDOG_MS);
    }

    private void clearInactivityWatchdog() {
        if (inactivityWatchdog != null) {
            mainHandler.removeCallbacks(inactivityWatchdog);
            inactivityWatchdog = null;
        }
    }

    private void forceStopGeneration(String suffix) {
        currentTurnCancelled = true;
        clearInactivityWatchdog();
        dismissPendingCommandDialog(true);
        AiStreamManager.getInstance().cancelStream();
        clearAllWorking();
        if (currentAssistantMsg != null) {
            String existing = currentAssistantMsg.text == null ? "" : currentAssistantMsg.text;
            currentAssistantMsg.text = existing.isEmpty() ? suffix : existing + "\n\n" + suffix;
            currentAssistantMsg.isStreaming = false;
            updateMsg(currentAssistantMsg);
        }
        currentUserMsg = null;
        currentAssistantMsg = null;
        setGeneratingState(false);
    }

    private void stopGeneration() {
        if (!isGenerating) return;
        currentTurnCancelled = true;
        dismissPendingCommandDialog(true);
        AiStreamManager.getInstance().cancelStream();
        clearAllWorking();

        if (currentAssistantMsg != null) {
            currentAssistantMsg.isStreaming = false;
            int idx = messages.indexOf(currentAssistantMsg);
            if (idx != -1) adapter.notifyItemChanged(idx);
        }
        currentUserMsg = null;
        currentAssistantMsg = null;
        setGeneratingState(false);
    }

    private boolean isRetryableError(String message) {
        if (message == null) return false;
        return message.contains("код 429")
                || message.contains("код 500") || message.contains("код 502")
                || message.contains("код 503") || message.contains("код 504");
    }

    private void retryWithDelay(ChatMessage displayAssistantMsg, String apiKey, int depth, int retryAttempt) {
        int delayMs = Math.min(1000 * retryAttempt, 5000);
        mainHandler.postDelayed(() -> {
            if (currentTurnCancelled) return;
            performTurn(displayAssistantMsg, apiKey, depth, retryAttempt);
        }, delayMs);
    }

    // ========== Стрим ==========

    private void performTurn(ChatMessage displayAssistantMsg, String apiKey, int depth, int retryAttempt) {
        boolean allowTools = depth < MAX_TOOL_DEPTH;
        liveTurnDepth = depth;
        liveRetryAttempt = retryAttempt;
        liveAllowTools = allowTools;
        liveApiKey = apiKey != null ? apiKey : "";

        if (retryAttempt > 1) {
            displayAssistantMsg.text = "";
            displayAssistantMsg.thinking = null;
            updateMsg(displayAssistantMsg);
        }

        // на retry буферы с нуля; на обычном ходе тоже (текст уже в msg или пуст)
        String seedText = retryAttempt > 1 ? "" : null;
        String seedThinking = retryAttempt > 1 ? "" : null;

        AiClient.StreamCallback callback = createStreamCallback(
                displayAssistantMsg, apiKey, depth, retryAttempt, allowTools,
                seedText, seedThinking);

        // запускаем через сервис — даже при сворачивании стрим выживет
        AiStreamManager.getInstance().startStream(
                requireContext(),
                apiKey,
                AiConfig.getModelId(requireContext()),
                AiConfig.getSystemPrompt(requireContext()),
                apiMessages,
                AiConfig.getMaxTokens(requireContext()),
                AiConfig.isThinkingEnabled(requireContext()),
                allowTools,
                callback
        );
    }

    private AiClient.StreamCallback createStreamCallback(ChatMessage displayAssistantMsg,
                                                          String apiKey, int depth, int retryAttempt,
                                                          boolean allowTools,
                                                          @Nullable String seedText,
                                                          @Nullable String seedThinking) {
        return new AiClient.StreamCallback() {
            final StringBuilder textBuffer = new StringBuilder(
                    seedText != null ? seedText : "");
            final StringBuilder thinkingBuffer = new StringBuilder(
                    seedThinking != null ? seedThinking : "");
            boolean toolCallsHandled = false;

            @Override
            public void onChunk(String textDelta) {
                if (currentTurnCancelled) return;
                textBuffer.append(textDelta);
                runOnUi(() -> {
                    if (currentTurnCancelled) return;
                    bumpInactivityWatchdog();
                    displayAssistantMsg.text = stripDsmlLeak(textBuffer.toString());
                    scheduleStreamTextUi(displayAssistantMsg);
                });
            }

            @Override
            public void onThinkingChunk(String thinkingDelta) {
                if (currentTurnCancelled) return;
                thinkingBuffer.append(thinkingDelta);
                runOnUi(() -> {
                    if (currentTurnCancelled) return;
                    bumpInactivityWatchdog();
                    displayAssistantMsg.thinking = thinkingBuffer.toString();
                    scheduleThinkingUi(displayAssistantMsg);
                });
            }

            @Override
            public void onToolCallsReady(List<ToolCallInfo> calls) {
                if (currentTurnCancelled) return;
                toolCallsHandled = true;
                runOnUi(() -> {
                    if (currentTurnCancelled) return;
                    flushPendingStreamUi();
                    bumpInactivityWatchdog();
                    displayAssistantMsg.toolCalls = calls;
                    displayAssistantMsg.isStreaming = false;
                    int idx = messages.indexOf(displayAssistantMsg);
                    if (idx != -1) {
                        adapter.notifyItemChanged(idx);
                        adapter.refreshSiblingToolRows(idx);
                    }
                    scrollToBottom();
                });
                executeToolsAndContinue(displayAssistantMsg, calls,
                        stripDsmlLeak(textBuffer.toString()), apiKey, depth);
            }

            @Override
            public void onComplete() {
                if (currentTurnCancelled) return;

                String cleaned = stripDsmlLeak(textBuffer.toString()).trim();

                // модель пыталась дёрнуть тулзу на глубине, где это уже запрещено (DSML спрятан,
                // не выполнен) - в итоге чистого текста ноль. Даём один принудительный дожим
                // с явным запретом в промпте, вместо пустого ответа юзеру.
                if (!toolCallsHandled && !allowTools && cleaned.isEmpty()
                        && !forcedFinalAttemptDone) {
                    forcedFinalAttemptDone = true;
                    apiMessages.add(new ChatMessage(ChatMessage.Role.USER,
                            "Инструменты сейчас недоступны. Ответь обычным текстом на основе " +
                            "уже собранной информации, не пытайся вызывать какие-либо функции."));
                    runOnUi(() -> performTurn(displayAssistantMsg, apiKey, depth, 1));
                    return;
                }

                // «Сейчас прочитаю файл...» без реального tool_call — дожимаем вместо обрыва
                if (!toolCallsHandled && allowTools && looksLikeToolNarration(cleaned)
                        && !forcedToolNudgeDone) {
                    forcedToolNudgeDone = true;
                    apiMessages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, cleaned));
                    assistantAddedToApi = true;
                    apiMessages.add(new ChatMessage(ChatMessage.Role.USER,
                            "Не описывай будущее действие текстом. Сразу вызови нужные инструменты " +
                            "(read_file / list_files) через function calling. Если файлы уже прочитаны " +
                            "в истории — дай итоговый ответ по фактам из результатов инструментов."));
                    runOnUi(() -> {
                        if (currentTurnCancelled) return;
                        displayAssistantMsg.text = cleaned;
                        updateMsg(displayAssistantMsg);
                        bumpInactivityWatchdog();
                        performTurn(displayAssistantMsg, apiKey, depth, 1);
                    });
                    return;
                }

                if (!toolCallsHandled) {
                    apiMessages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, cleaned));
                    assistantAddedToApi = true;
                }
                runOnUi(() -> {
                    if (currentTurnCancelled) return;
                    flushPendingStreamUi();
                    displayAssistantMsg.text = cleaned;
                    displayAssistantMsg.isStreaming = false;
                    int idx = messages.indexOf(displayAssistantMsg);
                    if (idx != -1) adapter.notifyItemChanged(idx);
                    setGeneratingState(false);
                    VectrHaptics.replyDone(requireContext());
                    if (!userScrolling) scrollToBottom();
                });
                persistMessage(displayAssistantMsg, messages.indexOf(displayAssistantMsg));
            }

            @Override
            public void onError(String message) {
                if (currentTurnCancelled) return;

                if (isRetryableError(message) && retryAttempt < MAX_RETRIES) {
                    runOnUi(() -> {
                        if (currentTurnCancelled) return;
                        displayAssistantMsg.text = "⚠️ Ошибка " + message
                                + "\n\nПовторная попытка " + (retryAttempt + 1) + "/" + MAX_RETRIES + "...";
                        displayAssistantMsg.isStreaming = true;
                        updateMsg(displayAssistantMsg);
                    });
                    retryWithDelay(displayAssistantMsg, apiKey, depth, retryAttempt + 1);
                    return;
                }

                runOnUi(() -> {
                    if (currentTurnCancelled) return;
                    String prefix = retryAttempt > 1
                            ? "⚠️ Не удалось после " + (retryAttempt - 1) + " попыток.\n\n"
                            : "";
                    displayAssistantMsg.text = prefix + "Ошибка: " + message;
                    displayAssistantMsg.isStreaming = false;
                    setGeneratingState(false);
                    adapter.notifyDataSetChanged();
                });
                persistMessage(displayAssistantMsg, messages.indexOf(displayAssistantMsg));
            }
        };
    }

    private void updateMsg(ChatMessage msg) {
        // апдейтим карточку всегда, юзер мог просто читать код выше -
        // это не повод замораживать статус тул-коллов на экране.
        // От скролла вниз юзера защищает отдельная проверка userScrolling в scrollToBottom().
        flushPendingStreamUi();
        int idx = messages.indexOf(msg);
        if (idx == -1) return;
        adapter.notifyItemChanged(idx);
        // схлопнутые Read по ходу — обновить соседние ряды
        adapter.refreshSiblingToolRows(idx);
    }

    /** Thinking стрим: partial update без перезагрузки WebView / tools. */
    private void scheduleThinkingUi(ChatMessage msg) {
        pendingThinkingMsg = msg;
        if (pendingThinkingUi != null) mainHandler.removeCallbacks(pendingThinkingUi);
        pendingThinkingUi = () -> {
            pendingThinkingUi = null;
            ChatMessage m = pendingThinkingMsg;
            pendingThinkingMsg = null;
            if (m == null) return;
            int idx = messages.indexOf(m);
            if (idx == -1) return;
            // merged «Thought · N» живёт на первой карточке хода
            int head = adapter.thoughtHeadIndex(idx);
            adapter.notifyItemChanged(head >= 0 ? head : idx, ChatAdapter.PAYLOAD_THINKING);
            // последующие thinking-only ряды схлопываем полным rebind
            if (head >= 0 && head != idx) {
                adapter.notifyItemChanged(idx);
            }
        };
        mainHandler.postDelayed(pendingThinkingUi, 90);
    }

    /** Текст ответа стримом: throttle + payload, меньше дёрганий. */
    private void scheduleStreamTextUi(ChatMessage msg) {
        pendingStreamTextMsg = msg;
        if (pendingStreamTextUi != null) mainHandler.removeCallbacks(pendingStreamTextUi);
        pendingStreamTextUi = () -> {
            pendingStreamTextUi = null;
            ChatMessage m = pendingStreamTextMsg;
            pendingStreamTextMsg = null;
            if (m == null) return;
            int idx = messages.indexOf(m);
            if (idx == -1) return;
            adapter.notifyItemChanged(idx, ChatAdapter.PAYLOAD_STREAM_TEXT);
            if (!userScrolling && !m.thinkingExpanded) scrollToBottom();
        };
        mainHandler.postDelayed(pendingStreamTextUi, 50);
    }

    private void flushPendingStreamUi() {
        if (pendingThinkingUi != null) {
            mainHandler.removeCallbacks(pendingThinkingUi);
            pendingThinkingUi.run();
        }
        if (pendingStreamTextUi != null) {
            mainHandler.removeCallbacks(pendingStreamTextUi);
            pendingStreamTextUi.run();
        }
    }

    private void executeToolsAndContinue(ChatMessage displayAssistantMsg, List<ToolCallInfo> calls,
                                          String assistantText, String apiKey, int depth) {
        new Thread(() -> {
            List<ToolCallInfo> commandCalls = new ArrayList<>();

            for (ToolCallInfo call : calls) {
                if (currentTurnCancelled) return;

                if ("run_command".equals(call.name)) {
                    commandCalls.add(call);
                    continue;
                }

                try {
                    JSONObject args = new JSONObject(call.argumentsJson == null ? "{}" : call.argumentsJson);

                    if ("write_file".equals(call.name)
                            || "search_replace".equals(call.name)
                            || "apply_patch".equals(call.name)) {
                        String path = args.getString("path");
                        call.targetFile = path;
                        runOnUi(() -> showWorking(path));

                        FileToolExecutor.WriteDiffResult diffResult;
                        if ("search_replace".equals(call.name)) {
                            boolean all = "true".equalsIgnoreCase(args.optString("replace_all", "false"));
                            diffResult = toolExecutor.searchReplaceDetailed(
                                    path,
                                    args.getString("old_string"),
                                    args.getString("new_string"),
                                    all);
                        } else if ("apply_patch".equals(call.name)) {
                            diffResult = toolExecutor.applyPatchDetailed(path, args.getString("patch"));
                        } else {
                            diffResult = toolExecutor.writeFileDetailed(path, args.getString("content"));
                        }

                        call.result = diffResult.summary;
                        call.oldContent = diffResult.oldContent;
                        call.newContent = diffResult.newContent;
                        final boolean failed = diffResult.summary != null
                                && diffResult.summary.startsWith("ОШИБКА");
                        if (failed) {
                            call.diffAdded = -1;
                            call.diffRemoved = -1;
                        } else {
                            call.diffAdded = diffResult.added;
                            call.diffRemoved = diffResult.removed;
                        }
                        runOnUi(() -> {
                            hideWorking(path);
                            if (!failed && diffResult.newContent != null
                                    && !diffResult.newContent.equals(diffResult.oldContent)) {
                                enqueuePendingChange(new PendingFileChange(
                                        path,
                                        diffResult.oldContent,
                                        diffResult.newContent,
                                        diffResult.added,
                                        diffResult.removed));
                            } else {
                                updateMsg(displayAssistantMsg);
                            }
                        });
                    } else {
                        if ("web_search".equals(call.name)) {
                            String q = args.optString("query", "search");
                            runOnUi(() -> showWorking(q));
                            call.result = toolExecutor.execute(call.name, args);
                            runOnUi(() -> hideWorking(q));
                        } else if ("fetch_url".equals(call.name)) {
                            // тихо: страница только для модели, без working-баннера
                            call.result = toolExecutor.execute(call.name, args);
                        } else {
                            call.result = toolExecutor.execute(call.name, args);
                        }
                    }
                } catch (Exception e) {
                    call.result = "ОШИБКА: не смог разобрать аргументы - " + e.getMessage();
                    if (("write_file".equals(call.name)
                            || "search_replace".equals(call.name)
                            || "apply_patch".equals(call.name))
                            && call.targetFile != null) {
                        String errPath = call.targetFile;
                        runOnUi(() -> hideWorking(errPath));
                    }
                }
                call.done = true;
                runOnUi(() -> updateMsg(displayAssistantMsg));
            }

            if (currentTurnCancelled) return;

            if (!commandCalls.isEmpty()) {
                runOnUi(() -> handleCommandApprovals(displayAssistantMsg, commandCalls,
                        assistantText, apiKey, depth, calls));
                return;
            }

            continueToolTurn(displayAssistantMsg, assistantText, apiKey, depth, calls);
        }).start();
    }

    /** Whitelist → автозапуск; иначе sheet или нотиф. */
    private void handleCommandApprovals(ChatMessage displayAssistantMsg,
                                         List<ToolCallInfo> commandCalls,
                                         String assistantText, String apiKey, int depth,
                                         List<ToolCallInfo> allCalls) {
        if (currentTurnCancelled || getContext() == null) return;

        List<ToolCallInfo> trusted = new ArrayList<>();
        List<ToolCallInfo> needConfirm = new ArrayList<>();
        for (ToolCallInfo c : commandCalls) {
            String cmd = commandOf(c);
            if (cmd.isEmpty()) {
                c.result = "ОШИБКА: пустая команда";
                c.done = true;
            } else if (AgentTrust.isCommandTrusted(cmd)) {
                trusted.add(c);
            } else {
                needConfirm.add(c);
            }
        }

        if (!trusted.isEmpty() && needConfirm.isEmpty()) {
            executeCommandChoice(displayAssistantMsg, trusted,
                    assistantText, apiKey, depth, allCalls, AgentTrust.getAutoRunner());
            return;
        }

        if (!trusted.isEmpty()) {
            new Thread(() -> {
                runCommandBatch(trusted, AgentTrust.getAutoRunner());
                runOnUi(() -> {
                    updateMsg(displayAssistantMsg);
                    if (currentTurnCancelled) return;
                    requestCommandConfirmation(displayAssistantMsg, needConfirm,
                            assistantText, apiKey, depth, allCalls);
                });
            }).start();
            return;
        }

        requestCommandConfirmation(displayAssistantMsg, needConfirm,
                assistantText, apiKey, depth, allCalls);
    }

    private static String commandOf(ToolCallInfo c) {
        try {
            JSONObject args = new JSONObject(c.argumentsJson == null ? "{}" : c.argumentsJson);
            return args.optString("command", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void runCommandBatch(List<ToolCallInfo> commandCalls, String mode) {
        for (ToolCallInfo c : commandCalls) {
            if (currentTurnCancelled) {
                if (!c.done) {
                    c.result = "Отменено пользователем";
                    c.done = true;
                }
                continue;
            }
            try {
                String command = commandOf(c);
                if (command.isEmpty()) {
                    c.result = "ОШИБКА: пустая команда";
                } else if ("termux".equals(mode)) {
                    c.result = toolExecutor.runTermuxCommand(command);
                } else {
                    c.result = toolExecutor.runLocalCommand(command);
                }
            } catch (Exception e) {
                c.result = "ОШИБКА: " + e.getMessage();
            }
            c.done = true;
        }
    }

    private void requestCommandConfirmation(ChatMessage displayAssistantMsg,
                                             List<ToolCallInfo> commandCalls,
                                             String assistantText, String apiKey, int depth,
                                             List<ToolCallInfo> allCalls) {
        if (commandCalls == null || commandCalls.isEmpty()) {
            continueToolTurn(displayAssistantMsg, assistantText, apiKey, depth, allCalls);
            return;
        }
        if (!AiStreamManager.getInstance().isAppInForeground()) {
            stashPendingCommandSession(displayAssistantMsg, commandCalls,
                    assistantText, apiKey, depth, allCalls);
            StringBuilder body = new StringBuilder();
            for (ToolCallInfo c : commandCalls) {
                String cmd = commandOf(c);
                if (cmd.isEmpty()) continue;
                if (body.length() > 0) body.append('\n');
                body.append("$ ").append(cmd);
            }
            String reqId = pendingCmdRequestId;
            ApprovalBus.get().setPendingCommandRequestId(reqId);
            AiStreamService.postCommandApproval(requireContext(), reqId,
                    getString(R.string.notif_cmd_title), body.toString());
            return;
        }
        showCommandConfirmation(displayAssistantMsg, commandCalls,
                assistantText, apiKey, depth, allCalls);
    }

    private void stashPendingCommandSession(ChatMessage displayAssistantMsg,
                                             List<ToolCallInfo> commandCalls,
                                             String assistantText, String apiKey, int depth,
                                             List<ToolCallInfo> allCalls) {
        pendingCmdRequestId = "cmd-" + System.currentTimeMillis();
        pendingCmdDisplayMsg = displayAssistantMsg;
        pendingCmdNeedConfirm = commandCalls;
        pendingCmdAllCalls = allCalls;
        pendingCmdAssistantText = assistantText;
        pendingCmdApiKey = apiKey;
        pendingCmdDepth = depth;
        pendingCommandCalls = commandCalls;
    }

    private void clearPendingCommandSession() {
        if (pendingCmdRequestId != null && getContext() != null) {
            AiStreamService.cancelCommandApproval(requireContext(), pendingCmdRequestId);
        }
        ApprovalBus.get().clearPendingCommandRequestId(pendingCmdRequestId);
        pendingCmdRequestId = null;
        pendingCmdDisplayMsg = null;
        pendingCmdNeedConfirm = null;
        pendingCmdAllCalls = null;
        pendingCmdAssistantText = null;
        pendingCmdApiKey = null;
        pendingCmdDepth = 0;
        pendingCommandCalls = null;
    }

    private void handleNotifCommandApproved(String requestId) {
        if (requestId == null || !requestId.equals(pendingCmdRequestId)) return;
        if (currentTurnCancelled) {
            clearPendingCommandSession();
            return;
        }
        ChatMessage msg = pendingCmdDisplayMsg;
        List<ToolCallInfo> cmds = pendingCmdNeedConfirm;
        List<ToolCallInfo> all = pendingCmdAllCalls;
        String text = pendingCmdAssistantText;
        String key = pendingCmdApiKey;
        int depth = pendingCmdDepth;
        clearPendingCommandSession();
        if (msg == null || cmds == null || all == null) return;
        dismissPendingCommandDialog(false);
        executeCommandChoice(msg, cmds, text, key, depth, all, AgentTrust.getAutoRunner());
    }

    private void handleNotifCommandRejected(String requestId) {
        if (requestId == null || !requestId.equals(pendingCmdRequestId)) return;
        ChatMessage msg = pendingCmdDisplayMsg;
        List<ToolCallInfo> cmds = pendingCmdNeedConfirm;
        List<ToolCallInfo> all = pendingCmdAllCalls;
        String text = pendingCmdAssistantText;
        String key = pendingCmdApiKey;
        int depth = pendingCmdDepth;
        clearPendingCommandSession();
        if (msg == null || cmds == null || all == null) return;
        dismissPendingCommandDialog(false);
        if (currentTurnCancelled) return;
        for (ToolCallInfo c : cmds) {
            if (!c.done) {
                c.result = "Отменено пользователем";
                c.done = true;
            }
        }
        updateMsg(msg);
        continueToolTurn(msg, text, key, depth, all);
    }

    private void continueToolTurn(ChatMessage displayAssistantMsg, String assistantText,
                                   String apiKey, int depth, List<ToolCallInfo> calls) {
        // до мутации apiMessages — иначе Stop на диалоге Termux оставляет грязную историю
        if (currentTurnCancelled) return;

        persistMessage(displayAssistantMsg, messages.indexOf(displayAssistantMsg));
        ChatMessage assistantApiMsg = new ChatMessage(ChatMessage.Role.ASSISTANT, assistantText);
        assistantApiMsg.toolCalls = calls;
        apiMessages.add(assistantApiMsg);

        for (ToolCallInfo call : calls) {
            ChatMessage toolMsg = new ChatMessage(ChatMessage.Role.TOOL, call.result);
            toolMsg.toolCallId = call.id;
            apiMessages.add(toolMsg);
        }
        assistantAddedToApi = true;

        runOnUi(() -> {
            if (currentTurnCancelled) return;
            bumpInactivityWatchdog();
            ChatMessage nextMsg = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
            nextMsg.isStreaming = true;
            currentAssistantMsg = nextMsg;
            messages.add(nextMsg);
            adapter.notifyItemInserted(messages.size() - 1);
            if (!userScrolling) scrollToBottom();
            performTurn(nextMsg, apiKey, depth + 1, 1);
        });
    }

    private void showCommandConfirmation(ChatMessage displayAssistantMsg,
                                          List<ToolCallInfo> commandCalls,
                                          String assistantText, String apiKey, int depth,
                                          List<ToolCallInfo> allCalls) {
        if (commandCalls.isEmpty() || getContext() == null) return;
        if (currentTurnCancelled) return;

        try {
            StringBuilder body = new StringBuilder();
            StringBuilder descAll = new StringBuilder();
            int valid = 0;
            for (int i = 0; i < commandCalls.size(); i++) {
                JSONObject args = new JSONObject(
                        commandCalls.get(i).argumentsJson == null ? "{}"
                                : commandCalls.get(i).argumentsJson);
                String command = args.optString("command", "");
                String desc = args.optString("description", "");
                if (command.isEmpty()) {
                    commandCalls.get(i).result = "ОШИБКА: пустая команда";
                    commandCalls.get(i).done = true;
                    continue;
                }
                if (valid > 0) body.append("\n\n");
                body.append("$ ").append(command);
                if (!desc.isEmpty()) {
                    if (descAll.length() > 0) descAll.append(" · ");
                    descAll.append(desc);
                }
                valid++;
            }

            if (valid == 0) {
                updateMsg(displayAssistantMsg);
                continueToolTurn(displayAssistantMsg, assistantText, apiKey, depth, allCalls);
                return;
            }

            stashPendingCommandSession(displayAssistantMsg, commandCalls,
                    assistantText, apiKey, depth, allCalls);
            pendingCommandCalls = commandCalls;
            com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                    new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
            View content = LayoutInflater.from(requireContext())
                    .inflate(R.layout.dialog_run_command, null, false);
            sheet.setContentView(content);
            sheet.setCancelable(false);
            sheet.setCanceledOnTouchOutside(false);

            TextView title = content.findViewById(R.id.run_cmd_title);
            TextView descView = content.findViewById(R.id.run_cmd_desc);
            TextView bodyView = content.findViewById(R.id.run_cmd_body);
            TextView cwdView = content.findViewById(R.id.run_cmd_cwd);
            View btnTermux = content.findViewById(R.id.btn_run_termux);
            View btnShell = content.findViewById(R.id.btn_run_shell);
            View btnCancel = content.findViewById(R.id.btn_run_cancel);
            com.google.android.material.switchmaterial.SwitchMaterial alwaysSwitch =
                    content.findViewById(R.id.run_cmd_always_switch);
            TextView alwaysLabel = content.findViewById(R.id.run_cmd_always_label);
            View alwaysRow = content.findViewById(R.id.run_cmd_always_row);

            title.setText(valid == 1
                    ? getString(R.string.run_cmd_title)
                    : getString(R.string.run_cmd_title_many, valid));
            if (descAll.length() > 0) {
                descView.setVisibility(View.VISIBLE);
                descView.setText(descAll.toString());
            } else {
                descView.setVisibility(View.GONE);
            }
            bodyView.setText(body.toString());

            String cwdHint = toolExecutor != null ? toolExecutor.getProjectCwdHint() : null;
            if (cwdHint != null && !cwdHint.isEmpty()) {
                cwdView.setVisibility(View.VISIBLE);
                cwdView.setText(getString(R.string.run_cmd_cwd, cwdHint));
            } else {
                cwdView.setVisibility(View.GONE);
            }

            java.util.LinkedHashSet<String> bins = new java.util.LinkedHashSet<>();
            for (ToolCallInfo c : commandCalls) {
                String b = AgentTrust.extractBinary(commandOf(c));
                if (!b.isEmpty()) bins.add(b);
            }
            if (alwaysRow != null && alwaysSwitch != null && alwaysLabel != null) {
                if (bins.isEmpty()) {
                    alwaysRow.setVisibility(View.GONE);
                } else {
                    alwaysRow.setVisibility(View.VISIBLE);
                    alwaysSwitch.setChecked(false);
                    if (bins.size() == 1) {
                        alwaysLabel.setText(getString(R.string.run_cmd_always_allow,
                                bins.iterator().next()));
                    } else {
                        alwaysLabel.setText(getString(R.string.run_cmd_always_allow_multi));
                    }
                }
            }

            Runnable clearPending = () -> {
                pendingCommandDialog = null;
                pendingCommandCalls = null;
            };

            View.OnClickListener runWith = v -> {
                String mode = (v == btnTermux) ? "termux" : "shell";
                boolean always = alwaysSwitch != null && alwaysSwitch.isChecked();
                clearPending.run();
                sheet.dismiss();
                clearPendingCommandSession();
                if (currentTurnCancelled) return;
                if (always) {
                    for (String b : bins) AgentTrust.addBinary(b);
                    AgentTrust.setAutoRunner(mode);
                }
                executeCommandChoice(displayAssistantMsg, commandCalls,
                        assistantText, apiKey, depth, allCalls, mode);
            };
            btnTermux.setOnClickListener(runWith);
            btnShell.setOnClickListener(runWith);
            btnCancel.setOnClickListener(v -> {
                clearPending.run();
                sheet.dismiss();
                clearPendingCommandSession();
                if (currentTurnCancelled) return;
                for (ToolCallInfo c : commandCalls) {
                    if (!c.done) {
                        c.result = "Отменено пользователем";
                        c.done = true;
                    }
                }
                updateMsg(displayAssistantMsg);
                continueToolTurn(displayAssistantMsg, assistantText, apiKey, depth, allCalls);
            });

            pendingCommandDialog = sheet;
            sheet.show();
        } catch (Exception e) {
            pendingCommandDialog = null;
            pendingCommandCalls = null;
            clearPendingCommandSession();
            for (ToolCallInfo c : commandCalls) {
                c.result = "ОШИБКА разбора команды: " + e.getMessage();
                c.done = true;
            }
            updateMsg(displayAssistantMsg);
            continueToolTurn(displayAssistantMsg, assistantText, apiKey, depth, allCalls);
        }
    }

    private void executeCommandChoice(ChatMessage displayAssistantMsg,
                                       List<ToolCallInfo> commandCalls,
                                       String assistantText, String apiKey, int depth,
                                       List<ToolCallInfo> allCalls, String mode) {
        if (currentTurnCancelled) return;
        new Thread(() -> {
            runCommandBatch(commandCalls, mode);
            runOnUi(() -> {
                updateMsg(displayAssistantMsg);
                if (currentTurnCancelled) return;
                continueToolTurn(displayAssistantMsg, assistantText, apiKey, depth, allCalls);
            });
        }).start();
    }

    private void persistMessage(ChatMessage msg, int order) {
        if (currentChatId == -1) return;
        new Thread(() -> chatRepository.saveMessage(currentChatId, msg, order)).start();
    }

    private void runOnUi(Runnable r) {
        if (getActivity() != null) getActivity().runOnUiThread(r);
    }

    private void scrollToBottom() {
        if (messages.isEmpty()) return;
        if (userScrolling) return;
        messageList.scrollToPosition(messages.size() - 1);
    }

    /** Убирает сырой DSML, если он всё же просочился в видимый текст. */
    private static String stripDsmlLeak(String text) {
        if (text == null || text.isEmpty()) return text;
        String lower = text.toLowerCase();
        if (!lower.contains("dsml") && !lower.contains("tool_calls")
                && !lower.contains("invoke name=") && !lower.contains("invoke name =")) {
            return text;
        }
        int cut = -1;
        String[] marks = {
                "< | dsml", "<|dsml", "|dsml|", "| dsml |",
                "<|dsml|tool_calls", "invoke name=\"", "invoke name ="
        };
        for (String m : marks) {
            int i = lower.indexOf(m);
            if (i >= 0 && (cut < 0 || i < cut)) cut = i;
        }
        if (cut < 0) return text;
        return text.substring(0, cut).trim();
    }

    /** Модель анонсирует вызов тулзов текстом вместо function calling. */
    private static boolean looksLikeToolNarration(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase(Locale.getDefault());
        boolean announces = t.contains("прочитаю") || t.contains("прочт")
                || t.contains("проверю") || t.contains("посмотрю")
                || t.contains("открою файл") || t.contains("давайте проверим")
                || t.contains("сейчас прочитаю") || t.contains("прочитать два")
                || t.contains("i'll read") || t.contains("let me read")
                || t.contains("let me check") || t.contains("going to read");
        if (!announces) return false;
        // длинный развёрнутый ответ с выводом — не трогаем
        if (text.length() > 600) return false;
        boolean hasConclusion = t.contains("итог") || t.contains("вывод:")
                || t.contains("в итоге") || t.contains("таким образом")
                || t.contains("deepseek-v4-pro") || t.contains("deepseek-v4-flash");
        return !hasConclusion;
    }
}
