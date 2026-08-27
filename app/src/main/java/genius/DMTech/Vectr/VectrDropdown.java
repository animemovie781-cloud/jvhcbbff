package genius.DMTech.Vectr;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Кастомный «спиннер»: поле + popup со списком опций (title / subtitle / badge).
 */
public class VectrDropdown {

    public static class Option {
        public final String title;
        public final String subtitle;
        public final String badge;
        public final boolean accent;

        public Option(String title, String subtitle, String badge, boolean accent) {
            this.title = title;
            this.subtitle = subtitle;
            this.badge = badge;
            this.accent = accent;
        }

        public Option(String title) {
            this(title, null, null, false);
        }
    }

    public interface Listener {
        void onSelected(int index, Option option);
    }

    private final Context context;
    private final View anchor;
    private final TextView titleView;
    private final TextView subtitleView;
    private final ImageView chevron;
    private final List<Option> options = new ArrayList<>();
    private int selectedIndex = 0;
    private Listener listener;
    private PopupWindow popup;

    public VectrDropdown(Context context, View fieldRoot) {
        this.context = context;
        this.anchor = fieldRoot;
        this.titleView = fieldRoot.findViewById(R.id.dropdown_title);
        this.subtitleView = fieldRoot.findViewById(R.id.dropdown_subtitle);
        this.chevron = fieldRoot.findViewById(R.id.dropdown_chevron);
        fieldRoot.setOnClickListener(v -> toggle());
    }

    public void setOptions(List<Option> opts) {
        options.clear();
        if (opts != null) options.addAll(opts);
        refreshLabel();
    }

    public void setSelectedIndex(int index) {
        if (index < 0 || index >= options.size()) return;
        selectedIndex = index;
        refreshLabel();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void refreshLabel() {
        if (options.isEmpty()) {
            titleView.setText("—");
            if (subtitleView != null) subtitleView.setVisibility(View.GONE);
            return;
        }
        Option o = options.get(selectedIndex);
        titleView.setText(o.title);
        if (subtitleView != null) {
            if (o.subtitle != null && !o.subtitle.isEmpty()) {
                subtitleView.setVisibility(View.VISIBLE);
                subtitleView.setText(o.subtitle);
            } else {
                subtitleView.setVisibility(View.GONE);
            }
        }
    }

    private void toggle() {
        if (popup != null && popup.isShowing()) {
            dismiss();
        } else {
            show();
        }
    }

    public void dismiss() {
        if (popup != null && popup.isShowing()) popup.dismiss();
        popup = null;
        if (chevron != null) {
            chevron.animate().rotation(0f).setDuration(160).start();
        }
    }

    private void show() {
        if (options.isEmpty()) return;
        View content = LayoutInflater.from(context).inflate(R.layout.popup_vectr_dropdown, null, false);
        RecyclerView list = content.findViewById(R.id.dropdown_list);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new Adapter());

        int width = anchor.getWidth();
        if (width <= 0) width = WindowManager.LayoutParams.MATCH_PARENT;

        popup = new PopupWindow(content, width, WindowManager.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        popup.setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(context, R.color.surface_raised)));
        popup.setOnDismissListener(() -> {
            if (chevron != null) chevron.animate().rotation(0f).setDuration(160).start();
        });
        if (chevron != null) chevron.animate().rotation(180f).setDuration(160).start();

        content.setAlpha(0f);
        content.setTranslationY(-8f);
        popup.showAsDropDown(anchor, 0, dp(6), Gravity.START);
        content.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dropdown_option, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Option o = options.get(position);
            h.title.setText(o.title);
            if (o.subtitle != null && !o.subtitle.isEmpty()) {
                h.subtitle.setVisibility(View.VISIBLE);
                h.subtitle.setText(o.subtitle);
            } else {
                h.subtitle.setVisibility(View.GONE);
            }
            if (o.badge != null && !o.badge.isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(o.badge);
                boolean warn = o.badge.contains("DEP");
                h.badge.setBackgroundResource(o.accent ? R.drawable.bg_badge_accent
                        : (warn ? R.drawable.bg_badge_warn : R.drawable.bg_badge_muted));
                h.badge.setTextColor(ContextCompat.getColor(context,
                        o.accent ? R.color.accent
                                : (warn ? R.color.warning_orange : R.color.text_secondary)));
            } else {
                h.badge.setVisibility(View.GONE);
            }
            boolean selected = position == selectedIndex;
            h.check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            h.row.setBackgroundResource(selected
                    ? R.drawable.bg_dropdown_item_selected
                    : R.drawable.bg_transparent_press);

            h.itemView.setOnClickListener(v -> {
                int idx = h.getBindingAdapterPosition();
                if (idx == RecyclerView.NO_POSITION) return;
                selectedIndex = idx;
                refreshLabel();
                Anim.pulseSelect(anchor);
                dismiss();
                if (listener != null) listener.onSelected(idx, options.get(idx));
            });
        }

        @Override
        public int getItemCount() {
            return options.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final LinearLayout row;
            final TextView title, subtitle, badge, check;

            Holder(View itemView) {
                super(itemView);
                row = itemView.findViewById(R.id.option_row);
                title = itemView.findViewById(R.id.option_title);
                subtitle = itemView.findViewById(R.id.option_subtitle);
                badge = itemView.findViewById(R.id.option_badge);
                check = itemView.findViewById(R.id.option_check);
            }
        }
    }
}
