package com.lean.iptv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Grid of channel tiles. RecyclerView recycles views, so 250 tiles stay light. */
public class GridAdapter extends RecyclerView.Adapter<GridAdapter.VH> {

    public interface OnTileClick {
        void onClick(int position, Channel channel);
    }

    private final List<Channel> data = new ArrayList<>();
    private final OnTileClick listener;

    public GridAdapter(OnTileClick listener) {
        this.listener = listener;
    }

    public void setData(List<Channel> channels) {
        data.clear();
        data.addAll(channels);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.grid_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Channel c = data.get(position);
        h.number.setText("#" + (position + 1));
        h.name.setText(c.name);
        // Lazy logo load; tile shows just the name if it fails or is slow.
        LogoLoader.get(h.itemView.getContext()).load(c.logo, h.logo);

        h.itemView.setOnClickListener(v -> {
            int p = h.getAdapterPosition();
            if (p != RecyclerView.NO_POSITION && listener != null) {
                listener.onClick(p, data.get(p));
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView logo;
        final TextView number;
        final TextView name;
        VH(View v) {
            super(v);
            logo = v.findViewById(R.id.tileLogo);
            number = v.findViewById(R.id.tileNumber);
            name = v.findViewById(R.id.tileName);
        }
    }
}
