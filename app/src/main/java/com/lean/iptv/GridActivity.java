package com.lean.iptv;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Launcher screen: source dropdown + search + favourites + category bar + channel grid. */
public class GridActivity extends AppCompatActivity {

    private static final int GRID_COLUMNS = 4;
    private static final long SEARCH_DEBOUNCE_MS = 220;

    private Spinner sourceSpinner;
    private EditText searchBox;
    private ImageView favButton;
    private ImageView addSourceButton;
    private RecyclerView categoryBar;
    private RecyclerView grid;
    private TextView statusText;

    private CategoryAdapter categoryAdapter;
    private GridAdapter gridAdapter;
    private Favorites favorites;

    private final ChannelRepository repo = ChannelRepository.get();
    private List<PlaylistSource> sources;
    private int currentSourceIndex = 0;

    private String currentCategory = ChannelRepository.ALL;
    private boolean uiBuilt = false;
    private boolean favoritesMode = false;
    private List<String> displayedCategories = null;

    // Guards the spinner's automatic onItemSelected at setup time.
    private boolean spinnerReady = false;

    // Search state
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private boolean searching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grid);

        sourceSpinner = findViewById(R.id.sourceSpinner);
        searchBox = findViewById(R.id.searchBox);
        favButton = findViewById(R.id.favButton);
        addSourceButton = findViewById(R.id.addSourceButton);
        categoryBar = findViewById(R.id.categoryBar);
        grid = findViewById(R.id.grid);
        statusText = findViewById(R.id.statusText);

        favorites = Favorites.get(this);

        categoryBar.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        grid.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        grid.setHasFixedSize(true);

        gridAdapter = new GridAdapter((pos, channel) -> openPlayer(pos));
        gridAdapter.setFavorites(favorites);
        gridAdapter.setStarListener((pos, channel, isFav) -> onStarClicked(channel));
        grid.setAdapter(gridAdapter);

        sources = PlaylistSource.all(this);
        currentSourceIndex = clampSourceIndex(Prefs.lastSourceIndex(this));

        setupSourceSpinner();
        setupSearch();
        favButton.setOnClickListener(v -> toggleFavoritesView());
        addSourceButton.setOnClickListener(v -> showManageSourcesDialog());

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

    /** Rebuild the spinner contents after custom sources change, keeping current selection. */
    private void rebuildSourceSpinner() {
        sources = PlaylistSource.all(this);
        if (currentSourceIndex >= sources.size()) currentSourceIndex = 0;
        ArrayAdapter<PlaylistSource> spinnerAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, sources);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerReady = false; // suppress the programmatic selection callback
        sourceSpinner.setAdapter(spinnerAdapter);
        sourceSpinner.setSelection(currentSourceIndex, false);
    }

    // ---------- custom sources (import / delete) ----------

    private void showManageSourcesDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_add_source, null);
        final EditText nameIn = content.findViewById(R.id.inputName);
        final EditText urlIn = content.findViewById(R.id.inputUrl);
        final android.widget.LinearLayout listBox = content.findViewById(R.id.customList);
        final TextView empty = content.findViewById(R.id.emptyCustom);

        final CustomSources cs = CustomSources.get(this);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add / manage sources")
                .setView(content)
                .setPositiveButton("Add", null) // overridden below to avoid auto-dismiss
                .setNegativeButton("Close", null)
                .create();

        // Populates the list of existing custom sources with delete buttons.
        final Runnable[] refreshList = new Runnable[1];
        refreshList[0] = () -> {
            listBox.removeAllViews();
            java.util.List<String[]> entries = cs.entries();
            empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            for (String[] e : entries) {
                final String name = e[0];
                final String url = e[1];
                View row = getLayoutInflater().inflate(R.layout.custom_source_row, listBox, false);
                ((TextView) row.findViewById(R.id.rowName)).setText(name);
                row.findViewById(R.id.rowDelete).setOnClickListener(v -> {
                    cs.remove(url);
                    rebuildSourceSpinner();
                    refreshList[0].run();
                    Toast.makeText(this, "Removed " + name, Toast.LENGTH_SHORT).show();
                });
                listBox.addView(row);
            }
        };
        refreshList[0].run();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = nameIn.getText().toString().trim();
                String url = urlIn.getText().toString().trim();
                if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    Toast.makeText(this, "Enter a valid http(s) playlist URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (cs.exists(url)) {
                    Toast.makeText(this, "That source is already added", Toast.LENGTH_SHORT).show();
                    return;
                }
                cs.add(name, url);
                rebuildSourceSpinner();
                refreshList[0].run();
                final String addedUrl = url;
                nameIn.setText("");
                urlIn.setText("");
                // Warm just the newly added source in the background (cache its playlist).
                for (PlaylistSource ps : sources) {
                    if (ps.url.equals(addedUrl)) { repo.warmSource(this, ps); break; }
                }
                Toast.makeText(this, "Source added", Toast.LENGTH_SHORT).show();
            });
        });

        dialog.show();
    }

    // ---------- search (scoped to the active source) ----------

    private void setupSearch() {
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                final String q = s.toString();
                // Debounce so we don't refilter on every keystroke (fast + smooth).
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
                searchRunnable = () -> applySearch(q);
                searchHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });
    }

    private void applySearch(String query) {
        if (!uiBuilt) return;
        String q = query == null ? "" : query.trim();

        if (q.isEmpty()) {
            // Exit search mode: restore the current category view.
            if (searching) {
                searching = false;
                showCategory(currentCategory);
            }
            return;
        }

        searching = true;
        List<Channel> results = repo.search(q);
        gridAdapter.setData(results);
        if (results.isEmpty()) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("No channels match \"" + q + "\"");
            grid.setVisibility(View.GONE);
        } else {
            statusText.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
            grid.scrollToPosition(0);
        }
    }

    // ---------- favourites ----------

    /** Star tapped on a tile. Add (with confirm) anywhere; remove (with confirm) in fav view. */
    private void onStarClicked(final Channel channel) {
        if (channel == null) return;
        final boolean isFav = favorites.isFavorite(channel.url);

        if (isFav && favoritesMode) {
            new AlertDialog.Builder(this)
                    .setTitle("Remove favourite")
                    .setMessage("Remove \"" + channel.name + "\" from favourites?")
                    .setPositiveButton("Remove", (d, w) -> {
                        favorites.remove(channel.url);
                        showFavorites(); // refresh the favourites grid
                        Toast.makeText(this, "Removed from favourites", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if (isFav) {
            // Already a favourite (outside fav view): offer to remove too.
            new AlertDialog.Builder(this)
                    .setTitle("Remove favourite")
                    .setMessage("\"" + channel.name + "\" is already in favourites. Remove it?")
                    .setPositiveButton("Remove", (d, w) -> {
                        favorites.remove(channel.url);
                        gridAdapter.notifyDataSetChanged();
                        Toast.makeText(this, "Removed from favourites", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Add favourite")
                    .setMessage("Add \"" + channel.name + "\" to favourites?")
                    .setPositiveButton("Add", (d, w) -> {
                        favorites.add(channel);
                        gridAdapter.notifyDataSetChanged(); // turn its star gold
                        Toast.makeText(this, "Added to favourites", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void toggleFavoritesView() {
        if (favoritesMode) {
            // Leave favourites, back to normal source view.
            favoritesMode = false;
            favButton.setSelected(false);
            showCategory(currentCategory);
        } else {
            showFavorites();
        }
    }

    private void showFavorites() {
        favoritesMode = true;
        favButton.setSelected(true); // visually mark the star button as active
        searching = false;
        if (searchBox.getText().length() > 0) searchBox.setText("");
        // Keep the category bar visible so tapping any category returns to channels.
        categoryBar.setVisibility(View.VISIBLE);

        List<Channel> favs = favorites.list();
        repo.setBucket(ChannelRepository.FAVORITES, favs);
        gridAdapter.setData(favs);

        if (favs.isEmpty()) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("No favourites yet.\nLong-press any channel (or tap its star) to add it,\nthen pick a category above to go back.");
            grid.setVisibility(View.GONE);
        } else {
            statusText.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
            grid.scrollToPosition(0);
            grid.post(() -> {
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
                if (vh != null) vh.itemView.requestFocus();
            });
        }
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
        searching = false;
        favoritesMode = false;
        favButton.setSelected(false);
        categoryBar.setVisibility(View.VISIBLE);
        if (searchBox.getText().length() > 0) searchBox.setText("");
        loadSource(source, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Don't disrupt favourites/search views when returning from the player.
        if (uiBuilt && !favoritesMode && !searching) {
            String last = Prefs.lastCategory(this);
            if (!last.equals(currentCategory) && repo.getCategories().contains(last)) {
                currentCategory = last;
                categoryAdapter.setSelectedCategory(last);
                showCategory(last);
            }
        }
        // Star states may have changed; refresh tile stars.
        if (uiBuilt) gridAdapter.notifyDataSetChanged();
    }

    @Override
    public void onBackPressed() {
        // BACK exits favourites or search first, instead of closing the app.
        if (favoritesMode) {
            toggleFavoritesView();
            return;
        }
        if (searching) {
            searchBox.setText("");
            return;
        }
        super.onBackPressed();
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

        // Don't clobber the favourites or search view if the user is in one.
        if (favoritesMode) {
            return;
        }
        if (searching) {
            applySearch(searchBox.getText().toString());
            return;
        }

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
        // Selecting a category exits search + favourites modes.
        favoritesMode = false;
        favButton.setSelected(false);
        if (categoryBar.getVisibility() != View.VISIBLE) categoryBar.setVisibility(View.VISIBLE);
        if (searching) {
            searching = false;
            if (searchBox.getText().length() > 0) searchBox.setText("");
        }
        // Always restore the grid (it may have been hidden by an empty fav/search state).
        statusText.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
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
        // Pick the right channel bucket so the player zaps through the visible list.
        String cat;
        if (favoritesMode) {
            cat = ChannelRepository.FAVORITES;
        } else if (searching) {
            cat = ChannelRepository.SEARCH;
        } else {
            cat = currentCategory;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_CATEGORY, cat);
        i.putExtra(PlayerActivity.EXTRA_INDEX, positionInCategory);
        startActivity(i);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
    }
}
