package com.lean.iptv;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.List;

/**
 * Fullscreen player. Opened from the grid with a category + index.
 *  - UP / DOWN  : previous / next channel (zapping)
 *  - Number keys: jump to a channel number within the category
 *  - BACK       : return to the grid
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_INDEX = "index";

    private static final int TIMEOUT_MS = 12000;
    private static final long BANNER_MS = 3000;
    private static final long NUMBER_COMMIT_MS = 1500;

    private PlayerView playerView;
    private TextView channelBanner;
    private TextView numberEntry;
    private TextView playerStatus;

    private ExoPlayer player;

    private List<Channel> channels;
    private String category;
    private int index;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder numberBuffer = new StringBuilder();

    private final Runnable hideBanner = () -> channelBanner.setVisibility(View.GONE);
    private final Runnable commitNumber = this::commitNumberJump;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // Keep the screen awake while watching (phones/tablets sleep on idle; TV boxes don't).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.playerView);
        channelBanner = findViewById(R.id.channelBanner);
        numberEntry = findViewById(R.id.numberEntry);
        playerStatus = findViewById(R.id.playerStatus);

        category = getIntent().getStringExtra(EXTRA_CATEGORY);
        if (category == null) category = ChannelRepository.ALL;
        index = getIntent().getIntExtra(EXTRA_INDEX, 0);

        channels = ChannelRepository.get().getChannels(category);
        if (channels.isEmpty()) {
            finish();
            return;
        }
        if (index < 0 || index >= channels.size()) index = 0;
    }

    @Override
    protected void onStart() {
        super.onStart();
        initPlayer();
        playCurrent();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    // ---------- player ----------

    private void initPlayer() {
        if (player != null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(TIMEOUT_MS)
                .setReadTimeoutMs(TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Mozilla/5.0 (Linux; Android 7.1; TV) ExoPlayer");

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                playerStatus.setVisibility(View.VISIBLE);
                playerStatus.setText("Channel unavailable. Press UP/DOWN for another.");
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    playerStatus.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    playerStatus.setVisibility(View.VISIBLE);
                    playerStatus.setText("Buffering...");
                }
            }
        });
    }

    private void playCurrent() {
        Channel c = channels.get(index);
        playerStatus.setVisibility(View.VISIBLE);
        playerStatus.setText("Opening " + c.name + "...");
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        player.setPlayWhenReady(true);

        showBanner((index + 1) + ".  " + c.name);
        Prefs.saveLast(this, category, c.url);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ---------- zapping + banner ----------

    private void nextChannel() {
        index = (index + 1) % channels.size();
        playCurrent();
    }

    private void prevChannel() {
        index = (index - 1 + channels.size()) % channels.size();
        playCurrent();
    }

    private void showBanner(String text) {
        channelBanner.setText(text);
        channelBanner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, BANNER_MS);
    }

    // ---------- number jump ----------

    private void onDigit(int digit) {
        numberBuffer.append(digit);
        numberEntry.setText(numberBuffer.toString());
        numberEntry.setVisibility(View.VISIBLE);
        ui.removeCallbacks(commitNumber);
        ui.postDelayed(commitNumber, NUMBER_COMMIT_MS);
    }

    private void commitNumberJump() {
        numberEntry.setVisibility(View.GONE);
        if (numberBuffer.length() == 0) return;
        int num;
        try {
            num = Integer.parseInt(numberBuffer.toString());
        } catch (NumberFormatException e) {
            numberBuffer.setLength(0);
            return;
        }
        numberBuffer.setLength(0);
        if (num >= 1 && num <= channels.size()) {
            index = num - 1;
            playCurrent();
        } else {
            showBanner("No channel " + num);
        }
    }

    // ---------- remote keys ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                prevChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                nextChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Re-show the channel banner on OK.
                showBanner((index + 1) + ".  " + channels.get(index).name);
                return true;
            default:
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    onDigit(keyCode - KeyEvent.KEYCODE_0);
                    return true;
                }
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
    }
}
