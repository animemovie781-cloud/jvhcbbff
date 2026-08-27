package genius.DMTech.Vectr;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    public interface Listener {
        void onOpen(FileEntry entry);
        void onMore(FileEntry entry, View anchor);
    }

    private List<FileEntry> files = new ArrayList<>();
    private final Listener listener;

    public FileAdapter(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileEntry entry = files.get(position);
        holder.name.setText(entry.name);
        holder.meta.setText(entry.subtitle(holder.itemView.getContext()));

        int iconRes = FileIcons.forEntry(entry.isDirectory, entry.isParent, entry.name);
        holder.icon.setImageResource(iconRes);

        if (FileIcons.usesAccentTint(entry.isDirectory, entry.isParent, entry.name)) {
            holder.icon.setImageResource(iconRes);
            holder.icon.setColorFilter(
                    ContextCompat.getColor(holder.icon.getContext(), R.color.accent),
                    android.graphics.PorterDuff.Mode.SRC_IN);
        } else {
            holder.icon.setColorFilter(null);
            holder.icon.setImageResource(iconRes);
        }

        holder.chevron.setVisibility(View.GONE); // тап по строке открывает — стрелка лишняя
        holder.more.setVisibility(entry.isParent ? View.INVISIBLE : View.VISIBLE);

        holder.itemView.setOnClickListener(v -> listener.onOpen(entry));
        holder.more.setOnClickListener(v -> {
            if (!entry.isParent) listener.onMore(entry, v);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (entry.isParent) return false;
            listener.onMore(entry, holder.more);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    public void submit(List<FileEntry> newFiles) {
        this.files = newFiles != null ? newFiles : new ArrayList<>();
        notifyDataSetChanged();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        ImageView chevron;
        TextView name;
        TextView meta;
        ImageButton more;

        FileViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.item_icon);
            chevron = itemView.findViewById(R.id.item_chevron);
            name = itemView.findViewById(R.id.item_name);
            meta = itemView.findViewById(R.id.item_meta);
            more = itemView.findViewById(R.id.item_more);
        }
    }
}
