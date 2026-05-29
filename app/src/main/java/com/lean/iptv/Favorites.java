package com.lean.iptv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Favorites store. Keyed by channel URL (stable unique id across all sources).
 *
 * Each favorite is persisted as a single line: url|name|group|logo  (URL-first so the
 * key is easy to extract). Stored in SharedPreferences as an ordered string set plus a
 * details map, so the Favorites tab can render channels without their source being loaded.
 */
public final class Favorites {

    private static final String NAME = "lean_iptv_favs";
    private static final String KEY_SET = "fav_lines";
    private static final String SEP = "\u0001"; // unlikely to appear in URLs/names

    private static Favorites instance;
    private final SharedPreferences sp;
    private final LinkedHashSet<String> lines = new LinkedHashSet<>(); // insertion order
    private final Set<String> urls = new LinkedHashSet<>();

    public static Favorites get(Context ctx) {
        if (instance == null) instance = new Favorites(ctx);
        return instance;
    }

    private Favorites(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        Set<String> stored = sp.getStringSet(KEY_SET, null);
        // We persist as a single ordered string (StringSet loses order on some ROMs),
        // so prefer the ordered backup if present.
        String ordered = sp.getString(KEY_SET + "_ordered", null);
        if (ordered != null && !ordered.isEmpty()) {
            for (String line : ordered.split("\n")) {
                if (!line.trim().isEmpty()) addLine(line);
            }
        } else if (stored != null) {
            for (String line : stored) addLine(line);
        }
    }

    private void addLine(String line) {
        lines.add(line);
        String url = urlOf(line);
        if (url != null) urls.add(url);
    }

    private String urlOf(String line) {
        int i = line.indexOf(SEP);
        return i >= 0 ? line.substring(0, i) : line;
    }

    public boolean isFavorite(String url) {
        return url != null && urls.contains(url);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public void add(Channel c) {
        if (c == null || c.url == null || isFavorite(c.url)) return;
        String line = c.url + SEP + safe(c.name) + SEP + safe(c.group) + SEP + safe(c.logo);
        lines.add(line);
        urls.add(c.url);
        persist();
    }

    public void remove(String url) {
        if (url == null) return;
        String toRemove = null;
        for (String line : lines) {
            if (url.equals(urlOf(line))) { toRemove = line; break; }
        }
        if (toRemove != null) {
            lines.remove(toRemove);
            urls.remove(url);
            persist();
        }
    }

    /** All favorites as Channel objects, in the order they were added. */
    public List<Channel> list() {
        List<Channel> out = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split(SEP, -1);
            if (parts.length >= 1 && !parts[0].isEmpty()) {
                String url = parts[0];
                String name = parts.length > 1 ? parts[1] : url;
                String group = parts.length > 2 ? parts[2] : "Favorites";
                String logo = parts.length > 3 ? parts[3] : "";
                out.add(new Channel(name, url, group, logo.isEmpty() ? null : logo));
            }
        }
        return out;
    }

    private void persist() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        sp.edit()
                .putString(KEY_SET + "_ordered", sb.toString())
                .putStringSet(KEY_SET, new LinkedHashSet<>(lines))
                .apply();
    }

    private String safe(String s) {
        if (s == null) return "";
        // Strip the separator char just in case it ever appears.
        return s.replace(SEP, " ");
    }
}
