# Tarango - Ultra-Lightweight IPTV for Android TV and Desktop

Tarango is an ultra-lightweight, high-performance IPTV player built for low-specification Android TV boxes and modern desktop workstations (Linux and Windows). 

The primary design goal is rock-solid stability on legacy hardware (such as Amlogic S905/S912, Rockchip, and Allwinner chipsets running Android 5.0 to Android 7.1 with 1 GB RAM or less) where modern streaming applications fail due to memory bloat, heavy WebViews, and Google Play Services dependencies. Tarango bypasses these bottlenecks entirely, streaming directly through native hardware decoders with minimal memory footprint.

In addition to Android TV, Tarango provides a dedicated desktop application with full window controls, keyboard shortcuts, and a resizable floating Picture-in-Picture (PiP) pop-up window for multitasking.

---

## Downloads

Download pre-built binaries directly from the GitHub Releases page:

1. **Android TV APK (Primary Target)**
   - `tarango-android-tv.apk`: Universal APK compatible with Android 5.0+ (API level 21 through 35).
   - Designed for low-spec TV boxes, smart TVs, TV sticks, and mobile devices.

2. **Linux Desktop**
   - `Tarango-*.AppImage`: Self-contained portable executable for all modern Linux distributions.
   - `tarango-desktop_*_amd64.deb`: Debian, Ubuntu, and Linux Mint native package.

3. **Windows Desktop**
   - `Tarango Setup *.exe`: 64-bit installer with desktop and start menu shortcuts.

---

## Features

### Android TV Application
- **Hardware-Accelerated Playback**: Built on AndroidX Media3 ExoPlayer with direct HLS (.m3u8) and DASH (.mpd) support.
- **TV Remote Navigation**: Intuitive grid layout fully controllable using only a standard D-pad TV remote.
- **Quick Channel Zapping**: Switch channels instantly using Up and Down remote keys while playing fullscreen.
- **Number-Key Channel Jump**: Direct channel number dialing with on-screen confirmation banner.
- **Multiple Playlist Sources**:
  - Bangladeshi Channels (Default)
  - Sports & International Channels
  - My List (Repository-managed custom M3U playlist)
  - Custom URLs added directly inside the application
- **Auto-Update Mechanism**: Playlists refresh on startup in the background; changes take effect without reinstalling.
- **Zero-Dependency Asset Cache**: Fast disk-cached logo loader engineered with strict memory bounds to prevent Out-Of-Memory (OOM) crashes on 1 GB RAM devices.
- **Screen Wake Lock**: Prevents low-spec devices and mobile phones from going into sleep mode during playback.

### Desktop Application (Linux & Windows)
- **Picture-in-Picture Pop-Up Viewer**:
  - Detach playback into a separate, resizable floating window.
  - Pin on top of all windows (Always on Top toggle) to keep watching streams while working or browsing.
  - One-click minimize for the main application window.
  - One-key restore back into the main application.
- **Dedicated Fullscreen Controls**: Instant toggle via interface buttons, video double-click, or `F` / `F11` hotkeys.
- **Integrated Playback Controls**: Full volume control with slider, mute toggle, play/pause, and channel cycling.
- **Full Keyboard Navigation**: Comprehensive keyboard shortcuts for every action.
- **Cross-Platform Packaging**: Native Linux AppImage, DEB package, and Windows NSIS installer.

---

## Desktop Keyboard Shortcuts

| Shortcut | Context | Action |
|---|---|---|
| `Space` | Player & PiP | Play / Pause stream |
| `F` or `F11` | Main Player | Toggle Fullscreen mode |
| `P` | Main Player | Open in Resizable Picture-in-Picture Pop-up Viewer |
| `R` | PiP Window | Restore stream to Main Player window |
| `M` | Player & PiP | Toggle Mute / Unmute audio |
| `Arrow Up` or `[` | Player & PiP | Switch to previous channel |
| `Arrow Down` or `]` | Player & PiP | Switch to next channel |
| `Arrow Left` | Player & PiP | Decrease volume |
| `Arrow Right` | Player & PiP | Increase volume |
| `Escape` | Main Player | Exit fullscreen (or close player if not fullscreen) |
| `Escape` | PiP Window | Close Picture-in-Picture window |
| `Double Click` | Video Area | Toggle Fullscreen (Main) / Toggle Aspect Ratio (PiP) |

---

## Installation Guide

### Sideloading on Android TV Boxes

