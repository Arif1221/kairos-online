<div align="center">

<img src="docs/store/icon-512.png" width="112" alt="Kairos app icon" />

# Kairos Online

**Kairos, plus YouTube Music and YouTube (audio only): streaming, Discover, offline caching and synced lyrics, in the same player.**

[![Latest release](https://img.shields.io/github/v/release/Arif1221/kairos-online?label=release&color=8B6CFF)](https://github.com/Arif1221/kairos-online/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/Arif1221/kairos-online/build.yml?branch=main)](https://github.com/Arif1221/kairos-online/actions/workflows/build.yml)
[![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

[Download the APK](https://github.com/Arif1221/kairos-online/releases) · [Report a bug](https://github.com/Arif1221/kairos-online/issues/new?template=bug_report.yml) · [Request a feature](https://github.com/Arif1221/kairos-online/issues/new?template=feature_request.yml)

</div>

> This is **Kairos Online**, in its own repository. The offline app (Play Store) is [Arif1221/kairos](https://github.com/Arif1221/kairos). Offline features are built there and merged in here; nothing goes back.

Kairos Online plays the music already on your phone (MP3, FLAC, M4A/AAC, OGG, Opus, WAV) and streams from YouTube Music and YouTube. No account, no ads, no tracking. It is built with Jetpack Compose and Media3 (ExoPlayer).

<!-- Screenshots: add phone screenshots to docs/screenshots/ and list them here, e.g.
<p align="center"><img src="docs/screenshots/player.png" width="22%" alt="Now playing" /></p> -->

## Features

**Lyrics**
- Time-synced lyrics with a karaoke-style fill that moves line by line, start to finish
- Reads lyrics embedded in the file (MP3 `USLT`/`SYLT`, FLAC Vorbis comments, M4A `©lyr`) and `.lrc` files next to your songs
- Word-level timestamps, `[offset]` tags, and a per-song timing fix in 0.1 s steps
- Tap a line to jump there

**Sound**
- Hi-res output (32-bit float) and bit-perfect USB output on Android 14+
- 10-band equalizer, bass boost and virtualizer
- ReplayGain (track and album), gapless playback
- Sleep timer that can fade out or stop at the end of the song

**Library**
- Songs, albums, artists and genres, with an A–Z fast scroller
- Songs with several artists ("A feat. B") show under each of them
- Choose which folders to scan; skip short clips
- Playlists, favourites, and auto playlists: Most played, On repeat, Recently added, Forgotten gems, genre mixes and more
- Listening stats: time listened, top songs and artists, streaks

**Design**
- The whole app takes its accent colour from the cover that is playing
- One player surface: swipe the mini player up into the full player and back down
- Swipe the cover to change song; swipe rows to play next or remove
- Light, dark and pure-black themes

## Download

Get `app-online-release.apk` from the latest `v*-online` release on [Releases](https://github.com/Arif1221/kairos-online/releases). Kairos Online is not on the Play Store and never will be (it streams through an unofficial YouTube API). It installs next to offline Kairos (`com.skarif.kairos.online`).

> Builds up to 0.7.5.2-online were signed with a retired key. If one is installed, uninstall it once before installing 0.7.6.1-online or later.

### Requirements
Android 9 (API 28) or newer. Bit-perfect USB output needs Android 14.

## Privacy

Kairos Online collects nothing and has no account. To stream, it talks to YouTube (search, stream URLs, audio) and to [LRCLIB](https://lrclib.net) (lyrics for online songs); those services see your IP address and what you search or play, as with any client. Your library, playlists, history and the stream cache stay on your phone. Read the full [privacy policy](PRIVACY.md).

## Online

### How the online build is set up
This repo keeps two Gradle flavors so the offline repo's code merges in cleanly:
- `online` (`assembleOnlineRelease`): what this repo builds and releases.
- `play`: the offline app, kept only so shared code compiles both ways. It isn't built or released here.

### Online features

- **Discover** tab (replaces Stats in the dock; Stats is in the menu): "More from" shelves for your most played artists, trending, new releases and mood chips (Bollywood, Lo-fi, Punjabi...).
- **Search** shows YouTube results under your own songs, with a **Songs / Videos** switch. Videos play as **audio only**; video streams are never downloaded. Shorts are skipped.
- **Settings > Streaming**: Data saver (~50 kbps), Normal (~128 kbps) or High (~160 kbps Opus), plus "Data saver on mobile data" (default on). Applies from the next song.
- **Works offline after streaming**: played songs are kept in a 1 GB stream cache (oldest go first) and replay with no connection. Discover shows an **Available offline** shelf when you're offline. Settings > Streaming shows the space used and can clear it.
- **Synced lyrics** for online songs from [LRCLIB](https://lrclib.net) (free, no key).
- **Reliability**: YouTube requests are retried once; a song that fails to load is skipped (up to 3 in a row) instead of stopping playback. Search and Discover show an offline state and reload when the connection returns.
- **Welcome screen** explains streaming and has **Skip, just stream**: no music permission needed, and the app opens on Discover.
- Discover also has **Recently streamed**, **Music videos** and a long-press menu (play next, favourite, add to playlist).
- Online songs can be favourited, added to playlists, queued, and count in Stats. No ReplayGain for them (no file to read).

### How it is wired

- `main/.../online/OnlineMusic.kt` is the only thing shared code knows about. `Flavor.online` is `null` in `src/play` and `YouTubeMusic` in `src/online`.
- Online songs are normal `songs` rows with a **negative id** and `volume = "online"`, so favourites, playlists, stats and the queue need no special cases. They are saved only once played, favourited or added to a playlist, and never appear in the Library.
- Online state that isn't playback (Discover, network, cache) lives in `ui/OnlineFeatures.kt`, not `MainViewModel`, so merges from main stay small.
- Playback goes through `RoutingDataSource`: local Uris go straight to the file, and `kairos-yt:` Uris go through `CacheDataSource` and then `ResolvingDataSource` (the stream URL is only fetched for bytes not in the cache). Each song's quality is pinned the first time it's opened, because seeking reopens the stream at a byte offset and a quality change mid-song would corrupt it.
- Quality is read each time a stream opens (`dataSourceFactory(context, quality)`), so the setting and Wi-Fi/mobile changes apply from the next song. Resolved URLs are cached per song and quality for 3 hours.
- Stream URLs expire, so the player gets `kairos-yt:<videoId>` and a `ResolvingDataSource` swaps in a fresh URL when the song is opened.
- YouTube access goes through [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (unofficial). When streaming breaks, bump `newpipeExtractor` in `gradle/libs.versions.toml` first.

> NewPipeExtractor is GPL-3.0, so the online APK must be distributed under the GPL-3.0.

### Keeping up with the offline app
```
git remote add offline https://github.com/Arif1221/kairos.git   # once
git fetch offline
git merge offline/main
```
Conflicts to expect: `versionCode`/`versionName` (keep online's numbering, ahead of offline), `.github/workflows/build.yml` (keep this repo's), and `MainViewModel.mixes` (the favourites mix must read `byId`, which includes saved online songs).

## Build from source

You need Android Studio Ladybug (2024.2) or newer, or JDK 17 and the Android SDK.

```bash
git clone https://github.com/Arif1221/kairos-online.git
cd kairos-online
./gradlew assembleDebug          # debug APK in app/build/outputs/apk/debug/
./gradlew testReleaseUnitTest    # unit tests (lyrics parser)
```

Release builds are signed in CI with a key stored in GitHub secrets. Local release builds without that key are signed with your debug key, so they install on your own phone.

## How it is built

| Part | What it uses |
|---|---|
| UI | Jetpack Compose, Material 3, one `MainViewModel` exposing `StateFlow`s |
| Playback | Media3 ExoPlayer in a `MediaSessionService` (notification, lock screen, headset buttons) |
| Library | MediaStore scan into Room; settings in DataStore |
| Lyrics | A tag reader and LRC parser in plain Java, with no Android imports, so they run in unit tests |
| Colour | Palette API: the accent is the colour that covers the most of the cover |
| Audio | `DynamicsProcessing` EQ, float output, `AudioManager` mixer attributes for bit-perfect USB |

```
app/src/main/java/com/skarif/kairos/
├── ui/          screens, player layer, theme, motion tokens, components
├── playback/    PlaybackService, PlayerConnection, AudioEffects, BitPerfect
├── data/        Room database, MediaScanner, SettingsStore, Lyrics and Artwork repositories
└── lyrics/      TagReader and LrcParser (pure Java)
```

## Contributing

Bug reports, ideas and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first. For security issues, see [SECURITY.md](SECURITY.md).

## License

Kairos is free software under the [GNU General Public License v3.0](LICENSE). You can use, study, share and change it; versions you distribute must stay under the same licence.

The Urbanist font is under the [SIL Open Font License](app/src/main/assets/licenses/urbanist-OFL.txt).
