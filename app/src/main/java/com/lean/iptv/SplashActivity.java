package com.lean.iptv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Branding splash. Uses SplashTheme (logo as window background) so it shows the
 * instant the process launches, then warms the channel cache and hands off to the grid.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long MIN_SHOW_MS = 900;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView: the SplashTheme window background already draws the logo.

        // Kick the cache-first load now so channels are ready by the time the grid opens.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(this, GridActivity.class));
            overridePendingTransition(0, 0);
            finish();
        }, MIN_SHOW_MS);
    }
}
