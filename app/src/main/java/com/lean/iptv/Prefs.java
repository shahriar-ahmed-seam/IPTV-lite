package com.lean.iptv;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny wrapper for remembering the last-watched channel, category, and source. */
public final class Prefs {
    private static final String NAME = "lean_iptv";
    private static final String KEY_CATEGORY = "last_category";
    private static final String KEY_URL = "last_url";
    private static final String KEY_SOURCE = "last_source_index";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static void saveLast(Context ctx, String category, String url) {
        sp(ctx).edit().putString(KEY_CATEGORY, category).putString(KEY_URL, url).apply();
    }

    public static String lastCategory(Context ctx) {
        return sp(ctx).getString(KEY_CATEGORY, ChannelRepository.ALL);
    }

    public static String lastUrl(Context ctx) {
        return sp(ctx).getString(KEY_URL, null);
    }

    public static void saveSourceIndex(Context ctx, int index) {
        sp(ctx).edit().putInt(KEY_SOURCE, index).apply();
    }

    public static int lastSourceIndex(Context ctx) {
        return sp(ctx).getInt(KEY_SOURCE, 0); // 0 = Bangla TV (default)
    }
}
