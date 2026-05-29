package com.lean.iptv;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * App-level safety net. On a weak box, a stray background error (e.g. a decode
 * OutOfMemoryError) should never take the whole app down. We log it and keep going.
 *
 * NOTE: this is a backstop only. The real fix is in LogoLoader, which already
 * catches Throwable around all bitmap work. This just guards anything we missed.
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Swallow background OOM/Errors so a logo decode can't kill the process.
            if (throwable instanceof OutOfMemoryError
                    && !isMainThread(thread)) {
                Log.w("TarangoApp", "Recovered from background OOM", throwable);
                System.gc();
                return;
            }
            // For anything on the main thread, fall back to the system handler.
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private boolean isMainThread(Thread thread) {
        return thread == Looper.getMainLooper().getThread();
    }
}
