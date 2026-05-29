# Tarango — Lean Android TV IPTV

A single-purpose, ultra-lightweight Android TV / Android box IPTV player. Built to run
smoothly on weak, low-RAM boxes (e.g. Amlogic S912, Android 7.1, no Google Play Services)
where heavier apps like Toffee, Jagobd, or Bioscope struggle. No WebView, no Play Services,
no DRM dependency — just Media3 ExoPlayer streaming directly, with a clean D-pad-friendly grid.

## Features

- **Channel grid** with category filtering, navigable entirely by the TV remote D-pad.
- **Fullscreen player** with channel zapping (Up/Down), number-key jump, and a channel-name banner.
- **Multiple playlist sources** selectable from a dropdown:
  - **Bangla TV** — Bangladeshi channels (default)
  - **Sports & More** — sports + general channels
  - **My List** — this repo's `playlist.m3u`, which you control
- **Auto-update**: each source is fetched fresh on launch. Add a channel to the playlist and it
  appears in the app the next time it opens — no reinstall needed.
- **Instant loading**: shows a cached/bundled list immediately, refreshes in the background.
- **Logo prefetch + caching**: channel logos download once and are cached to disk for instant reuse.
- **Stable on weak hardware**: aggressive memory safety, all image work guarded against OOM.

## The "My List" playlist

The app's **My List** source loads [`playlist.m3u`](./playlist.m3u) from this repository.
To add or change channels, edit that file and push. Each channel is two lines:

```
#EXTINF:-1 tvg-logo="LOGO_URL" group-title="CATEGORY",Channel Name
STREAM_URL
```

- `group-title` becomes a category chip in the app.
- `tvg-logo` is optional (tile shows the name if missing/broken).
- HLS (`.m3u8`), DASH (`.mpd`), and direct streams are supported.
- DRM-protected entries (`#KODIPROP` / `license_key`) are skipped automatically.

Changes are picked up automatically the next time the app launches.

## Project structure

```
app/                      Android app module (single-activity-style, lean)
  src/main/java/com/lean/iptv/
    SplashActivity.java    Branded splash, routes to the grid
    GridActivity.java      Source dropdown + category bar + channel grid
    PlayerActivity.java    Fullscreen ExoPlayer with zapping & number-jump
    ChannelRepository.java Multi-source cache-first loading + auto-update
    PlaylistSource.java    The selectable sources
    M3UParser.java         Line-by-line M3U/M3U8 parser
    GridAdapter.java       Channel tiles
    CategoryAdapter.java   Category chips
    LogoLoader.java        Dependency-free logo loader + disk cache + prefetch
    Prefs.java             Remembers last source / category
    App.java               App-level crash safety net
  src/main/assets/         Bundled seed playlists (instant first launch)
playlist.m3u              The "My List" source (edit to add your channels)
```

## Building

Open in Android Studio and Run, or from the command line:

```
gradlew assembleRelease
```

The signed APK is produced at `app/build/outputs/apk/release/app-release.apk`.
Sideload it onto the TV box (USB + file manager, allow unknown sources).

- `minSdk 21` (Android 5.0+), targets modern SDK but stays backward compatible.
- Built with Media3 ExoPlayer (HLS + DASH).

## Disclaimer

This app does not host, provide, or endorse any stream. It only plays publicly available
playlist links configured by the user. Channel availability depends on the upstream sources.
