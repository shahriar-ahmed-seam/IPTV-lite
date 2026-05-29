package com.lean.iptv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Horizontal category chips. Selecting one filters the grid. */
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.VH> {

    public interface OnCategory {
        void onSelected(String category);
    }

    private final List<String> categories;
    private final OnCategory listener;
    private int selectedPos = 0;
    private RecyclerView recyclerView;

    public CategoryAdapter(List<String> categories, OnCategory listener) {
        this.categories = categories;
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        super.onAttachedToRecyclerView(rv);
        recyclerView = rv;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        super.onDetachedFromRecyclerView(rv);
        recyclerView = null;
    }

    public void setSelectedCategory(String category) {
        int idx = categories.indexOf(category);
        if (idx < 0) idx = 0;
        selectedPos = idx;
        safeRefresh();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.category_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String cat = categories.get(position);
        h.text.setText(cat);
        h.itemView.setSelected(position == selectedPos);

        // Filter when a chip gains focus -> fast D-pad browsing.
        // IMPORTANT: do NOT call notifyItemChanged() here; this fires during
        // scroll/layout and would crash the RecyclerView. Update state directly + posted.
        h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) select(h.getBindingAdapterPosition(), false);
        });
        // A deliberate click always notifies (even on the current chip), so it can be
        // used to exit favourites/search back to this category.
        h.itemView.setOnClickListener(v -> select(h.getBindingAdapterPosition(), true));
    }

    private void select(int pos, boolean force) {
        if (pos < 0 || pos >= categories.size()) return;
        if (pos == selectedPos && !force) return;

        final int old = selectedPos;
        selectedPos = pos;

        // Update the visible chips' highlight directly (safe during scroll).
        if (old != pos) {
            updateSelectedState(old);
            updateSelectedState(pos);
        }

        if (listener != null) listener.onSelected(categories.get(pos));
    }

    /** Toggle the selected highlight on a currently-visible chip without notifyItemChanged. */
    private void updateSelectedState(int pos) {
        if (recyclerView == null || pos < 0) return;
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.setSelected(pos == selectedPos);
        }
    }

    /** Full refresh, but deferred so it never runs during a layout/scroll pass. */
    private void safeRefresh() {
        if (recyclerView == null) {
            notifyDataSetChanged();
            return;
        }
        if (recyclerView.isComputingLayout()) {
            recyclerView.post(this::notifyDataSetChanged);
        } else {
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;
        VH(View v) {
            super(v);
            text = (TextView) v;
        }
    }
}
