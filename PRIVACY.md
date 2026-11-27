# Privacy policy (Kairos Online)

> This is the policy for **Kairos Online** (`com.skarif.kairos.online`). The offline Kairos app has its own policy in [Arif1221/kairos](https://github.com/Arif1221/kairos/blob/main/PRIVACY.md).

_Last updated: 19 November 2026_

Kairos Online is a music player made by SK Arif Hossain that plays music on your phone and streams from YouTube. This policy explains what the app does with your information.

## What Kairos collects

Nothing. Kairos Online does not collect, sell or share any personal data, and it contains no analytics, advertising or crash-reporting services. There is no account and no Kairos server.

## Services the app talks to

To stream, the app connects directly from your phone to:

- **YouTube** (youtube.com, googlevideo.com, ytimg.com, googleusercontent.com): search, stream addresses, audio and cover images. YouTube receives your IP address and what you search for and play, as with any YouTube client. Google's privacy policy applies to that traffic.
- **LRCLIB** (lrclib.net): the title, artist and length of a song, to find its lyrics.
- **Spotify** (open.spotify.com): only when you import a playlist, its public page, to read the song list. No account, nothing about you.
- **GitHub** (api.github.com): once per launch, to check for a newer Kairos Online release. Nothing about you is sent.

To build Explore (Quick picks and Your artists), the app searches YouTube for the titles and artists of the few songs you play most, including songs on your phone. Nothing else about your music library (files, folders, playlists, history) is sent anywhere.

## What stays on your phone

To work, Kairos keeps the following in its private storage on your device:

- the list of songs found on your phone (title, artist, album, duration, file location)
- your playlists, favourites and settings
- your listening history (which song, when, and for how long), used for statistics, auto playlists and Discover
- online songs you played, favourited or added to a playlist (title, artist, YouTube video id, cover address)
- a cache of streamed audio (up to 1 GB), so songs replay without a connection; Settings > Streaming can clear it
- the text of the last crash, if the app crashes, so you can choose to copy it into a bug report

This data never leaves your phone. Uninstalling Kairos, or clearing its storage in Android settings, deletes all of it. If Android's own backup is turned on, Android may include this data in your device backup, under Google's backup terms.

## Permissions

| Permission | Why |
|---|---|
| Music and audio (`READ_MEDIA_AUDIO`, or storage on Android 12 and older) | To find and play the songs on your phone |
| Notifications | To show playback controls in the notification |
| Foreground service (media playback) | To keep playing when the app is in the background |
| Change audio settings | For bit-perfect USB output on Android 14+ |
| Internet, network state | To stream, and to know when you are offline |

Folder access for `.lrc` lyrics files is only requested when you choose a folder, and is only used to read lyrics.

## Children

Kairos Online does not knowingly collect data from anyone, including children.

## Changes

If this policy changes, the new version will be published in this file with a new date.

## Contact

Questions: open an issue at https://github.com/Arif1221/kairos-online/issues.
