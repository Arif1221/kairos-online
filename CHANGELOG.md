# Changelog

## Unreleased

## Online 0.9.3.1
- Fix: build error on newer Compose (`BlurEffect.radiusX` is private); the glass blur tracks its own radius
- Look: calmer colours. Seek bar scenes (aurora bands, the planet and its ring, wave layers) now stay close to the cover's colour instead of fanning out into a rainbow. The album colour favours the most vivid family over a larger dull one, and the second background glow only appears when that hue really shows on the cover
- Smoother: opening and minimising the player no longer measures the mini player's row again on every frame, and its progress line stops redrawing once the player is open
- Smoother: the side menu and the song menus (including the player's three dots) rebuild only when something they show changes, not on every play/pause, queue change or download tick, and covers in them show on the first frame
- Look: the background glow is back to two colours (top and bottom corners), a calmer page and a little less to draw
- Fix: a quick double tap no longer opens a page twice or goes back two pages, runs a menu action twice, or closes a sheet twice
- Smoother: tapping the dock's play button or the mini player again while the player is already opening no longer restarts the opening spring

## Online 0.9.3.0
- Look: frosted glass. The dock, the mini player and the small title bar now blur the page under them, and the header panel is see-through. Their tint follows the album: lavender glass over a purple cover, more opaque where text needs it (real blur needs Android 12+; older versions get a plain, more opaque bar)
- Look: in light mode a dark cover now gives a bright pastel background instead of muddy grey, with the same readable text
- Look: the mini player floats a little higher, with a soft light on top and a fine edge
- Look: the side menu is built like the pages (living background, rounded header panel, same cards)
- Player: the ⋯ button at the top is a three-dot icon now (it was the menu icon); the queue's options button too
- Smoother: opening the player no longer hitches, the hidden title bar no longer costs a layer per frame, and the lyrics preview on the player page only redraws when the line changes
- Look: the seek bar is now an animated scene, tinted by the album: Minimal Wave+ (a fluid wave that pulses with the beat), Sakura Flow (petals on waves), Starry Horizon (parallax stars and shooting stars), Aurora Drift and Cosmic Orbit (a ringed planet, dust and a comet). The played part is a glowing wave with a knob riding its crest; drag to scrub as before. The old bars are still there as Classic Bars
- Look: the big card at the top of Home is alive too. It follows the time of day (petals in the morning, waves in the afternoon, a ringed planet in the evening, stars at night), or matches your seek bar, and takes the album's colour. It pauses while you scroll or while the player covers it
- Look: livelier, truer background colours. The glows now come from the cover's three most vivid, different hues (the old version averaged two halves, which turned colourful covers brown), and with no cover the page is tinted with your accent colour instead of plain grey
- Settings: a Seekbar card with a live preview, a style picker, an Animation setting (Still, Calm, Normal, Lively) and a choice for the Home card. The page is grouped into Appearance, Music, Library and data, and About. Backups keep the new settings

## Online 0.9.2.2
- Fix: lyrics subtitles over a video froze on one line; they now follow the song
- Video: when no video is found for a song, "Pick a video" on the no-video dialog searches YouTube right in the player and remembers your choice for that song
- Video: the soft blurred backdrop behind a fitted video now grabs its frames the supported way (the old way may have returned nothing); it is skipped in Fill mode, where it can't be seen
- Smoother: the living background redraws about 30 times a second instead of at the screen's rate, its colour boost is computed once per song, and it is cached as one picture, to cut GPU work while lists and the player page scroll
- Smoother: the living background holds still while you scroll, and carries on a moment after you stop

## Online 0.9.2.1
- Fix: the fit/fill and lyrics-subtitle buttons on a video did nothing when tapped (the cover's swipe layer sat on top of them); they work now, and subtitles no longer slide under the download and heart buttons
- Video: when no video is found for a song, a "Find video" chip on the player offers to search again or search by hand
- Player menu (the three dots): a Quality entry for audio and video quality, same settings as Settings > Streaming
- Fix: Backup and restore left out online songs' details, so on a new phone or after reinstalling their favourites, playlists and history did not come back; they do now. Backups also keep the background glow, video quality, subtitles and mini-player lyrics settings
- Look: a slightly heavier default veil over the background (your own Background glow setting is unchanged), and soft colour glows from the cover drift behind the player
- Video: more songs find their video (a looser second search pass; covers, live takes and remixes are still ignored). Video has a dot on the switch when one is ready, and "Find music video" is in the player and mini-player menus
- Video: your Song / Video choice is kept when you leave the app and come back, and a video that fails gets one quiet retry with a fresh address before falling back to the song's sound
- Video: minimising the player or leaving the app no longer swaps the video back to the song's sound after a few seconds (that paused and rebuffered the song); the picture just stops drawing and comes back when you return
- Video: "Retry video search" on the no-video dialog searches again (a "no video" answer was remembered for a day), and Retry after a network error now really retries
- Settings > Background glow: a slider for how much of the cover's colour shows through the background
- Video quality: Auto, 360p, 480p, 720p or 1080p in Settings > Streaming (Auto keeps the old behaviour; data saver on mobile data still caps at 360p). Downloaded videos are unaffected
- Lyrics as subtitles over videos with synced lyrics, with an on/off button on the video and in Settings
- Mini player: the current synced lyric line replaces the artist name while the song plays (can be turned off in Settings)
- Mini player: a heart button for favourites, and a taller player (76 dp) with a bigger cover (60 dp)
- Look: a thinner veil over the living background so the cover's colour blooms through (edges stay darker for readability)

## Online 0.9.2.0
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