1. Download `tarango-android-tv.apk` from the latest GitHub Release.
2. Copy the APK file to a USB flash drive.
3. Insert the USB drive into your Android TV box.
4. Open the TV box file manager, locate `tarango-android-tv.apk`, and select it.
5. If prompted, grant permission to install from Unknown Sources.
6. Launch Tarango from your TV box app drawer.

### Linux Desktop Installation

- **Using the DEB package (Debian / Ubuntu / Mint)**:
  ```bash
  sudo dpkg -i tarango-desktop_*_amd64.deb
  sudo apt-get install -f  # Resolves any missing system dependencies
  ```

- **Using the portable AppImage**:
  ```bash
  chmod +x Tarango-*.AppImage
  ./Tarango-*.AppImage
  ```

### Windows Desktop Installation

1. Download and run `Tarango Setup *.exe`.
2. Follow the standard installation wizard.
3. Launch "Tarango IPTV" from your Start Menu or Desktop shortcut.

---

## Managing Custom Playlists

The application supports standard M3U and M3U8 playlists. To customize the built-in "My List" source:

1. Open `playlist.m3u` in this repository.
2. Add your channels in standard extended M3U format:
   ```text
   #EXTINF:-1 tvg-logo="https://example.com/logo.png" group-title="News",Channel Name
   https://stream.example.com/live/stream.m3u8
   ```
3. Commit and push your changes to GitHub.
4. The next time the application opens, it automatically pulls the latest playlist.

---

## Building from Source

### Prerequisites
- Android Build: JDK 17, Android SDK with API 35 build tools.
- Desktop Build: Node.js (v20+ or v22+) and npm.

### Building the Android APK
Run Gradle from the root repository directory:
```bash
./gradlew assembleRelease
```
The compiled, signed release APK is output to:
`app/build/outputs/apk/release/app-release.apk`

### Building Desktop Binaries

Navigate to the `tarango-desktop/` directory:
```bash
cd tarango-desktop
npm install
```

- **Run in development mode**:
  ```bash
  npm start
  ```

- **Build Linux AppImage and DEB package**:
  ```bash
  npm run build:linux
  ```

- **Build Windows NSIS installer**:
  ```bash
  npm run build:win
  ```

Output binaries are generated in `tarango-desktop/dist/`.

---

## CI/CD Pipeline

The repository includes a GitHub Actions automated release pipeline (`.github/workflows/release.yml`). 

Whenever a release tag is pushed (e.g. `v1.3.0`) or triggered manually via workflow dispatch:
1. `build-android` compiles the optimized Android TV release APK via Gradle and JDK 17.
2. `build-desktop-linux` builds the Linux AppImage and DEB package.
3. `build-desktop-windows` runs on a Windows runner to produce the native Windows installer.
4. `release` publishes all binaries to GitHub Releases, placing the low-spec Android TV APK at the top of the release assets.

---

## Project Structure

```text
app/                      Android application module (lean, no WebView)
  src/main/java/com/lean/iptv/
    SplashActivity.java    Branded splash, routes to grid
    GridActivity.java      Source dropdown, category bar, channel grid
    PlayerActivity.java    Fullscreen ExoPlayer with zapping and number-jump
    ChannelRepository.java Multi-source cache-first loading and auto-update
    PlaylistSource.java    Selectable playlist source definitions
    M3UParser.java         M3U/M3U8 parser
    GridAdapter.java       Channel tile adapter
    CategoryAdapter.java   Category chip adapter
    LogoLoader.java        Memory-safe disk cache and prefetch loader
    Prefs.java             Persistent preferences
    App.java               Application-level exception net
  src/main/assets/         Bundled fallback playlists
tarango-desktop/          Desktop Electron application
  main.js                  Main process, window management, and PiP IPC
  index.html               Main interface markup
  styles.css               Desktop styling, glassmorphism, responsive grid
  app.js                   Renderer logic, Hls.js stream player, hotkeys
  pip.html                 Picture-in-Picture floating window markup
  pip.js                   PiP stream engine and window synchronization
  pip.css                  Compact borderless PiP styling
.github/workflows/
  release.yml              Multi-platform automated build and release pipeline
playlist.m3u              Repository-controlled custom M3U playlist
```

---

## License & Disclaimer

This project is licensed under the MIT License.

This software does not host, broadcast, or distribute any media streams. It functions solely as a lightweight playback interface for publicly available M3U playlist streams configured by the user. Stream availability and quality depend entirely on third-party sources.
