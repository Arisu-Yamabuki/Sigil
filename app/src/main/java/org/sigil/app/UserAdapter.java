package org.sigil.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {

    public interface OnPick {
        void pick(Row row);
    }

    public static final class Row {
        public final String username;
        public final String status;
        public final String meta;
        public final boolean hasKey;

        public Row(String username, String status, String meta, boolean hasKey) {
            this.username = username;
            this.status = status;
            this.meta = meta;
            this.hasKey = hasKey;
        }
    }

    private final List<Row> rows;
    private final OnPick onPick;

    public UserAdapter(List<Row> rows, OnPick onPick) {
        this.rows = rows;
        this.onPick = onPick;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Row r = rows.get(position);
        h.name.setText("@" + r.username);
        h.meta.setText(r.meta == null ? "" : r.meta);
        h.status.setText(r.status);
        h.status.setTextColor(r.hasKey ? 0xFF8A97A8 : 0xFFFF7A8A);
        h.itemView.setOnClickListener(v -> onPick.pick(r));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name, meta, status;

        VH(View v) {
            super(v);
            name = v.findViewById(R.id.name);
            meta = v.findViewById(R.id.meta);
            status = v.findViewById(R.id.status);
        }
    }
}
