package com.lean.iptv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * User-added playlist sources, persisted on the device. Each is a name + URL the
 * user imports (e.g. their own GitHub playlist). They behave exactly like the
 * built-in sources: cached, auto-updated, selectable from the dropdown.
 *
 * Stored as lines "name\u0001url" in SharedPreferences (order preserved).
 */
public final class CustomSources {

    private static final String NAME = "lean_iptv_sources";
    private static final String KEY = "custom_lines";
    private static final String SEP = "\u0001";

    private static CustomSources instance;
    private final SharedPreferences sp;
    private final List<String[]> entries = new ArrayList<>(); // [name, url]

    public static CustomSources get(Context ctx) {
        if (instance == null) instance = new CustomSources(ctx);
        return instance;
    }

    private CustomSources(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String stored = sp.getString(KEY, "");
        if (stored != null && !stored.isEmpty()) {
            for (String line : stored.split("\n")) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(SEP, 2);
                if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                    entries.add(new String[]{ parts[0], parts[1] });
                }
            }
        }
    }

    /** Build PlaylistSource objects for each custom entry (no bundled asset seed). */
    public List<PlaylistSource> asSources() {
        List<PlaylistSource> out = new ArrayList<>();
        for (String[] e : entries) {
            String name = e[0];
            String url = e[1];
            // Stable cache filename derived from the URL hash.
            String cache = "cache_custom_" + Integer.toHexString(url.hashCode()) + ".m3u8";
            out.add(new PlaylistSource(name, url, cache, null));
        }
        return out;
    }

    public List<String[]> entries() {
        return new ArrayList<>(entries);
    }

    public boolean exists(String url) {
        for (String[] e : entries) if (e[1].equals(url)) return true;
        return false;
    }

    public void add(String name, String url) {
        if (name == null) name = "";
        name = name.trim().replace(SEP, " ").replace("\n", " ");
        url = url == null ? "" : url.trim();
        if (name.isEmpty()) name = "My Source";
        if (url.isEmpty() || exists(url)) return;
        entries.add(new String[]{ name, url });
        persist();
    }

    public void remove(String url) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i)[1].equals(url)) {
                entries.remove(i);
                persist();
                return;
            }
        }
    }

    private void persist() {
        StringBuilder sb = new StringBuilder();
        for (String[] e : entries) {
            sb.append(e[0]).append(SEP).append(e[1]).append('\n');
        }
        sp.edit().putString(KEY, sb.toString()).apply();
    }
}
