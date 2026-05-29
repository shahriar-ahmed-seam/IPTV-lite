package com.lean.iptv;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-by-line M3U parser tuned for the real playlist.
 *
 * Rules learned from the actual file:
 *  - Channel name = text after the LAST comma on an #EXTINF line.
 *  - group-title="..." gives the category.
 *  - Skip DRM entries: lines whose "name" is actually a #KODIPROP license_key tag.
 *  - Skip .mpd (DASH) entries that are paired with DRM, and any non-http url lines.
 */
public final class M3UParser {

    private M3UParser() {}

    public static List<Channel> parse(String raw) {
        List<Channel> out = new ArrayList<>();
        if (raw == null) return out;

        String[] lines = raw.split("\\r?\\n");
        String pendingName = null;
        String pendingGroup = "Other";
        String pendingLogo = null;

        for (String lineRaw : lines) {
            String line = lineRaw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF")) {
                pendingGroup = extractAttr(line, "group-title");
                if (pendingGroup == null || pendingGroup.isEmpty()) pendingGroup = "Other";

                pendingLogo = extractAttr(line, "tvg-logo");
                if (pendingLogo != null) {
                    pendingLogo = pendingLogo.trim();
                    // Drop obviously broken logo values seen in the playlist.
                    if (!pendingLogo.startsWith("http")) pendingLogo = null;
                }

                int comma = line.lastIndexOf(',');
                pendingName = (comma >= 0 && comma < line.length() - 1)
                        ? line.substring(comma + 1).trim()
                        : "";

                // DRM rows store a #KODIPROP tag where the name should be -> skip them.
                if (pendingName.startsWith("#KODIPROP") || pendingName.contains("license_key")) {
                    pendingName = null;
                }
                continue;
            }

            // Any other #-comment line that isn't a URL: ignore but keep pending name.
            if (line.startsWith("#")) {
                // A standalone #KODIPROP before the url also means DRM -> drop pending.
                if (line.contains("license_key") || line.startsWith("#KODIPROP")) {
                    pendingName = null;
                }
                continue;
            }

            // This is a URL line.
            if (pendingName != null && !pendingName.isEmpty() && isPlayableUrl(line)) {
                out.add(new Channel(pendingName, line, pendingGroup, pendingLogo));
            }
            pendingName = null;
            pendingGroup = "Other";
            pendingLogo = null;
        }
        return out;
    }

    private static boolean isPlayableUrl(String url) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
        // ExoPlayer handles .m3u8, .mpd, .ts and raw progressive streams.
        // DRM .mpd entries were already dropped via the KODIPROP check above.
        return true;
    }

    /** Pulls value of attr="..." from an #EXTINF line. */
    private static String extractAttr(String line, String attr) {
        String key = attr + "=\"";
        int start = line.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = line.indexOf('"', start);
        if (end < 0) return null;
        return line.substring(start, end).trim();
    }
}
