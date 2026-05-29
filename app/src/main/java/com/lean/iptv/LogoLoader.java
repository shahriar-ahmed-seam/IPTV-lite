package com.lean.iptv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny dependency-free image loader for channel logos.
 *
 * Stability first (this runs on a weak 3GB box):
 *  - ALL bitmap work catches Throwable, so an OutOfMemoryError can never crash the app.
 *  - Logos are aggressively downsampled to tile size and decoded as RGB_565 (half memory).
 *  - 2-level cache: memory LRU + persistent PNG files in filesDir (survives relaunch).
 *  - Tag check prevents wrong images on recycled RecyclerView tiles.
 *
 * Caching behaviour:
 *  - load()        : lazy load for a visible tile (memory -> disk -> network).
 *  - prefetchAll() : after first launch, download every logo to disk ONCE in the
 *                    background, so future launches render instantly from disk.
 *                    It decodes one small bitmap at a time and recycles it, so it
 *                    never inflates memory.
 */
public final class LogoLoader {

    private static final int TARGET_PX = 96;        // tile logo target size (px)
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 1_500_000; // skip oversized source images

    private static LogoLoader instance;

    public static LogoLoader get(Context ctx) {
        if (instance == null) instance = new LogoLoader(ctx);
        return instance;
    }

    // Small pool for on-demand (visible) loads while scrolling.
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    // Single dedicated thread for the one-time bulk prefetch (gentle on memory + network).
    private final ExecutorService prefetchPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mem;
    private final File diskDir;

    private volatile boolean prefetchStarted = false;

    private LogoLoader(Context ctx) {
        // Cap the in-memory cache modestly (1/12 of heap) so it can't dominate the heap.
        int cacheKb = (int) Math.max(2048, Runtime.getRuntime().maxMemory() / 1024 / 12);
        mem = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        // filesDir (not cacheDir) so the OS won't wipe it under storage pressure.
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "logos");
        if (!dir.exists()) dir.mkdirs();
        diskDir = dir;
    }

    /** True if this logo's PNG is already saved on disk. */
    public boolean isCachedOnDisk(String url) {
        if (url == null || url.isEmpty()) return false;
        return diskFile(url).exists();
    }

    /**
     * Loads the logo for url into target. Sets the tag so a recycled view
     * that has since been rebound to another channel won't get a stale image.
     */
    public void load(String url, ImageView target) {
        target.setTag(url);
        if (url == null || url.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }

        Bitmap cached = mem.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        target.setImageDrawable(null); // clear while loading
        pool.execute(() -> {
            // Hard safety net: nothing in here may ever crash the app.
            try {
                Bitmap bmp = fromDisk(url);
                if (bmp == null) {
                    bmp = fromNetwork(url);
                    if (bmp != null) toDisk(url, bmp);
                }
                if (bmp == null) return;

                final Bitmap result = bmp;
                try {
                    mem.put(url, result);
                } catch (Throwable ignored) {}

                ui.post(() -> {
                    if (url.equals(target.getTag())) {
                        target.setImageBitmap(result);
                    }
                });
            } catch (Throwable t) {
                // OutOfMemoryError or anything else: drop the logo silently, keep the app alive.
            }
        });
    }

    /**
     * One-time background warm-up: ensures every channel logo is saved to disk.
     * Safe to call on every launch; it only downloads logos not already cached,
     * and runs once per process. Decodes one image at a time and recycles it,
     * so peak memory stays tiny even for hundreds of logos.
     */
    public void prefetchAll(final List<Channel> channels) {
        if (prefetchStarted || channels == null || channels.isEmpty()) return;
        prefetchStarted = true;

        // Snapshot the URLs so the list can't change under us.
        final String[] urls = new String[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            urls[i] = channels.get(i).logo;
        }

        prefetchPool.execute(() -> {
            for (String url : urls) {
                if (url == null || url.isEmpty()) continue;
                if (isCachedOnDisk(url)) continue; // already have it
                try {
                    Bitmap bmp = fromNetwork(url);
                    if (bmp != null) {
                        toDisk(url, bmp);
                        bmp.recycle(); // free immediately; prefetch only needs the disk copy
                    }
                    // Tiny pause keeps the weak box's network + CPU breathing room.
                    Thread.sleep(40);
                } catch (Throwable t) {
                    // Ignore individual failures; keep warming the rest.
                }
            }
        });
    }

    // ---------- disk ----------

    private File diskFile(String url) {
        return new File(diskDir, Integer.toHexString(url.hashCode()) + ".png");
    }

    private Bitmap fromDisk(String url) {
        try {
            File f = diskFile(url);
            if (!f.exists()) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void toDisk(String url, Bitmap bmp) {
        try {
            // Write to a temp file then rename, so a half-written PNG is never read.
            File target = diskFile(url);
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
            fos.close();
            if (!tmp.renameTo(target)) {
                tmp.delete();
            }
        } catch (Throwable ignored) {}
    }

    // ---------- network + downsample ----------

    private Bitmap fromNetwork(String urlStr) {
        try {
            byte[] data = readBytes(urlStr);
            if (data == null) return null;

            // Pass 1: bounds only (allocates no pixels).
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            // Pass 2: decode downsampled + RGB_565 (half the memory of ARGB_8888).
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Throwable t) {
            // Includes OutOfMemoryError -> just return null, no logo, no crash.
            return null;
        }
    }

    private byte[] readBytes(String urlStr) {
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

            InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                total += n;
                if (total > MAX_BYTES) {
                    is.close();
                    return null; // too big, skip rather than risk memory
                }
            }
            is.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private int sampleSize(int w, int h) {
        int sample = 1;
        int larger = Math.max(w, h);
        while (larger / sample > TARGET_PX * 2) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }
}
