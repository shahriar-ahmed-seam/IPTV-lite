package com.lean.iptv;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Launcher screen: source dropdown + category bar + channel grid. */
public class GridActivity extends AppCompatActivity {

    private static final int GRID_COLUMNS = 4;

    private Spinner sourceSpinner;
    private RecyclerView categoryBar;
    private RecyclerView grid;
    private TextView statusText;

    private CategoryAdapter categoryAdapter;
    private GridAdapter gridAdapter;

    private final ChannelRepository repo = ChannelRepository.get();
    private List<PlaylistSource> sources;
    private int currentSourceIndex = 0;

    private String currentCategory = ChannelRepository.ALL;
    private boolean uiBuilt = false;
    private List<String> displayedCategories = null;

    // Guards the spinner's automatic onItemSelected at setup time.
    private boolean spinnerReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grid);

        sourceSpinner = findViewById(R.id.sourceSpinner);
        categoryBar = findViewById(R.id.categoryBar);
        grid = findViewById(R.id.grid);
        statusText = findViewById(R.id.statusText);

        categoryBar.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        grid.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        grid.setHasFixedSize(true);

        gridAdapter = new GridAdapter((pos, channel) -> openPlayer(pos));
        grid.setAdapter(gridAdapter);

        sources = PlaylistSource.all();
        currentSourceIndex = clampSourceIndex(Prefs.lastSourceIndex(this));

        setupSourceSpinner();

        // Warm every source's playlist + logos once, so switching is instant later.
        repo.warmAllSources(this, sources);

        // Load the remembered source.
        loadSource(sources.get(currentSourceIndex), false);
    }

    private int clampSourceIndex(int idx) {
        if (idx < 0 || idx >= sources.size()) return 0;
        return idx;
    }

    private void setupSourceSpinner() {
        ArrayAdapter<PlaylistSource> spinnerAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, sources);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        sourceSpinner.setAdapter(spinnerAdapter);
        sourceSpinner.setSelection(currentSourceIndex, false);

        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Ignore the initial programmatic selection.
                if (!spinnerReady) {
                    spinnerReady = true;
                    return;
                }
                if (position == currentSourceIndex) return;
                currentSourceIndex = position;
                Prefs.saveSourceIndex(GridActivity.this, position);
                // Reset category memory when switching sources.
                currentCategory = ChannelRepository.ALL;
                switchToSource(sources.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** Load (or reload) a source via the repository's cache-first pipeline. */
    private void loadSource(final PlaylistSource source, final boolean isSwitch) {
        if (!repo.isLoaded() || isSwitch) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("Loading " + source.name + "...");
            grid.setVisibility(View.GONE);
        }

        repo.load(this, source, new ChannelRepository.Listener() {
            @Override
            public void onReady(int count, boolean fromCache) {
                buildUi(false);
            }

            @Override
            public void onUpdated(int count) {
                refreshAfterUpdate();
            }

            @Override
            public void onEmpty() {
                statusText.setVisibility(View.VISIBLE);
                grid.setVisibility(View.GONE);
                statusText.setText("Could not load " + source.name + ".\nCheck the internet connection.");
            }
        });
    }

    /** User picked a different source from the dropdown. */
    private void switchToSource(PlaylistSource source) {
        uiBuilt = false;
        displayedCategories = null;
        loadSource(source, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (uiBuilt) {
            String last = Prefs.lastCategory(this);
            if (!last.equals(currentCategory) && repo.getCategories().contains(last)) {
                currentCategory = last;
                categoryAdapter.setSelectedCategory(last);
                showCategory(last);
            }
        }
    }

    private void buildUi(boolean offline) {
        statusText.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);

        List<String> cats = repo.getCategories();
        categoryAdapter = new CategoryAdapter(cats, this::showCategory);
        categoryBar.setAdapter(categoryAdapter);
        displayedCategories = cats;

        if (!cats.contains(currentCategory)) currentCategory = ChannelRepository.ALL;
        categoryAdapter.setSelectedCategory(currentCategory);
        showCategory(currentCategory);

        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
        });

        uiBuilt = true;

        // Warm the current source's logos so they render instantly while browsing.
        LogoLoader.get(this).prefetchAll(repo.getChannels(ChannelRepository.ALL));

        if (offline) {
            Toast.makeText(this, "Offline: showing saved channels", Toast.LENGTH_SHORT).show();
        }
    }

    /** Background update arrived: refresh data but keep the user's place + focus. */
    private void refreshAfterUpdate() {
        int focusedPos = -1;
        View focused = grid.getFocusedChild();
        if (focused != null) {
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(focused);
            if (vh != null) focusedPos = vh.getAdapterPosition();
        }

        List<String> cats = repo.getCategories();

        if (categoryAdapter == null || !cats.equals(displayedCategories)) {
            categoryAdapter = new CategoryAdapter(cats, this::showCategory);
            categoryBar.setAdapter(categoryAdapter);
            displayedCategories = cats;
        }
        if (!cats.contains(currentCategory)) currentCategory = ChannelRepository.ALL;
        categoryAdapter.setSelectedCategory(currentCategory);

        gridAdapter.setData(repo.getChannels(currentCategory));

        final int restore = focusedPos;
        if (restore >= 0) {
            final int clamped = Math.min(restore, Math.max(0, gridAdapter.getItemCount() - 1));
            grid.post(() -> {
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(clamped);
                if (vh != null) vh.itemView.requestFocus();
            });
        }
    }

    private void showCategory(String category) {
        currentCategory = category;
        if (grid.isComputingLayout()) {
            grid.post(() -> {
                gridAdapter.setData(repo.getChannels(category));
                grid.scrollToPosition(0);
            });
        } else {
            gridAdapter.setData(repo.getChannels(category));
            grid.scrollToPosition(0);
        }
    }

    private void openPlayer(int positionInCategory) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_CATEGORY, currentCategory);
        i.putExtra(PlayerActivity.EXTRA_INDEX, positionInCategory);
        startActivity(i);
    }
}
