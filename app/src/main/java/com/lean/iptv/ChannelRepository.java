package com.lean.iptv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Single source of truth for the channel list, shared by the grid and the player.
 *
 * Multi-source: the user picks a playlist source (Bangla TV / Sports / My List).
 * Each source has its own persistent cache file + bundled asset seed.
 *
 * Loading strategy per source (instant + always fresh):
 *   1. Show that source's local copy IMMEDIATELY (its disk cache, or bundled asset).
 *   2. Fetch the latest playlist for that source in the BACKGROUND.
 *   3. If it changed, update the cache and notify the UI to refresh.
 *
 * On first launch we also warm ALL sources (download each playlist file + its logos)
 * so switching sources later is instant.
 */
public final class ChannelRepository {

    public static final String ALL = "All";
    private static final int TIMEOUT_MS = 12000;

    private static ChannelRepository instance;

    public static ChannelRepository get() {
        if (instance == null) instance = new ChannelRepository();
        return instance;
    }

    private final List<Channel> all = new ArrayList<>();
    private final LinkedHashMap<String, List<Channel>> byCategory = new LinkedHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String loadedRaw = null;          // raw text currently displayed
    private PlaylistSource currentSource = null;
    private volatile boolean warmStarted = false;

    private ChannelRepository() {}

    public interface Listener {
        void onReady(int count, boolean fromCache);
        void onUpdated(int count);
        void onEmpty();
    }

    public boolean isLoaded() {
        return !all.isEmpty();
    }

    public PlaylistSource getCurrentSource() {
        return currentSource;
    }

    public List<String> getCategories() {
        return new ArrayList<>(byCategory.keySet());
    }

    public List<Channel> getChannels(String category) {
        List<Channel> l = byCategory.get(category);
        return l != null ? l : new ArrayList<>();
    }

    /**
     * Loads a specific source: instant local copy first, then background refresh.
     * Switching sources also routes through here.
     */
    public void load(Context ctx, final PlaylistSource source, final Listener listener) {
        final Context app = ctx.getApplicationContext();
        currentSource = source;

        // ---- Step 1: instant local load (this source's cache, then its asset) ----
        String local = readFile(new File(app.getFilesDir(), source.cacheFile));
        boolean fromCache = local != null && local.contains("#EXTINF");
        if (!fromCache) {
            local = readAsset(app, source.assetFile);
        }

        if (local != null && local.contains("#EXTINF")) {
            List<Channel> parsed = M3UParser.parse(local);
            if (!parsed.isEmpty()) {
                build(parsed);
                loadedRaw = local;
                listener.onReady(parsed.size(), fromCache);
            } else {
                loadedRaw = null;
            }
        } else {
            // No local copy for this source yet; clear current view until network returns.
            loadedRaw = null;
            all.clear();
            byCategory.clear();
        }

        // ---- Step 2: background network refresh for this source ----
        final String shownRaw = loadedRaw;
        new Thread(() -> {
            String raw = fetch(source.url);
            if (raw == null || !raw.contains("#EXTINF")) {
                if (shownRaw == null) ui.post(listener::onEmpty);
                return;
            }
            if (raw.equals(shownRaw)) {
                writeFile(new File(app.getFilesDir(), source.cacheFile), raw);
                return;
            }
            final List<Channel> parsed = M3UParser.parse(raw);
            if (parsed.isEmpty()) {
                if (shownRaw == null) ui.post(listener::onEmpty);
                return;
            }
            writeFile(new File(app.getFilesDir(), source.cacheFile), raw);
            ui.post(() -> {
                // Only apply if the user hasn't switched to another source meanwhile.
                if (currentSource == source) {
                    build(parsed);
                    loadedRaw = raw;
                    listener.onUpdated(parsed.size());
                }
            });
        }, "playlist-refresh").start();
    }

    /**
     * First-launch warm-up for EVERY source: downloads each playlist file to its cache
     * and prefetches its logos, so switching sources is instant later.
     * Runs once per process, on a single background thread, memory-safe.
     */
    public void warmAllSources(Context ctx, final List<PlaylistSource> sources) {
        if (warmStarted) return;
        warmStarted = true;
        final Context app = ctx.getApplicationContext();

        new Thread(() -> {
            for (PlaylistSource s : sources) {
                try {
                    // Skip the one already loaded fresh by load()'s own refresh if cached.
                    String raw = fetch(s.url);
                    if (raw != null && raw.contains("#EXTINF")) {
                        writeFile(new File(app.getFilesDir(), s.cacheFile), raw);
                        List<Channel> parsed = M3UParser.parse(raw);
                        // Warm this source's logos to disk (lazy-safe, one at a time).
                        LogoLoader.get(app).prefetchAll(parsed);
                    }
                    Thread.sleep(50);
                } catch (Throwable ignored) {
                    // Keep warming the rest regardless of any single failure.
                }
            }
        }, "warm-all-sources").start();
    }

    private void build(List<Channel> parsed) {
        all.clear();
        all.addAll(parsed);
        byCategory.clear();
        byCategory.put(ALL, new ArrayList<>(parsed));
        for (Channel c : parsed) {
            List<Channel> l = byCategory.get(c.group);
            if (l == null) {
                l = new ArrayList<>();
                byCategory.put(c.group, l);
            }
            l.add(c);
        }
    }

    // ---------- network + files ----------

    private String fetch(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.connect();
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void writeFile(File f, String content) {
        try {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(content.getBytes());
            fos.close();
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable ignored) {}
    }

    private String readFile(File f) {
        try {
            if (!f.exists()) return null;
            return readStream(new FileInputStream(f));
        } catch (Throwable e) {
            return null;
        }
    }

    private String readAsset(Context ctx, String assetFile) {
        if (assetFile == null) return null;
        try {
            InputStream is = ctx.getAssets().open(assetFile);
            return readStream(is);
        } catch (Throwable e) {
            return null;
        }
    }

    private String readStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }
}
