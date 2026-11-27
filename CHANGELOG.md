# Changelog

## Unreleased
- Video: a Song | Video switch at the top of the player for online songs, like YouTube Music. Video fits the cover whole (double-tap to fill it); it starts at the same second and goes back to the song's sound when you close the player, leave the app or swap song
- Fix: a stream address that stopped working (the phone changed from Wi-Fi to mobile data, say) made the song skip; it now gets one retry with a fresh address first
- Fix: swapping a song's stream no longer counts as a second play in Stats
- Fix: a lookup that timed out or was cancelled kept retrying in the background

## Online 0.9.1.8
- Fix: a crash during the back gesture on Android 16+ ("This input is not added to any dispatcher")

## Online 0.9.1.7
- Import from Spotify: paste a public playlist or album link (no login); songs are matched on this phone first, then on YouTube Music
- Library's Songs and Artists include songs downloaded in the app, alongside the files on this phone
- Download buttons in the queue too
- New playlist: the song search finds YouTube Music songs too, under your own
- Fix: saving the queue as a playlist left out online songs

## Online 0.9.1.6
- Home-screen widget: the song's cover blurred behind it, with play, previous and next
- See all on every Home shelf; Refresh moved to the top of Home
- Download buttons on Quick picks and on song cards
- Sharper covers on the player (YouTube thumbnails at their largest, without black bars)
- Artist pages load faster and never hang: they show what they found, or a Try again
- Smaller side menu, with a Light / Dark / Auto switch
- Save the queue as a playlist from a button on the queue screen
- A light tap feel on buttons, and a short message for play next, favourites and new playlists
- Living background: more of the cover's colour in dark and pure-black themes
- Songs saved to the phone now show up in Library a few seconds later

## Online 0.9.1.5
- Like artists, albums and playlists; find them under Library > Artists and Albums
- Liked artists lead Home's "Your artists"
- Fix: Library's Shuffle button was drawn over the Favourites tile

## Online 0.9.1.4
- One Home for streaming and your phone: Quick picks, Jump back in, downloads, your mixes and online shelves together
- Same dock as offline Kairos: Home, Library, Now playing, Stats, Search
- Library: Favourites, Playlists, Downloads and Recently played tiles
- Search: one box for YouTube Music and this phone, with filter chips
- Artist names in the player and song menu open the artist's online page
- In-app updates: a popup shows what's new and installs the update in place
- Tips that explain each screen, and Show tips again in Settings
- Settings > Credits lists every project Kairos Online is built with

## Online 0.9.1.3
- Save to phone: online songs, albums and playlists as M4A files in Music/Kairos, tagged with title, artist and cover
- Download buttons on every online song row and in the player, with progress
- Settings > Downloads & storage: choose what the Download button does, and how much room recently streamed songs may take (256 MB to 4 GB)

## Online 0.9.1.2
- Download online songs (hold a song, then Download) and whole albums, playlists and artists (Download all); downloads play offline and are never evicted
- Downloads page (menu and Explore), with storage and Remove all in Settings > Streaming
- "Made by" card in Settings and on the welcome screen
- Welcome screens updated for streaming

## Online 0.9.1.1
- Everything from offline Kairos 0.9.1: living album-art background, new menus, lyrics download and share cards, vinyl player, queue dragging, smarter shuffle and radio, new welcome screens and icon
- New Explore: Quick picks from mixes of what you play, Jump back in, your artists, playlists, new releases, charts and mood pages; opens instantly from the last visit
- New online search: suggestions, Songs / Videos / Artists / Albums & playlists, more results as you scroll, better ranked songs with tidy titles
- Album, playlist and artist pages for online results; save any of them as a playlist
- Online radio and autoplay from YouTube mixes
- Settings > Streaming > Online check shows what works on your network
- Sharing an online song shares its link

## Online 0.7.6.2
- Kairos Online moved to its own repository, Arif1221/kairos-online
- Update check and all links now point to the new repository

Entries below are the offline app's history, merged in from Arif1221/kairos.

## 0.7.6
- New app icon, with a themed icon on Android 13+
- Releases signed with a private key; a Play Store bundle (.aab) with every release
- Privacy policy, contributing guide and security policy

## 0.7.5
- Bigger cards on Home and a richer Continue card

## 0.7.4
- The fade behind the mini player follows it and fades as the player opens

## 0.7.3
- Fixed a crash when leaving the queue
- One shared background behind all pages, for smoother transitions
- If the app crashes, the next launch offers a report you can copy

## 0.7.2
- New Home: greeting, quick picks, Jump back in, your mixes, fresh albums
- New seek bar animation; lyrics fill moves smoothly; bigger current lyric line
- Favourites pinned in Playlists; favourite button in the accent colour

## 0.7.0
- One connected player: swipe the mini player up into the full player and back
- Lyrics and queue open inside the player, with the cover moving between them
- Sleep timer, sort and equalizer are bottom sheets

## 0.6.x
- Welcome screen, queue options and swipes, live stats, genres, multi-artist songs, auto playlists, line-by-line karaoke lyrics

## Video startup prefetch
- Added a separate 128 MiB LRU video cache so video startup ranges never evict streamed audio.
- When Video is enabled, Kairos resolves the video URL and prefetches the first 768 KiB while audio continues, then switches to the prepared stream. If prefetch fails, playback falls back to normal streaming.
- The next queued online song gets metadata/stream URL preparation only; no next-track video bytes are downloaded.
- Stream cache usage now includes the separate video cache, and Clear Cache clears both streamed audio and video cache entries (downloaded files remain untouched).

### Video availability fallback
- Songs with no suitable matching video stay in audio-only playback without interrupting the queue.
- Temporary video-search failures are represented separately from confirmed no-match results, so they are not cached as negative matches.
- The Video preference is retained across tracks: unavailable tracks play audio, and video can resume automatically on the next track with a valid match.


### Ambient video mode
- Added a soft, video-colour ambient glow behind the playing video in the Now Playing cover.
- Samples a 32×32 thumbnail about every 1.2 seconds and uses Palette to find dominant colours; animated gradients transition smoothly between scenes.
- Keeps processing off the video frame path and pauses sampling when video playback is paused or inactive.

### Download choices and offline video
- The Download button now offers Audio + video, Audio only, and Save audio to phone (M4A).
- Audio + video stores the song audio plus the matched video's picture and matching audio in persistent app storage, allowing the video player to use those cached streams offline.
- Added a per-dialog “Remember my choice” checkbox and Settings > Downloads & storage controls for the remembered default.
- Video downloads live outside the evictable stream cache; Clear Cache does not remove explicit downloads. Removing a download removes its video companion too.
- If a song has no suitable video, Audio + video still completes the audio download and reports that the video was unavailable.

## v18 – video framing, queue identity, and live video blur
- Replaced the sampled-palette radial Ambient Mode with a low-resolution live video-frame blur behind the video surface.
- Kept Fill/Fit sizing explicit and clipped oversized video content to the cover; raised the framing control above the video layer.
- Queue rows now resolve streamed-only songs from the ViewModel's session cache before falling back to an unavailable-track label.
