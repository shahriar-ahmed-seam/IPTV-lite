package com.lean.iptv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A selectable playlist source shown in the dropdown.
 *  - name      : label in the dropdown
 *  - url       : where to fetch the latest list
 *  - cacheFile : per-source persistent cache filename
 *  - assetFile : bundled seed (instant first launch), or null if none
 */
public final class PlaylistSource {

    public final String name;
    public final String url;
    public final String cacheFile;
    public final String assetFile;

    public PlaylistSource(String name, String url, String cacheFile, String assetFile) {
        this.name = name;
        this.url = url;
        this.cacheFile = cacheFile;
        this.assetFile = assetFile;
    }

    /** The fixed built-in sources. "Source 1" is the default (index 0). */
    public static List<PlaylistSource> builtIn() {
        return Arrays.asList(
            new PlaylistSource(
                "Source 1",
                "https://raw.githubusercontent.com/imShakil/tvlink/main/iptv.m3u8",
                "cache_bangla.m3u8",
                "iptv.m3u8"),
            new PlaylistSource(
                "Source 2",
                "https://raw.githubusercontent.com/ashik4u/mrgify-clean/main/playlist.m3u",
                "cache_sports.m3u8",
                "sports.m3u8"),
            new PlaylistSource(
                "My List",
                "https://raw.githubusercontent.com/shahriar-ahmed-seam/IPTV-lite/main/playlist.m3u",
                "cache_mylist.m3u8",
                "mylist.m3u8")
        );
    }

    /** Built-in sources followed by any user-added custom sources. */
    public static List<PlaylistSource> all(android.content.Context ctx) {
        List<PlaylistSource> list = new ArrayList<>(builtIn());
        if (ctx != null) {
            list.addAll(CustomSources.get(ctx).asSources());
        }
        return list;
    }

    @Override
    public String toString() {
        return name;
    }
}
