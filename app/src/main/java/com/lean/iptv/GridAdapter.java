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

/** Grid of channel tiles. RecyclerView recycles views, so many tiles stay light. */
public class GridAdapter extends RecyclerView.Adapter<GridAdapter.VH> {

    public interface OnTileClick {
        void onClick(int position, Channel channel);
    }

    public interface OnStarClick {
        void onStar(int position, Channel channel, boolean currentlyFavorite);
    }

    private final List<Channel> data = new ArrayList<>();
    private final OnTileClick listener;
    private OnStarClick starListener;
    private Favorites favorites;

    public GridAdapter(OnTileClick listener) {
        this.listener = listener;
    }

    public void setStarListener(OnStarClick l) {
        this.starListener = l;
    }

    public void setFavorites(Favorites f) {
        this.favorites = f;
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
        LogoLoader.get(h.itemView.getContext()).load(c.logo, h.logo);

        boolean fav = favorites != null && favorites.isFavorite(c.url);
        h.star.setImageResource(fav ? R.drawable.ic_star_gold : R.drawable.ic_star_gray);

        h.itemView.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && listener != null) {
                listener.onClick(p, data.get(p));
            }
        });

        // Long-press a tile to toggle its favourite (TV-friendly: no extra focus stop).
        h.itemView.setOnLongClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && starListener != null) {
                Channel ch = data.get(p);
                boolean isFav = favorites != null && favorites.isFavorite(ch.url);
                starListener.onStar(p, ch, isFav);
                return true;
            }
            return false;
        });

        h.star.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && starListener != null) {
                Channel ch = data.get(p);
                boolean isFav = favorites != null && favorites.isFavorite(ch.url);
                starListener.onStar(p, ch, isFav);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView logo;
        final ImageView star;
        final TextView number;
        final TextView name;
        VH(View v) {
            super(v);
            logo = v.findViewById(R.id.tileLogo);
            star = v.findViewById(R.id.tileStar);
            number = v.findViewById(R.id.tileNumber);
            name = v.findViewById(R.id.tileName);
        }
    }
}
