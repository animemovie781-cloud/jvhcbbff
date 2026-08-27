package genius.DMTech.Vectr;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatHistoryBottomSheet extends BottomSheetDialogFragment {

    public interface OnChatSelectedListener {
        void onChatSelected(long chatId);
    }

    public interface OnChatDeleteListener {
        void onChatDeleted(long chatId);
    }

    private List<ChatRepository.ChatSummary> allChats = new ArrayList<>();
    private List<ChatRepository.ChatSummary> filteredChats = new ArrayList<>();
    private OnChatSelectedListener listener;
    private OnChatDeleteListener deleteListener;

    private HistoryAdapter adapter;
    private RecyclerView list;
    private EditText searchInput;
    private ImageButton btnClearSearch;
    private LinearLayout emptyState;
    private TextView chatCount;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ChatHistoryBottomSheet() {}

    public void setChats(List<ChatRepository.ChatSummary> chats) {
        this.allChats = chats != null ? chats : new ArrayList<>();
        this.filteredChats = new ArrayList<>(this.allChats);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            updateEmptyState();
            updateCount();
        }
    }

    public void setOnChatSelectedListener(OnChatSelectedListener listener) {
        this.listener = listener;
    }

    public void setOnChatDeleteListener(OnChatDeleteListener deleteListener) {
        this.deleteListener = deleteListener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_chat_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        list = view.findViewById(R.id.chat_history_list);
        searchInput = view.findViewById(R.id.search_input);
        btnClearSearch = view.findViewById(R.id.btn_clear_search);
        emptyState = view.findViewById(R.id.empty_state);
        chatCount = view.findViewById(R.id.chat_count);

        adapter = new HistoryAdapter(filteredChats);
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        list.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString());
                btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

        btnClearSearch.setOnClickListener(v -> searchInput.setText(""));

        updateEmptyState();
        updateCount();
    }

    private void filter(String query) {
        filteredChats.clear();
        if (query.isEmpty()) {
            filteredChats.addAll(allChats);
        } else {
            String lower = query.toLowerCase(Locale.ROOT).trim();
            for (ChatRepository.ChatSummary chat : allChats) {
                String title = chat.title != null ? chat.title.toLowerCase(Locale.ROOT) : "";
                String preview = chat.lastPreview != null ? chat.lastPreview.toLowerCase(Locale.ROOT) : "";
                if (title.contains(lower) || preview.contains(lower)) {
                    filteredChats.add(chat);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = filteredChats.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateCount() {
        if (chatCount != null) chatCount.setText(String.valueOf(allChats.size()));
    }

    private void deleteChatWithConfirmation(ChatRepository.ChatSummary chat) {
        String title = chat.title != null && !chat.title.isEmpty() ? chat.title : "Новый чат";
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Удалить чат?")
                .setMessage("«" + title + "» будет удалён навсегда.")
                .setPositiveButton("Удалить", (d, w) -> {
                    final ChatRepository.ChatSummary toDelete = chat;
                    final android.content.Context appCtx = requireContext().getApplicationContext();
                    new Thread(() -> {
                        try {
                            new ChatRepository(appCtx).deleteChat(toDelete.id);
                            mainHandler.post(() -> {
                                if (!isAdded()) return;
                                allChats.remove(toDelete);
                                filteredChats.remove(toDelete);
                                adapter.notifyDataSetChanged();
                                updateEmptyState();
                                updateCount();
                                if (deleteListener != null) {
                                    deleteListener.onChatDeleted(toDelete.id);
                                }
                                Toast.makeText(getContext(), "Чат удалён", Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(getContext(),
                                        "Не удалось удалить: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            });
                        }
                    }).start();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void selectChatAndDismiss(long chatId) {
        // Сначала дёргаем лисенер (пока фрагмент ещё жив), потом закрываем боттомшит.
        // isAdded() после dismiss почти всегда уже false — раньше это молча
        // проглатывало выбор чата.
        if (listener != null) {
            listener.onChatSelected(chatId);
        }
        dismissAllowingStateLoss();
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {

        private final List<ChatRepository.ChatSummary> items;

        HistoryAdapter(List<ChatRepository.ChatSummary> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_history, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ChatRepository.ChatSummary chat = items.get(position);

            // Заголовок
            holder.title.setText(chat.title != null && !chat.title.isEmpty() ? chat.title : "Новый чат");

            // Превью
            if (chat.lastPreview != null && !chat.lastPreview.isEmpty()) {
                holder.preview.setVisibility(View.VISIBLE);
                String preview = chat.lastPreview.length() > 80
                        ? chat.lastPreview.substring(0, 80) + "..."
                        : chat.lastPreview;
                holder.preview.setText(preview);
            } else {
                holder.preview.setVisibility(View.GONE);
            }

            // Дата
            if (chat.updatedAt > 0) {
                holder.date.setVisibility(View.VISIBLE);
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault());
                holder.date.setText(sdf.format(new Date(chat.updatedAt)));
            } else {
                holder.date.setVisibility(View.GONE);
            }

            // Клик на карточку — переключение чата
            holder.card.setOnClickListener(v -> selectChatAndDismiss(chat.id));

            // Клик на кнопку удаления
            holder.btnDelete.setOnClickListener(v -> deleteChatWithConfirmation(chat));

            // Долгий тап — тоже удаление
            holder.itemView.setOnLongClickListener(v -> {
                deleteChatWithConfirmation(chat);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            CardView card;
            TextView title, preview, date;
            ImageButton btnDelete;

            Holder(@NonNull View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.card);
                title = itemView.findViewById(R.id.chat_title);
                preview = itemView.findViewById(R.id.chat_preview);
                date = itemView.findViewById(R.id.chat_date);
                btnDelete = itemView.findViewById(R.id.btn_delete_chat);
            }
        }
    }
}
