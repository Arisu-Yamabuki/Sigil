package org.sigil.app;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    public static final class Msg {
        public final String id;
        public final boolean mine;
        public final String text;
        public final boolean expiring;

        public Msg(String id, boolean mine, String text, boolean expiring) {
            this.id = id;
            this.mine = mine;
            this.text = text;
            this.expiring = expiring;
        }
    }

    private final List<Msg> items;

    public MessageAdapter(List<Msg> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Msg m = items.get(position);
        h.body.setText(m.text);
        h.body.setBackgroundResource(m.mine ? R.drawable.bg_bubble_mine : R.drawable.bg_bubble_theirs);
        h.meta.setText(m.expiring ? "disappears" : (m.mine ? "sent" : ""));

        int gravity = m.mine ? Gravity.END : Gravity.START;
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.gravity = gravity;
        h.body.setLayoutParams(lp1);

        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.gravity = gravity;
        h.meta.setLayoutParams(lp2);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView body, meta;

        VH(View v) {
            super(v);
            body = v.findViewById(R.id.body);
            meta = v.findViewById(R.id.meta);
        }
    }
}
