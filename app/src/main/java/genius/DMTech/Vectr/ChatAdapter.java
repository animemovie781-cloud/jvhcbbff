package genius.DMTech.Vectr;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.MotionEvent;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    public static final String PAYLOAD_THINKING = "thinking";
    public static final String PAYLOAD_STREAM_TEXT = "stream_text";
    public static final String PAYLOAD_TOOLS = "tools";

    public interface OnDiffClickListener {
        void onDiffClick(ToolCallInfo call);
    }

    public interface OnActionListener {
        void onCopyMessage(ChatMessage message);
        void onRefreshMessage(ChatMessage message);
    }

    public interface OnFilePathClickListener {
        void onFilePathClick(String relativePath);
    }

    public interface OnUserMessageListener {
        void onUserMessageLongPress(TextView textView, ChatMessage message);
    }

    public interface OnCheckpointClickListener {
        void onCheckpointClick(ChatMessage message);
    }

    private List<ChatMessage> messages;
    private OnDiffClickListener diffClickListener;
    private OnActionListener actionListener;
    private OnFilePathClickListener filePathClickListener;
    private OnUserMessageListener userMessageListener;
    private OnCheckpointClickListener checkpointClickListener;

    public ChatAdapter(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public void setOnDiffClickListener(OnDiffClickListener listener) {
        this.diffClickListener = listener;
    }

    public void setOnActionListener(OnActionListener listener) {
        this.actionListener = listener;
    }

    public void setOnFilePathClickListener(OnFilePathClickListener listener) {
        this.filePathClickListener = listener;
    }

    public void setOnUserMessageListener(OnUserMessageListener listener) {
        this.userMessageListener = listener;
    }

    public void setOnCheckpointClickListener(OnCheckpointClickListener listener) {
        this.checkpointClickListener = listener;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onViewRecycled(@NonNull MessageViewHolder holder) {
        stopShimmer(holder);
        if (holder.toolCallsContainer != null) {
            stopShimmerOnContainer(holder.toolCallsContainer);
        }
        if (holder.messageWebView != null) {
            try {
                holder.messageWebView.stopLoading();
                holder.messageWebView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null);
            } catch (Exception ignored) {}
        }
        super.onViewRecycled(holder);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        ChatMessage msg = messages.get(position);
        for (Object payload : payloads) {
            if (PAYLOAD_THINKING.equals(payload)) {
                bindThinking(holder, msg, position);
            } else if (PAYLOAD_STREAM_TEXT.equals(payload)) {
                bindAssistantStreamText(holder, msg, position);
            } else if (PAYLOAD_TOOLS.equals(payload)) {
                renderToolCalls(holder, msg, position);
            } else {
                onBindViewHolder(holder, position);
                return;
            }
        }
    }

    /** Индекс первой Thinking-карточки в текущем ходе (для merged «Thought · N»). */
    public int thoughtHeadIndex(int position) {
        return thoughtSpan(position)[0];
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);

        // восстановить высоту после collapse (merged thinking без своего UI)
        ViewGroup.LayoutParams rootLp = holder.itemView.getLayoutParams();
        if (rootLp != null && rootLp.height == 0) {
            rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            holder.itemView.setLayoutParams(rootLp);
        }
        holder.itemView.setPadding(
                holder.itemView.getPaddingLeft(),
                dp(holder.itemView.getContext(), 4),
                holder.itemView.getPaddingRight(),
                dp(holder.itemView.getContext(), 4));

        if (msg.isWorkingMessage) {
            holder.workingContainer.setVisibility(View.VISIBLE);
            holder.bubbleContainer.setVisibility(View.INVISIBLE);
            holder.toolCallsContainer.setVisibility(View.GONE);
            holder.actionButtons.setVisibility(View.GONE);
            holder.sourcesGroup.setVisibility(View.GONE);
            holder.thinkingBlock.setVisibility(View.GONE);

            holder.workingText.setText(holder.itemView.getContext()
                    .getString(R.string.chat_working, msg.workingFileName));
            startShimmer(holder);
            return;
        } else if (msg.isCheckpointMessage) {
            if (holder.workingContainer.getVisibility() == View.VISIBLE) {
                holder.workingContainer.setVisibility(View.GONE);
                stopShimmer(holder);
            }
            holder.thinkingBlock.setVisibility(View.GONE);
            holder.toolCallsContainer.setVisibility(View.GONE);
            holder.actionButtons.setVisibility(View.GONE);
            holder.sourcesGroup.setVisibility(View.GONE);
            holder.messageWebView.setVisibility(View.GONE);
            holder.bubbleContainer.setVisibility(View.VISIBLE);
            holder.messageText.setVisibility(View.VISIBLE);

            ViewGroup.LayoutParams rawBubble = holder.bubbleContainer.getLayoutParams();
            if (rawBubble instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams bubbleLp = (LinearLayout.LayoutParams) rawBubble;
                bubbleLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                bubbleLp.gravity = Gravity.CENTER_HORIZONTAL;
                holder.bubbleContainer.setLayoutParams(bubbleLp);
            }

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.messageText.getLayoutParams();
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            holder.messageText.setLayoutParams(params);
            holder.messageText.setBackgroundResource(R.drawable.bg_spinner_field);
            holder.messageText.setTextColor(holder.itemView.getContext().getColor(R.color.warning_orange));
            holder.messageText.setGravity(Gravity.CENTER);
            holder.messageText.setTextSize(12f);
            int n = msg.checkpointFileCount;
            holder.messageText.setText(holder.itemView.getContext()
                    .getString(R.string.checkpoint_tap, n));
            holder.messageTime.setVisibility(View.GONE);
            holder.messageText.setOnLongClickListener(null);
            holder.bubbleContainer.setOnLongClickListener(null);
            View.OnClickListener open = v -> {
                if (checkpointClickListener != null) checkpointClickListener.onCheckpointClick(msg);
            };
            holder.messageText.setOnClickListener(open);
            holder.bubbleContainer.setOnClickListener(open);
            return;
        } else {
            if (holder.workingContainer.getVisibility() == View.VISIBLE) {
                holder.workingContainer.setVisibility(View.GONE);
                stopShimmer(holder);
            }
            holder.messageTime.setVisibility(View.VISIBLE);
            holder.messageText.setOnClickListener(null);
            holder.bubbleContainer.setOnClickListener(null);
            holder.messageText.setGravity(Gravity.START);
            holder.messageText.setTextSize(15f);
        }

        holder.bubbleContainer.setVisibility(View.VISIBLE);

        if (msg.role == ChatMessage.Role.USER) {
            holder.messageText.setVisibility(View.VISIBLE);
            holder.messageWebView.setVisibility(View.GONE);
            holder.messageText.setText(msg.text);

            ViewGroup.LayoutParams rawBubble = holder.bubbleContainer.getLayoutParams();
            if (rawBubble instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams bubbleLp = (LinearLayout.LayoutParams) rawBubble;
                bubbleLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                bubbleLp.gravity = Gravity.END;
                holder.bubbleContainer.setLayoutParams(bubbleLp);
            }

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.messageText.getLayoutParams();
            params.gravity = Gravity.END;
            holder.messageText.setBackgroundResource(R.drawable.bg_bubble_user);
            holder.messageText.setTextColor(holder.messageText.getContext().getColor(R.color.bubble_user_text));
            holder.messageText.setLayoutParams(params);

            holder.messageTime.setText(formatTime(msg.timestamp));
            holder.messageTime.setTextColor(holder.itemView.getContext().getColor(R.color.bubble_user_text));
            ((FrameLayout.LayoutParams) holder.messageTime.getLayoutParams()).gravity =
                    Gravity.BOTTOM | Gravity.END;

            holder.actionButtons.setVisibility(View.GONE);

            View.OnLongClickListener userLong = v -> {
                if (userMessageListener != null) {
                    userMessageListener.onUserMessageLongPress(holder.messageText, msg);
                    return true;
                }
                return false;
            };
            holder.messageText.setOnLongClickListener(userLong);
            holder.bubbleContainer.setOnLongClickListener(userLong);

        } else {
            holder.messageText.setOnLongClickListener(null);
            holder.bubbleContainer.setOnLongClickListener(null);

            boolean hasText = msg.text != null && !msg.text.trim().isEmpty();
            // пустой пузырь при одном Thinking/стриме без текста — шум и timestamp «на мысли»
            boolean showBubble = hasText;
            holder.bubbleContainer.setVisibility(showBubble ? View.VISIBLE : View.GONE);
            holder.messageTime.setVisibility(showBubble ? View.VISIBLE : View.GONE);
            holder.messageText.setVisibility(View.GONE);
            holder.messageWebView.setVisibility(showBubble ? View.VISIBLE : View.GONE);

            if (showBubble) {
            ViewGroup.LayoutParams rawBubble = holder.bubbleContainer.getLayoutParams();
            if (rawBubble instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams bubbleLp = (LinearLayout.LayoutParams) rawBubble;
                bubbleLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                bubbleLp.gravity = Gravity.START;
                holder.bubbleContainer.setLayoutParams(bubbleLp);
            }

            FrameLayout.LayoutParams wvParams = (FrameLayout.LayoutParams) holder.messageWebView.getLayoutParams();
            wvParams.gravity = Gravity.START;
            wvParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            wvParams.setMargins(0, 0, 0, 0);
            holder.messageWebView.setLayoutParams(wvParams);

            String html;
            android.content.Context ctx = holder.itemView.getContext();
            ChatHtmlTheme theme = ChatHtmlTheme.from(ctx);
            if (hasText) {
                html = MarkdownParser.toHtml(msg.text,
                        ctx.getString(R.string.chat_copy), theme);
            } else {
                html = "";
            }

            // не дёргаем WebView на каждый чанк/thinking, если текст тот же
            String htmlKey = msg.text != null ? msg.text : "";
            if (!htmlKey.equals(holder.lastBoundText)) {
                holder.lastBoundText = htmlKey;
                String jsCode = "" +
                        "function copyCode(el) {" +
                        "  var wrapper = el.closest('div');" +
                        "  var pre = wrapper.nextElementSibling;" +
                        "  if (!pre) pre = wrapper.parentElement.querySelector('pre');" +
                        "  var code = pre ? pre.textContent : '';" +
                        "  Android.copyToClipboard(code);" +
                        "}";

                String wrapped = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                        + "<style>" + theme.bodyCss() + " body{padding-bottom:22px;}</style>"
                        + "<script>" + jsCode + "</script>"
                        + "</head><body>" + html + "</body></html>";

                holder.messageWebView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    holder.messageWebView.setForceDarkAllowed(false);
                }
                holder.messageWebView.loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null);
            }

            holder.messageTime.setText(formatTime(msg.timestamp));
            holder.messageTime.setTextColor(holder.itemView.getContext().getColor(R.color.text_tertiary));
            holder.messageTime.setBackgroundColor(0x00000000);
            FrameLayout.LayoutParams timeLp =
                    (FrameLayout.LayoutParams) holder.messageTime.getLayoutParams();
            timeLp.gravity = Gravity.BOTTOM | Gravity.END;
            timeLp.setMargins(0, 0, 0, 0);
            holder.messageTime.setLayoutParams(timeLp);
            holder.messageTime.bringToFront();
            } else {
                holder.lastBoundText = null;
            }

            boolean showActions = !msg.isStreaming
                    && hasText
                    && isLastAssistantMessage(position);
            holder.actionButtons.setVisibility(showActions ? View.VISIBLE : View.GONE);

            if (showActions) {
                holder.btnCopy.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onCopyMessage(msg);
                });
                holder.btnRefresh.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onRefreshMessage(msg);
                });
            }
        }

        bindThinking(holder, msg, position);

        // последующие Thinking в том же ходе уже в «Thought · N» — прячем пустой ряд
        if (collapseMergedThoughtRow(holder, msg, position)) {
            return;
        }

        renderToolCalls(holder, msg, position);

        holder.sourcesGroup.removeAllViews();
        if (msg.sources != null && !msg.sources.isEmpty()) {
            holder.sourcesGroup.setVisibility(View.VISIBLE);
            for (String source : msg.sources) {
                Chip chip = new Chip(holder.sourcesGroup.getContext());
                chip.setText(source);
                chip.setTextSize(11f);
                chip.setChipBackgroundColorResource(R.color.surface);
                chip.setClickable(false);
                holder.sourcesGroup.addView(chip);
            }
        } else {
            holder.sourcesGroup.setVisibility(View.GONE);
        }
    }

    /** Только текст ответа (стрим) — без пересборки tools/thinking. */
    private void bindAssistantStreamText(MessageViewHolder holder, ChatMessage msg, int position) {
        if (msg.role != ChatMessage.Role.ASSISTANT || msg.isWorkingMessage || msg.isCheckpointMessage) {
            onBindViewHolder(holder, position);
            return;
        }
        holder.messageText.setVisibility(View.GONE);
        holder.messageWebView.setVisibility(View.VISIBLE);

        Context ctx = holder.itemView.getContext();
        ChatHtmlTheme theme = ChatHtmlTheme.from(ctx);
        String htmlKey = msg.text != null ? msg.text : "";
        if (!htmlKey.equals(holder.lastBoundText)) {
            holder.lastBoundText = htmlKey;
            String html = htmlKey.isEmpty() ? "" : MarkdownParser.toHtml(htmlKey,
                    ctx.getString(R.string.chat_copy), theme);
            String wrapped = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                    + "<style>" + theme.bodyCss() + " body{padding-bottom:22px;}</style></head><body>" + html + "</body></html>";
            holder.messageWebView.loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null);
        }

        boolean showActions = !msg.isStreaming
                && msg.text != null && !msg.text.isEmpty()
                && isLastAssistantMessage(position);
        holder.actionButtons.setVisibility(showActions ? View.VISIBLE : View.GONE);
        if (holder.thinkingIcon != null) {
            holder.thinkingIcon.setImageResource(msg.isStreaming
                    ? R.drawable.ic_thinking : R.drawable.ic_thinking_ended);
        }
    }

    private void bindThinking(MessageViewHolder holder, ChatMessage msg, int position) {
        TextView labelView = holder.itemView.findViewById(R.id.thinking_label);

        int[] span = thoughtSpan(position);
        int firstIdx = span[0];
        int thoughtCount = span[1];
        boolean hasOwn = msg.thinking != null && !msg.thinking.isEmpty();

        if (!hasOwn || firstIdx < 0 || position != firstIdx) {
            holder.thinkingBlock.setVisibility(View.GONE);
            return;
        }

        holder.thinkingBlock.setVisibility(View.VISIBLE);
        Context ctx = holder.itemView.getContext();
        if (labelView != null) {
            labelView.setText(thoughtCount > 1
                    ? ctx.getString(R.string.chat_thought_count, thoughtCount)
                    : ctx.getString(R.string.chat_thinking));
        }

        String merged = mergeThoughtsInTurn(firstIdx);
        holder.thinkingText.setText(merged);
        holder.thinkingText.setMovementMethod(ScrollingMovementMethod.getInstance());

        ChatMessage head = messages.get(firstIdx);
        if (holder.thinkingIcon != null) {
            holder.thinkingIcon.setImageResource(isTurnStreamingThoughts(firstIdx)
                    ? R.drawable.ic_thinking : R.drawable.ic_thinking_ended);
        }

        applyThinkingExpanded(holder, head.thinkingExpanded);

        if (head.thinkingExpanded && isTurnStreamingThoughts(firstIdx)) {
            holder.thinkingText.post(() -> {
                if (holder.thinkingText.getLayout() == null) return;
                int layoutH = holder.thinkingText.getLayout().getHeight();
                int viewH = holder.thinkingText.getHeight();
                int maxScroll = Math.max(0, layoutH - viewH);
                holder.thinkingText.scrollTo(0, maxScroll);
            });
        }

        holder.thinkingToggle.setOnClickListener(v -> {
            head.thinkingExpanded = !head.thinkingExpanded;
            applyThinkingExpanded(holder, head.thinkingExpanded);
        });

        holder.thinkingText.setOnTouchListener((v, event) -> {
            if (holder.thinkingText.getVisibility() != View.VISIBLE) return false;
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
    }

    /** [firstIndexWithThinking, count] внутри хода после последнего USER. */
    private int[] thoughtSpan(int position) {
        if (position < 0 || position >= messages.size()) return new int[]{-1, 0};
        int turnStart = position;
        while (turnStart > 0 && messages.get(turnStart).role != ChatMessage.Role.USER) {
            turnStart--;
        }
        if (messages.get(turnStart).role == ChatMessage.Role.USER) turnStart++;

        int first = -1;
        int count = 0;
        for (int i = turnStart; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role == ChatMessage.Role.USER) break;
            if (m.isWorkingMessage || m.isCheckpointMessage) continue;
            if (m.role == ChatMessage.Role.ASSISTANT
                    && m.thinking != null && !m.thinking.isEmpty()) {
                if (first < 0) first = i;
                count++;
            }
        }
        return new int[]{first, count};
    }

    private String mergeThoughtsInTurn(int firstIdx) {
        StringBuilder sb = new StringBuilder();
        for (int i = firstIdx; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role == ChatMessage.Role.USER) break;
            if (m.isWorkingMessage || m.isCheckpointMessage) continue;
            if (m.role == ChatMessage.Role.ASSISTANT
                    && m.thinking != null && !m.thinking.isEmpty()) {
                if (sb.length() > 0) sb.append("\n\n——\n\n");
                sb.append(m.thinking.trim());
            }
        }
        return sb.toString();
    }

    private boolean isTurnStreamingThoughts(int firstIdx) {
        for (int i = firstIdx; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role == ChatMessage.Role.USER) break;
            if (m.role == ChatMessage.Role.ASSISTANT && m.isStreaming
                    && m.thinking != null && !m.thinking.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void applyThinkingExpanded(MessageViewHolder holder, boolean expanded) {
        holder.thinkingText.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (holder.thinkingChevron != null) {
            holder.thinkingChevron.setRotation(expanded ? 180f : 0f);
        }
    }

    /** Схлопнуть ряд, чьи мысли уже показаны в полоске на firstIdx и больше нечего рисовать. */
    private boolean collapseMergedThoughtRow(MessageViewHolder holder, ChatMessage msg, int position) {
        if (msg.role != ChatMessage.Role.ASSISTANT) return false;
        if (msg.isWorkingMessage || msg.isCheckpointMessage) return false;
        boolean hasOwn = msg.thinking != null && !msg.thinking.isEmpty();
        if (!hasOwn) return false;
        int[] span = thoughtSpan(position);
        if (span[0] < 0 || position == span[0]) return false;
        boolean hasText = msg.text != null && !msg.text.trim().isEmpty();
        boolean hasTools = msg.toolCalls != null && !msg.toolCalls.isEmpty();
        boolean hasSources = msg.sources != null && !msg.sources.isEmpty();
        if (hasText || hasTools || hasSources) return false;

        holder.thinkingBlock.setVisibility(View.GONE);
        holder.bubbleContainer.setVisibility(View.GONE);
        holder.messageTime.setVisibility(View.GONE);
        holder.toolCallsContainer.setVisibility(View.GONE);
        holder.actionButtons.setVisibility(View.GONE);
        holder.sourcesGroup.setVisibility(View.GONE);
        holder.itemView.setPadding(0, 0, 0, 0);
        ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            holder.itemView.setLayoutParams(lp);
        }
        return true;
    }

    private boolean isLastAssistantMessage(int position) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.role == ChatMessage.Role.ASSISTANT && !m.isWorkingMessage) {
                return i == position;
            }
        }
        return false;
    }

    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    private void startShimmer(MessageViewHolder holder) {
        stopShimmer(holder);

        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF1C1F28, 0xFF252A36, 0xFF2E3545, 0xFF252A36, 0xFF1C1F28}
        );
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        gd.setCornerRadius(dp(holder.itemView.getContext(), 10));
        holder.workingContainer.setBackground(gd);

        ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(holder.workingContainer, "alpha", 0.7f, 1.0f, 0.7f);
        alphaAnim.setRepeatCount(ValueAnimator.INFINITE);
        alphaAnim.setDuration(1500);
        alphaAnim.setInterpolator(new LinearInterpolator());
        alphaAnim.start();
        holder.shimmerAnimator = alphaAnim;

        final String[] dotsStates = {".  ", ".. ", "...", " ..", "  .", "   "};
        final int[] dotIndex = {0};
        holder.workingDots.setText(dotsStates[0]);
        holder.workingDots.setVisibility(View.VISIBLE);
        holder.dotsHandler = new Handler(Looper.getMainLooper());
        holder.dotsHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (holder.shimmerAnimator == null) return;
                dotIndex[0] = (dotIndex[0] + 1) % dotsStates.length;
                holder.workingDots.setText(dotsStates[dotIndex[0]]);
                holder.dotsHandler.postDelayed(this, 400);
            }
        }, 400);
    }

    private void stopShimmer(MessageViewHolder holder) {
        if (holder.shimmerAnimator != null) {
            holder.shimmerAnimator.cancel();
            holder.shimmerAnimator = null;
        }
        if (holder.dotsHandler != null) {
            holder.dotsHandler.removeCallbacksAndMessages(null);
            holder.dotsHandler = null;
        }
        if (holder.workingContainer != null) {
            holder.workingContainer.setAlpha(1.0f);
            holder.workingContainer.setBackgroundResource(R.drawable.bg_working_gradient);
        }
        if (holder.workingDots != null) {
            holder.workingDots.setVisibility(View.GONE);
        }
    }

    /** Обновить соседние tool-ряды в том же ходе (схлопнутые Read → одна строка). */
    public void refreshSiblingToolRows(int position) {
        if (position < 0 || position >= messages.size()) return;
        int start = turnStartIndex(position);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role == ChatMessage.Role.USER) break;
            if (i == position) continue;
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                notifyItemChanged(i, PAYLOAD_TOOLS);
            }
        }
    }

    private int turnStartIndex(int position) {
        if (position < 0 || position >= messages.size()) return 0;
        int turnStart = position;
        while (turnStart > 0 && messages.get(turnStart).role != ChatMessage.Role.USER) {
            turnStart--;
        }
        if (messages.get(turnStart).role == ChatMessage.Role.USER) turnStart++;
        return turnStart;
    }

    private static boolean isReadTool(ToolCallInfo c) {
        return c != null && "read_file".equals(c.name);
    }

    private static boolean isListTool(ToolCallInfo c) {
        return c != null && "list_files".equals(c.name);
    }

    private static boolean isWriteTool(ToolCallInfo c) {
        return c != null && ("write_file".equals(c.name)
                || "search_replace".equals(c.name)
                || "apply_patch".equals(c.name));
    }

    private static boolean isToolFailed(ToolCallInfo c) {
        return c != null && c.result != null && c.result.startsWith("ОШИБКА");
    }

    private static boolean isToolPending(ToolCallInfo c) {
        if (c == null) return false;
        if (isToolFailed(c)) return false;
        return c.result == null || !c.done;
    }

    private boolean messageHasReads(ChatMessage m) {
        if (m == null || m.toolCalls == null) return false;
        for (ToolCallInfo c : m.toolCalls) {
            if (isReadTool(c)) return true;
        }
        return false;
    }

    private int lastReadMessageInTurn(int position) {
        int start = turnStartIndex(position);
        int last = -1;
        for (int i = start; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role == ChatMessage.Role.USER) break;
            if (messageHasReads(m)) last = i;
        }
        return last;
    }

    private List<ToolCallInfo> collectTurnReads(int position) {
        List<ToolCallInfo> out = new ArrayList<>();
        int start = turnStartIndex(position);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role == ChatMessage.Role.USER) break;
            if (m.toolCalls == null) continue;
            for (ToolCallInfo c : m.toolCalls) {
                if (isReadTool(c)) out.add(c);
            }
        }
        return out;
    }

    private void renderToolCalls(MessageViewHolder holder, ChatMessage msg, int position) {
        stopShimmerOnContainer(holder.toolCallsContainer);
        holder.toolCallsContainer.removeAllViews();

        if (msg.toolCalls == null || msg.toolCalls.isEmpty()) {
            holder.toolCallsContainer.setVisibility(View.GONE);
            return;
        }

        holder.toolCallsContainer.setVisibility(View.VISIBLE);
        Context context = holder.toolCallsContainer.getContext();

        List<ToolCallInfo> writeBatch = new ArrayList<>();
        List<ToolCallInfo> listBatch = new ArrayList<>();
        boolean showReadsHere = lastReadMessageInTurn(position) == position;

        Runnable flushWrites = () -> {
            if (writeBatch.isEmpty()) return;
            holder.toolCallsContainer.addView(
                    buildGroupedFileEvents(context, new ArrayList<>(writeBatch), true));
            writeBatch.clear();
        };
        Runnable flushLists = () -> {
            if (listBatch.isEmpty()) return;
            holder.toolCallsContainer.addView(buildListSummaryEvent(context, new ArrayList<>(listBatch)));
            listBatch.clear();
        };

        for (ToolCallInfo call : msg.toolCalls) {
            boolean isFetch = "fetch_url".equals(call.name);
            boolean isSearch = "web_search".equals(call.name);
            boolean isCmd = "run_command".equals(call.name);

            if (isFetch) {
                flushWrites.run();
                flushLists.run();
                holder.toolCallsContainer.addView(buildSilentWebChip(context, call));
                continue;
            }
            if (isSearch) {
                flushWrites.run();
                flushLists.run();
                holder.toolCallsContainer.addView(buildSearchChip(context, call));
                continue;
            }
            if (isCmd) {
                flushWrites.run();
                flushLists.run();
                holder.toolCallsContainer.addView(buildCommandChip(context, call));
                continue;
            }

            if (isReadTool(call)) {
                // чтения рисуем одним рядом на последнем сообщении хода
                continue;
            }

            if (isWriteTool(call)) {
                flushLists.run();
                if (isToolFailed(call)) {
                    flushWrites.run();
                    holder.toolCallsContainer.addView(buildFileEvent(context, call));
                } else {
                    writeBatch.add(call);
                }
                continue;
            }

            if (isListTool(call)) {
                flushWrites.run();
                if (isToolFailed(call)) {
                    flushLists.run();
                    holder.toolCallsContainer.addView(buildFileEvent(context, call));
                } else {
                    listBatch.add(call);
                }
                continue;
            }

            flushWrites.run();
            flushLists.run();
            holder.toolCallsContainer.addView(buildFileEvent(context, call));
        }
        flushWrites.run();
        flushLists.run();

        if (showReadsHere) {
            List<ToolCallInfo> turnReads = collectTurnReads(position);
            if (!turnReads.isEmpty()) {
                holder.toolCallsContainer.addView(
                        buildGroupedFileEvents(context, turnReads, false));
            }
        }

        if (holder.toolCallsContainer.getChildCount() == 0) {
            holder.toolCallsContainer.setVisibility(View.GONE);
        }
    }

    private View buildListSummaryEvent(Context context, List<ToolCallInfo> calls) {
        if (calls.size() == 1) {
            return buildFileEvent(context, calls.get(0));
        }
        TextView line = new TextView(context);
        line.setText(context.getString(R.string.tool_listed_n, calls.size()));
        line.setTextColor(context.getColor(R.color.text_secondary));
        line.setTextSize(13f);
        line.setPadding(0, dp(context, 3), 0, dp(context, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(context, 2);
        line.setLayoutParams(lp);
        boolean pending = false;
        for (ToolCallInfo c : calls) {
            if (isToolPending(c)) { pending = true; break; }
        }
        applyTextShimmer(line, pending);
        return line;
    }

    /** Cursor-like: одна строка «Read/Reading N» или «Edited N»; shimmer пока выполняется. */
    private View buildGroupedFileEvents(Context context, List<ToolCallInfo> calls, boolean writes) {
        boolean anyPending = false;
        for (ToolCallInfo c : calls) {
            if (isToolPending(c)) { anyPending = true; break; }
        }

        if (!writes && calls.size() == 1 && !anyPending) {
            return buildFileEvent(context, calls.get(0));
        }
        if (writes && calls.size() == 1 && !anyPending) {
            return buildFileEvent(context, calls.get(0));
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rootLp.bottomMargin = dp(context, 2);
        root.setLayoutParams(rootLp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(context, 4), 0, dp(context, 4));
        header.setClickable(true);

        TextView summary = new TextView(context);
        summary.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!writes && anyPending) {
            summary.setText(context.getString(R.string.tool_reading_n, calls.size()));
        } else {
            summary.setText(context.getString(
                    writes ? R.string.tool_edited_n : R.string.tool_read_n, calls.size()));
        }
        summary.setTextColor(context.getColor(R.color.text_secondary));
        summary.setTextSize(13f);
        header.addView(summary);
        applyTextShimmer(summary, anyPending);

        int addSum = 0, delSum = 0;
        boolean anyDiff = false;
        if (writes && !anyPending) {
            for (ToolCallInfo c : calls) {
                if (c.diffAdded >= 0) {
                    anyDiff = true;
                    addSum += c.diffAdded;
                    delSum += Math.max(0, c.diffRemoved);
                }
            }
        }
        if (anyDiff) {
            TextView diff = new TextView(context);
            diff.setText(diffSpan(context, addSum, delSum));
            diff.setTextSize(12f);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dLp.leftMargin = dp(context, 8);
            diff.setLayoutParams(dLp);
            header.addView(diff);
        }

        ImageView chevron = new ImageView(context);
        int chev = dp(context, 12);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(chev, chev);
        cLp.leftMargin = dp(context, 6);
        chevron.setLayoutParams(cLp);
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setColorFilter(context.getColor(R.color.text_tertiary));
        header.addView(chevron);
        root.addView(header);

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        details.setPadding(dp(context, 8), 0, 0, 0);
        for (ToolCallInfo call : calls) {
            details.addView(buildFileEvent(context, call));
        }
        root.addView(details);

        View.OnClickListener toggle = v -> {
            boolean open = details.getVisibility() == View.VISIBLE;
            details.setVisibility(open ? View.GONE : View.VISIBLE);
            chevron.animate().rotation(open ? 0f : 90f).setDuration(120).start();
        };
        header.setOnClickListener(toggle);
        return root;
    }

    /** Одна строка события: Wrote path  +N −M  (без карточек и ok-pill). */
    private View buildFileEvent(Context context, ToolCallInfo call) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(context, 2);
        row.setLayoutParams(rowLp);
        row.setPadding(0, dp(context, 3), 0, dp(context, 3));

        boolean isWrite = "write_file".equals(call.name)
                || "search_replace".equals(call.name)
                || "apply_patch".equals(call.name);
        boolean isRead = "read_file".equals(call.name);
        boolean isList = "list_files".equals(call.name);
        boolean hasDiff = isWrite && call.diffAdded >= 0;
        boolean failed = call.result != null && call.result.startsWith("ОШИБКА");
        boolean pending = call.result == null || (!call.done && !failed);
        boolean emptyList = isList && call.result != null
                && (call.result.trim().equals("(пусто)")
                || call.result.trim().equalsIgnoreCase("(empty)"));

        String pathArg = safeArg(call, "path");
        if (pathArg.isEmpty() && call.targetFile != null) pathArg = call.targetFile;
        final String filePath = pathArg;
        boolean canOpenFile = !filePath.isEmpty() && !isList
                && (isRead || isWrite || filePath.contains("."));

        String verb;
        if ("write_file".equals(call.name)) verb = context.getString(R.string.tool_wrote);
        else if ("search_replace".equals(call.name) || "apply_patch".equals(call.name))
            verb = context.getString(R.string.tool_patched);
        else if (isRead) verb = context.getString(R.string.tool_read);
        else if (isList) verb = context.getString(R.string.tool_list);
        else verb = call.name != null ? call.name : "tool";

        String label;
        if (isList) label = filePath.isEmpty() ? "." : filePath;
        else if (!filePath.isEmpty()) label = filePath;
        else if (call.targetFile != null) label = call.targetFile;
        else label = call.name != null ? call.name : "tool";
        // короткий basename для спокойного вида
        String shortLabel = shortPath(label);

        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView event = new TextView(context);
        event.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        event.setTextSize(13f);
        event.setMaxLines(1);
        event.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int verbColor = failed ? context.getColor(R.color.error) : context.getColor(R.color.text_secondary);
        int pathColor = canOpenFile ? context.getColor(R.color.text_primary)
                : context.getColor(R.color.text_secondary);
        appendColored(sb, verb, verbColor);
        sb.append(' ');
        appendColored(sb, shortLabel, pathColor);
        if (pending) {
            sb.append(' ');
            appendColored(sb, "…", context.getColor(R.color.text_tertiary));
        } else if (failed) {
            sb.append(' ');
            appendColored(sb, context.getString(R.string.tool_failed), context.getColor(R.color.error));
        } else if (emptyList) {
            sb.append(' ');
            appendColored(sb, context.getString(R.string.tool_empty), context.getColor(R.color.text_tertiary));
        }
        event.setText(sb);
        line.addView(event);
        applyTextShimmer(event, pending);

        if (hasDiff && !failed) {
            TextView diff = new TextView(context);
            diff.setText(diffSpan(context, call.diffAdded, call.diffRemoved));
            diff.setTextSize(12f);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dLp.leftMargin = dp(context, 8);
            diff.setLayoutParams(dLp);
            diff.setOnClickListener(v -> {
                if (diffClickListener != null) diffClickListener.onDiffClick(call);
            });
            line.addView(diff);
        }

        row.addView(line);

        if (canOpenFile) {
            event.setOnClickListener(v -> {
                if (filePathClickListener != null) filePathClickListener.onFilePathClick(filePath);
            });
        }

        if (failed) {
            TextView err = new TextView(context);
            err.setText(shortenError(call.result));
            err.setTextColor(context.getColor(R.color.error));
            err.setTextSize(12f);
            err.setMaxLines(2);
            err.setEllipsize(android.text.TextUtils.TruncateAt.END);
            err.setPadding(0, dp(context, 2), 0, 0);
            final String full = call.result != null ? call.result : "";
            err.setOnClickListener(v -> {
                if (err.getMaxLines() == 2) {
                    err.setMaxLines(20);
                    err.setText(full);
                } else {
                    err.setMaxLines(2);
                    err.setText(shortenError(full));
                }
            });
            row.addView(err);
        }

        return row;
    }

    private static void appendColored(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private CharSequence diffSpan(Context context, int added, int removed) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendColored(sb, "+" + added, context.getColor(R.color.success));
        sb.append(' ');
        appendColored(sb, "−" + Math.max(0, removed), context.getColor(R.color.error));
        return sb;
    }

    private static String shortPath(String path) {
        if (path == null || path.isEmpty()) return path;
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 && slash < p.length() - 1 ? p.substring(slash + 1) : p;
    }

    /** Компактный event shell-команды — без карточки. */
    private View buildCommandChip(Context context, ToolCallInfo call) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(context, 2);
        root.setLayoutParams(lp);
        root.setPadding(0, dp(context, 3), 0, dp(context, 3));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        String cmd = safeArg(call, "command");
        if (cmd.isEmpty()) cmd = safeArg(call, "description");
        if (cmd.isEmpty()) cmd = "shell";
        cmd = cmd.replace('\n', ' ').trim();

        TextView title = new TextView(context);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendColored(sb, context.getString(R.string.tool_ran), context.getColor(R.color.text_secondary));
        sb.append(' ');
        appendColored(sb, cmd, context.getColor(R.color.text_primary));
        title.setText(sb);
        title.setTextSize(13f);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        header.addView(title);

        TextView badge = new TextView(context);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(context, 8);
        badge.setLayoutParams(badgeLp);
        badge.setTextSize(12f);

        boolean failed = call.result != null && call.result.startsWith("ОШИБКА");
        boolean pending = call.result == null || (!call.done && !failed);
        Integer exit = parseExitCode(call.result);

        if (pending) {
            badge.setText("…");
            badge.setTextColor(context.getColor(R.color.text_tertiary));
        } else if (failed) {
            badge.setText(context.getString(R.string.cmd_error));
            badge.setTextColor(context.getColor(R.color.error));
        } else if (exit != null) {
            badge.setText(context.getString(R.string.cmd_exit, exit));
            badge.setTextColor(context.getColor(exit == 0 ? R.color.success : R.color.warning_orange));
        } else {
            badge.setText(context.getString(R.string.tool_ok));
            badge.setTextColor(context.getColor(R.color.success));
        }
        header.addView(badge);
        root.addView(header);

        TextView detail = new TextView(context);
        detail.setText(formatCommandOutput(call.result));
        detail.setTextColor(context.getColor(R.color.text_tertiary));
        detail.setTextSize(11f);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setVisibility(View.GONE);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(context, 4);
        detail.setLayoutParams(detailLp);
        detail.setMaxLines(8);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(detail);

        boolean canExpand = call.result != null && !call.result.isEmpty() && !pending;
        if (canExpand) {
            root.setOnClickListener(v -> {
                boolean open = detail.getVisibility() == View.VISIBLE;
                detail.setVisibility(open ? View.GONE : View.VISIBLE);
            });
        }

        return root;
    }

    private static Integer parseExitCode(String result) {
        if (result == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(exit\\s+(-?\\d+)\\)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(result);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return null;
    }

    /** Убирает шум: OK (exit N), обрезает длинный stderr. */
    private static String formatCommandOutput(String result) {
        if (result == null || result.isEmpty()) return "";
        String s = result.trim();
        s = s.replaceFirst("(?i)^OK\\s*\\(exit\\s+-?\\d+\\)\\s*", "");
        s = s.replaceFirst("(?i)^\\[stderr\\]\\s*", "");
        s = s.replace("\r\n", "\n").trim();
        if (s.isEmpty()) return "(no output)";
        if (s.length() > 900) s = s.substring(0, 900) + "…";
        return s;
    }

    /** Event-строка поиска. */
    private View buildSearchChip(Context context, ToolCallInfo call) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(0, dp(context, 3), 0, dp(context, 3));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(context, 2);
        chip.setLayoutParams(lp);

        String query = safeArg(call, "query");
        if (query.isEmpty()) query = "search";

        boolean failed = call.result != null && call.result.startsWith("ОШИБКА");
        boolean pending = call.result == null || (!call.done && !failed);
        int hits = countSearchHits(call.result);

        TextView title = new TextView(context);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendColored(sb, context.getString(R.string.tool_searched), context.getColor(R.color.text_secondary));
        sb.append(' ');
        appendColored(sb, query, context.getColor(R.color.text_primary));
        title.setText(sb);
        title.setTextSize(13f);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.addView(title);

        TextView meta = new TextView(context);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = dp(context, 8);
        meta.setLayoutParams(metaLp);
        meta.setTextSize(12f);
        if (pending) {
            meta.setText("…");
            meta.setTextColor(context.getColor(R.color.text_tertiary));
        } else if (failed) {
            meta.setText(context.getString(R.string.tool_failed));
            meta.setTextColor(context.getColor(R.color.error));
        } else {
            meta.setText(hits > 0 ? String.valueOf(hits) : context.getString(R.string.tool_ok));
            meta.setTextColor(context.getColor(R.color.text_tertiary));
        }
        chip.addView(meta);

        return chip;
    }

    /** fetch_url: тихая event-строка. */
    private View buildSilentWebChip(Context context, ToolCallInfo call) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(0, dp(context, 3), 0, dp(context, 3));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(context, 2);
        chip.setLayoutParams(lp);

        String host = hostOf(safeArg(call, "url"));
        boolean failed = call.result != null && call.result.startsWith("ОШИБКА");

        TextView title = new TextView(context);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendColored(sb, context.getString(R.string.tool_fetched), context.getColor(R.color.text_secondary));
        sb.append(' ');
        appendColored(sb, host, context.getColor(R.color.text_tertiary));
        title.setText(sb);
        title.setTextSize(13f);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        chip.addView(title);

        if (call.done || call.result != null) {
            TextView mark = new TextView(context);
            mark.setText(failed ? "!" : "·");
            mark.setTextSize(12f);
            mark.setTextColor(context.getColor(failed ? R.color.error : R.color.text_tertiary));
            chip.addView(mark);
        }
        return chip;
    }

    private static int countSearchHits(String result) {
        if (result == null || result.isEmpty() || result.startsWith("ОШИБКА")) return 0;
        int n = 0;
        for (String line : result.split("\n")) {
            String t = line.trim();
            if (t.matches("^\\d+\\.\\s.+")) n++;
        }
        return n;
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "page";
        try {
            String u = url.trim();
            int scheme = u.indexOf("://");
            if (scheme >= 0) u = u.substring(scheme + 3);
            int slash = u.indexOf('/');
            if (slash >= 0) u = u.substring(0, slash);
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            return u.isEmpty() ? "page" : u;
        } catch (Exception e) {
            return "page";
        }
    }

    private static final int TAG_SHIMMER_ANIM = 0x50F11EE1;

    private void applyTextShimmer(TextView tv, boolean enable) {
        if (tv == null) return;
        Object old = tv.getTag(TAG_SHIMMER_ANIM);
        if (old instanceof ValueAnimator) {
            ((ValueAnimator) old).cancel();
            tv.setTag(TAG_SHIMMER_ANIM, null);
        }
        tv.getPaint().setShader(null);
        if (!enable) {
            tv.invalidate();
            return;
        }
        final int base = tv.getContext().getColor(R.color.text_tertiary);
        final int mid = tv.getContext().getColor(R.color.text_secondary);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1300);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float w = Math.max(tv.getWidth(), dp(tv.getContext(), 120));
            LinearGradient g = new LinearGradient(
                    -w + 3f * w * f, 0f, w + 2f * w * f, 0f,
                    new int[]{base, mid, 0xFFE8EAED, mid, base},
                    new float[]{0f, 0.35f, 0.5f, 0.65f, 1f},
                    Shader.TileMode.CLAMP);
            tv.getPaint().setShader(g);
            tv.invalidate();
        });
        tv.setTag(TAG_SHIMMER_ANIM, anim);
        // ширина может быть 0 до layout — старт после measure
        tv.post(anim::start);
    }

    private void stopShimmerOnContainer(ViewGroup container) {
        if (container == null) return;
        stopShimmerRecursive(container);
    }

    private void stopShimmerRecursive(View v) {
        if (v instanceof TextView) {
            applyTextShimmer((TextView) v, false);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                stopShimmerRecursive(g.getChildAt(i));
            }
        }
    }

    private String previewLine(String text) {
        if (text == null || text.isEmpty()) return "…";
        String one = text.replace('\n', ' ').trim();
        if (one.length() > 90) one = one.substring(0, 90) + "…";
        return one;
    }

    private static String shortenError(String text) {
        if (text == null) return "";
        String s = text.trim();
        // убрать повторяющийся префикс «ОШИБКА:» в превью
        if (s.startsWith("ОШИБКА:")) s = s.substring("ОШИБКА:".length()).trim();
        return s;
    }

    private String safeArg(ToolCallInfo call, String key) {
        try {
            JSONObject args = new JSONObject(call.argumentsJson == null ? "{}" : call.argumentsJson);
            return args.optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void removeMessage(ChatMessage message) {
        int idx = messages.indexOf(message);
        if (idx != -1) {
            messages.remove(idx);
            notifyItemRemoved(idx);
        }
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        LinearLayout thinkingBlock, toolCallsContainer, thinkingToggle, actionButtons;
        LinearLayout workingContainer;
        ImageView thinkingIcon, thinkingChevron;
        TextView thinkingText, messageText, messageTime, workingText, workingDots;
        WebView messageWebView;
        ChipGroup sourcesGroup;
        ImageButton btnCopy, btnRefresh;
        View bubbleContainer;
        ValueAnimator shimmerAnimator;
        Handler dotsHandler;
        String lastBoundText;

        MessageViewHolder(View itemView) {
            super(itemView);
            thinkingBlock = itemView.findViewById(R.id.thinking_block);
            thinkingToggle = itemView.findViewById(R.id.thinking_toggle);
            thinkingIcon = itemView.findViewById(R.id.thinking_icon);
            thinkingChevron = itemView.findViewById(R.id.thinking_chevron);
            thinkingText = itemView.findViewById(R.id.thinking_text);
            messageText = itemView.findViewById(R.id.message_text);
            messageTime = itemView.findViewById(R.id.message_time);
            sourcesGroup = itemView.findViewById(R.id.sources_group);
            toolCallsContainer = itemView.findViewById(R.id.tool_calls_container);
            actionButtons = itemView.findViewById(R.id.action_buttons);
            btnCopy = itemView.findViewById(R.id.btn_copy);
            btnRefresh = itemView.findViewById(R.id.btn_refresh);
            bubbleContainer = itemView.findViewById(R.id.bubble_container);
            workingContainer = itemView.findViewById(R.id.working_container);
            workingText = itemView.findViewById(R.id.working_text);
            workingDots = itemView.findViewById(R.id.working_dots);

            messageWebView = new WebView(itemView.getContext());
            messageWebView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            messageWebView.setBackgroundColor(0x00000000);
            messageWebView.setHorizontalScrollBarEnabled(false);
            messageWebView.setVerticalScrollBarEnabled(false);
            messageWebView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
            messageWebView.getSettings().setJavaScriptEnabled(true);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                messageWebView.setForceDarkAllowed(false);
                try {
                    messageWebView.getSettings().setForceDark(
                            android.webkit.WebSettings.FORCE_DARK_OFF);
                } catch (Throwable ignored) {}
            }
            messageWebView.addJavascriptInterface(new CodeCopyInterface(itemView.getContext()), "Android");
            messageWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    openExternalUrl(view.getContext(), url);
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view,
                        android.webkit.WebResourceRequest request) {
                    if (request != null && request.getUrl() != null) {
                        openExternalUrl(view.getContext(), request.getUrl().toString());
                    }
                    return true;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    view.post(() -> {
                        view.requestLayout();
                        ViewGroup.LayoutParams lp = view.getLayoutParams();
                        if (lp != null) view.setLayoutParams(lp);
                    });
                }
            });

            ViewGroup parent = (ViewGroup) messageText.getParent();
            int idx = parent.indexOfChild(messageText);
            parent.addView(messageWebView, idx + 1);
            messageWebView.setVisibility(View.GONE);
        }
    }

    // JavaScript-интерфейс для копирования кода в буфер обмена
    private static void openExternalUrl(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) return;
        if (url.startsWith("data:") || url.startsWith("about:")) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {}
    }

    private static class CodeCopyInterface {
        private final Context context;

        CodeCopyInterface(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("code", text);
            clipboard.setPrimaryClip(clip);

            // тост в UI-потоке
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, R.string.chat_code_copied, Toast.LENGTH_SHORT).show()
            );
        }
    }
}
