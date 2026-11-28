package com.skarif.kairos.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.db.isOnline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NowPlaying(
    val songId: Long? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    /** The queue in the order it will actually play (shuffle included). */
    val queue: List<QueueEntry> = emptyList(),
    /** Where the current song sits in [queue]. */
    val queuePosition: Int = 0,
    val shuffle: Boolean = false,
    /** Player.REPEAT_MODE_OFF / ONE / ALL. */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val output: String? = null,
    val usbConnected: Boolean = false,
    val outputNeedsRestart: Boolean = false,
    val speed: Float = 1f,
    /** "25 of 2071" for the screens: the whole intended queue, even while it is still being added. */
    val shownPosition: Int = 0,
    val shownSize: Int = 0,
    /** The current song is playing as a video (its Uri is a [VIDEO_SCHEME] one). */
    val videoActive: Boolean = false,
)

/** Uri scheme of an online video; the online flavor's data source plays it with its picture. */
const val VIDEO_SCHEME = "kairos-ytv"

/** A streamed song or video that has been buffering this long gets a "slow connection" notice. */
private const val SLOW_AFTER_MS = 5_000L
/** At most one such notice per this long, so a flaky network doesn't flood the screen. */
private const val SLOW_COOLDOWN_MS = 30_000L
/** A video that still hasn't recovered after this long is given up on (the app falls back to the sound). */
private const val VIDEO_GIVE_UP_MS = 15_000L

/** [index] is the player's media item index; [songId] the song. */
data class QueueEntry(
    val index: Int,
    val songId: Long,
    /** Media3 metadata survives even if the corresponding local DB row is temporarily absent. */
    val title: String = "",
    val artist: String = "",
)

/** The UI's handle on the playback service, through a MediaController. */
class PlayerConnection(private val context: Context, private val appScope: CoroutineScope) {
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    /**
     * Position is its own flow: it changes 5 times a second while playing, and only the screens
     * that show it (seek bar, lyrics) should redraw that often.
     */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)
    /** Size of the video's picture, for fitting it into the cover. */
    val videoSize: StateFlow<VideoSize> = _videoSize.asStateFlow()

    private val _firstFrame = MutableStateFlow(false)
    /** True once the video has drawn a frame since the last switch; until then the cover stays up. */
    val firstFrame: StateFlow<Boolean> = _firstFrame.asStateFlow()

    /** Called when the video of the playing song fails to load or play. */
    @Volatile var onVideoError: (String) -> Unit = {}

    /** Called when audio playback fails (with song title, human-readable reason, and whether it skipped). */
    @Volatile var onPlaybackError: (title: String?, reason: String, skipped: Boolean) -> Unit = { _, _, _ -> }

    /**
     * Called once when a streamed song or video has been stuck buffering for a while; [video] says
     * which. Audio and video share this path so they warn the same way.
     */
    @Volatile var onSlowNetwork: (video: Boolean) -> Unit = {}

    /** Called when playback runs again after [onSlowNetwork] was shown. */
    @Volatile var onNetworkRecovered: () -> Unit = {}

    private var stallJob: Job? = null
    private var lastSlowAt = -SLOW_COOLDOWN_MS
    private var slowShown = false

    private var lastAudioErrorId: String? = null

    private var controller: MediaController? = null
    private var pendingVideoView: TextureView? = null
    private var ticker: Job? = null
    private var extras: Bundle = Bundle.EMPTY
    private val transitionListeners = mutableListOf<() -> Unit>()

    fun connect() {
        if (controller != null) return
        appScope.launch(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = MediaController.Builder(context, token)
                .setListener(object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        this@PlayerConnection.extras = extras
                        publish()
                    }
                })
                .buildAsync()
                .await()
            controller = c
            pendingVideoView?.let { c.setVideoTextureView(it) }
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
                override fun onVideoSizeChanged(videoSize: VideoSize) { _videoSize.value = videoSize }
                override fun onRenderedFirstFrame() { _firstFrame.value = true; android.util.Log.i("KairosVideo", "UI: first frame") }
                override fun onPlayerError(error: PlaybackException) {
                    val cur = c.current()
                    val reason = humanError(error)
                    if (isVideoItem(cur)) {
                        android.util.Log.w("KairosVideo", "video failed: ${error.errorCodeName}: $reason", error)
                        onVideoError(reason)
                    } else {
                        val id = cur?.mediaId
                        val online = (id?.toLongOrNull() ?: 0L) < 0
                        val permanent = reason in setOf(
                            "Age-restricted on YouTube", "Not available in your region",
                            "This video is private", "Paid content cannot be streamed",
                            "Unavailable on YouTube", "No playable audio stream",
                        )
                        // Skip notifying on the first transient online retry unless it's permanent or local.
                        if (!online || permanent || lastAudioErrorId == id || !c.hasNextMediaItem()) {
                            val title = cur?.mediaMetadata?.title?.toString()
                            onPlaybackError(title, reason, online && c.hasNextMediaItem())
                        }
                        lastAudioErrorId = id
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) lastAudioErrorId = null
                }
                override fun onPlaybackStateChanged(playbackState: Int) = watchBuffering(c)
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = watchBuffering(c)
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // A new item restarts the clock: its own stall is judged on its own.
                    stallJob?.cancel(); stallJob = null; slowShown = false
                    watchBuffering(c)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) transitionListeners.toList().forEach { it() }
                }
            })
            publish()
        }
    }

    /**
     * The playing item. While a stream swap (sound <-> video) is being set up, [MediaController.getCurrentMediaItem]
     * can be null although the queue and the index are intact (items=6 index=1 in the log). Reading
     * the item at the index instead keeps the song from "disappearing" for that time, which made the
     * ViewModel drop the video request and, after its grace period, close the player.
     */
    private fun MediaController.current(): MediaItem? =
        currentMediaItem ?: if (currentMediaItemIndex in 0 until mediaItemCount) getMediaItemAt(currentMediaItemIndex) else null

    /**
     * Watches for a streamed song or video that wants to play but keeps buffering. After
     * [SLOW_AFTER_MS] it reports [onSlowNetwork]; a video that is still stuck at [VIDEO_GIVE_UP_MS]
     * is reported as a video error, so it takes the same retry-then-fall-back path as any other.
     * Local files are never watched: they don't wait on a network.
     */
    private fun watchBuffering(c: MediaController) {
        val cur = c.current()
        val video = isVideoItem(cur)
        val streamed = video || (cur?.mediaId?.toLongOrNull() ?: 0L) < 0
        val stalled = streamed && c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
        if (!stalled) {
            stallJob?.cancel(); stallJob = null
            if (slowShown && c.playbackState == Player.STATE_READY) { slowShown = false; onNetworkRecovered() }
            return
        }
        if (stallJob?.isActive == true) return
        stallJob = appScope.launch(Dispatchers.Main) {
            delay(SLOW_AFTER_MS)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSlowAt >= SLOW_COOLDOWN_MS) { lastSlowAt = now; slowShown = true; onSlowNetwork(video) }
            if (video) {
                delay(VIDEO_GIVE_UP_MS - SLOW_AFTER_MS)
                onVideoError("Slow connection")
            }
        }
    }

    private fun isVideoItem(item: MediaItem?) = item?.requestMetadata?.mediaUri?.scheme == VIDEO_SCHEME

    private fun humanError(error: PlaybackException): String {
        val known = listOf(
            "Age-restricted on YouTube",
            "Not available in your region",
            "This video is private",
            "Paid content cannot be streamed",
            "Unavailable on YouTube",
            "Too many requests — try again shortly",
            "No playable video stream",
            "No playable audio stream",
            "No internet connection",
            "Connection timed out",
        )
        for (t in generateSequence<Throwable>(error) { it.cause }) {
            val msg = t.message.orEmpty()
            known.firstOrNull { it in msg }?.let { return it }
            if (t is java.net.UnknownHostException || t is java.net.ConnectException) return "No internet connection"
            if (t is java.net.SocketTimeoutException) return "Connection timed out"
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "No internet connection"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connection timed out"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Stream unavailable"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "File not found"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "Format not supported on this device"
            else -> "Playback error"
        }
    }

    /** Draws the video into [view]. Call [detachVideo] when the view goes away. */
    fun attachVideo(view: TextureView) {
        android.util.Log.i("KairosVideo", "UI: attach texture (controller=${controller != null}, available=${view.isAvailable}, attached=${view.isAttachedToWindow})")
        pendingVideoView = view
        controller?.setVideoTextureView(view)
    }

    fun detachVideo(view: TextureView) {
        if (pendingVideoView === view) pendingVideoView = null
        controller?.clearVideoTextureView(view)
    }

    /**
     * Plays the current song from [uri] instead (its sound only, or with its picture), from the
     * same second. Same media id, so favourites, stats and the queue don't notice.
     *
     * The item is built fresh, without the Uri the service resolved for the old one: with it, the
     * service would keep playing the old stream.
     */
    fun swapStream(uri: Uri) {
        val c = controller ?: return
        val item = c.current() ?: return
        // Already playing from this Uri (a repeated request): swapping again would only restart the stream.
        if (item.requestMetadata.mediaUri == uri) return
        val index = c.currentMediaItemIndex
        val position = c.currentPosition
        _firstFrame.value = false
        val swapped = MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setMediaMetadata(item.mediaMetadata)
            // replaceMediaItem() does not pass through MediaSession.Callback.onAddMediaItems().
            // Therefore the service cannot resolve requestMetadata.mediaUri into a playback URI
            // for this replacement. Set BOTH: localConfiguration.uri drives MediaSource creation,
            // while requestMetadata.mediaUri preserves Kairos' logical stream identity.
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
        c.replaceMediaItem(index, swapped)
        c.seekTo(index, position) // replacing the playing item restarts it
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }

    /**
     * Turns the picture off or on WITHOUT touching the stream: nothing is rebuffered and the sound
     * does not skip, unlike [swapStream]. With the video track disabled nothing decodes it, and the
     * player stops reading the picture-only stream. Used when the player is minimised or the app
     * goes to the background.
     */
    fun setVideoRendering(on: Boolean) {
        val c = controller ?: return
        val params = c.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == !on) return
        c.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on).build()
    }

    /** Called when a song ends and the next one starts on its own (used by the sleep timer). */
    fun onAutoTransition(listener: () -> Unit) { transitionListeners += listener }
    fun removeAutoTransition(listener: () -> Unit) { transitionListeners -= listener }

    private var fillJob: Job? = null
    // While a long queue is being added: where the tapped song sits in the full list, how long
    // the full list is, and how many songs have gone in before it so far.
    private var fillStart = 0
    private var fillTotal = 0
    private var fillInsertedBefore = 0
    private val _filling = MutableStateFlow(false)
    /** True while a long queue is still being added in the background (see [play]). */
    val filling: StateFlow<Boolean> = _filling.asStateFlow()

    /**
     * Plays [songs] from [startIndex]. A long list (a whole library) is not sent to the
     * playback service in one go: that one call bundles every song across processes on the main
     * thread and froze the player's opening animation for a moment. The tapped song starts at
     * once on its own; the rest is added in small batches once the player has opened.
     */
    fun play(songs: List<SongEntity>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(0, songs.lastIndex)
        c.shuffleModeEnabled = shuffle
        loadQueue(c, songs, start, 0L, playNow = true)
    }

    /**
     * True while the UI is running an animation the user is watching (the player opening or
     * closing). Big queue updates wait for it; set by the ViewModel.
     */
    @Volatile var busy: () -> Boolean = { false }

    /**
     * Loads a queue. A long one starts with just [start]'s song; the rest is added afterwards in
     * two calls, once nothing is animating. Every queue change makes Media3 rebuild the whole
     * queue for Android's media controls (lock screen, notification) on the main thread, so
     * 2,000 songs in 21 batches meant 21 full rebuilds right after the player opened.
     */
    private fun loadQueue(c: MediaController, songs: List<SongEntity>, start: Int, positionMs: Long, playNow: Boolean, firstWaitMs: Long = 400) {
        fillJob?.cancel()
        if (songs.size <= 40) {
            _filling.value = false
            c.setMediaItems(songs.map { it.toMediaItem() }, start, positionMs)
            c.prepare(); if (playNow) c.play()
            return
        }
        fillStart = start; fillTotal = songs.size; fillInsertedBefore = 0
        _filling.value = true
        c.setMediaItems(listOf(songs[start].toMediaItem()), 0, positionMs)
        c.prepare(); if (playNow) c.play()
        fillJob = appScope.launch(Dispatchers.Main) {
            try {
                suspend fun idle(ms: Long = 400) { delay(ms); while (busy()) delay(50); delay(200) }
                idle(firstWaitMs)
                // Pieces of 250, one per frame: one call with 2,000 songs blocks the main thread for 100+ ms.
                val after = withContext(Dispatchers.Default) { songs.subList(start + 1, songs.size).map { it.toMediaItem() } }
                for (part in after.chunked(250)) { while (busy()) delay(50); c.addMediaItems(part); delay(32) }
                idle()
                val before = withContext(Dispatchers.Default) { songs.subList(0, start).map { it.toMediaItem() } }
                // Inserted at the front in reverse piece order, so the final order is unchanged.
                for (part in before.chunked(250).asReversed()) { while (busy()) delay(50); c.addMediaItems(0, part); fillInsertedBefore += part.size; delay(32) }
            } finally {
                _filling.value = false
                publish()
            }
        }
    }

    /**
     * Puts a saved queue back, paused, if nothing is loaded yet (the app just started).
     * Waits for the connection to the playback service.
     */
    suspend fun restoreIfEmpty(songs: List<SongEntity>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) return
        var c = controller
        var waited = 0
        while (c == null && waited < 100) { kotlinx.coroutines.delay(50); waited++; c = controller }
        c ?: return
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (c.mediaItemCount > 0) return@withContext
            // Same gradual loading as play(), started 2.5 s later so it misses the opening animations.
            loadQueue(c, songs, index.coerceIn(0, songs.lastIndex), positionMs.coerceAtLeast(0), playNow = false, firstWaitMs = 2500)
        }
    }

    fun toggle() = controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } }
    fun pause() = controller?.pause()
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun previousTrack() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) = controller?.seekTo(ms.coerceAtLeast(0))
    fun playQueueItem(index: Int) = controller?.run { seekToDefaultPosition(index); play() }
    fun moveQueueItem(from: Int, to: Int) = controller?.moveMediaItem(from, to)
    fun removeQueueItem(index: Int) = controller?.removeMediaItem(index)

    /** Moves a queue entry to play right after the current song. */
    fun moveToNext(index: Int) = controller?.run {
        val cur = currentMediaItemIndex
        if (index == cur) return@run
        moveMediaItem(index, if (index > cur) cur + 1 else cur)
    }

    fun moveToEnd(index: Int) = controller?.run { if (index != currentMediaItemIndex) moveMediaItem(index, mediaItemCount - 1) }

    /** Removes everything after the current song. */
    fun clearUpcoming() = controller?.run { if (currentMediaItemIndex + 1 < mediaItemCount) removeMediaItems(currentMediaItemIndex + 1, mediaItemCount) }

    /** Removes the songs already played. */
    fun clearPlayed() = controller?.run { if (currentMediaItemIndex > 0) removeMediaItems(0, currentMediaItemIndex) }
    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }

    /**
     * Shake to shuffle: puts everything after the current song in a new random order. Done as
     * one remove and one add (not one move per song, which is a binder call each). With shuffle
     * mode on, "what's next" isn't the list order, so it's switched off: the list itself is now
     * random, and the queue shows exactly what will play. False if there's nothing to shuffle.
     */
    fun reshuffleUpcoming(): Boolean {
        val c = controller ?: return false
        if (filling.value) return false
        if (c.shuffleModeEnabled) c.shuffleModeEnabled = false
        val cur = c.currentMediaItemIndex
        val n = c.mediaItemCount
        if (n - cur - 1 < 2) return false
        val rest = (cur + 1 until n).map { c.getMediaItemAt(it) }.shuffled()
        c.removeMediaItems(cur + 1, n)
        c.addMediaItems(rest)
        return true
    }
    /** Swaps everything after the current song for [songs] (an already-ordered list), as one remove and one add. */
    fun replaceUpcoming(songs: List<SongEntity>): Boolean {
        val c = controller ?: return false
        if (filling.value || songs.size < 2) return false
        if (c.shuffleModeEnabled) c.shuffleModeEnabled = false
        val cur = c.currentMediaItemIndex
        c.removeMediaItems(cur + 1, c.mediaItemCount)
        c.addMediaItems(songs.map { it.toMediaItem() })
        return true
    }
    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeed(v: Float) = controller?.setPlaybackSpeed(v.coerceIn(0.25f, 3f))
    fun setVolume(v: Float) = controller?.run { volume = v.coerceIn(0f, 1f) }
    val volume: Float get() = controller?.volume ?: 1f

    /** Puts songs back at a queue position (undo of a removal). */
    fun insertAt(index: Int, songs: List<SongEntity>) = controller?.run {
        addMediaItems(index.coerceIn(0, mediaItemCount), songs.map { it.toMediaItem() })
    }

    /** Adds songs at the end of the queue (autoplay). */
    fun append(songs: List<SongEntity>) = controller?.run { if (songs.isNotEmpty()) addMediaItems(songs.map { it.toMediaItem() }) }

    /** Adds songs right after the current one. */
    fun playNext(songs: List<SongEntity>) = controller?.run {
        fillJob?.let { if (it.isActive) { it.cancel(); _filling.value = false } }
        addMediaItems((currentMediaItemIndex + 1).coerceAtMost(mediaItemCount), songs.map { it.toMediaItem() })
    }

    private fun publish() {
        val c = controller ?: return
        _position.value = c.currentPosition
        // Walk the timeline in play order, so the queue matches what you hear when shuffle is on.
        val tl = c.currentTimeline
        val order = ArrayList<Int>(tl.windowCount)
        if (!tl.isEmpty) {
            var i = tl.getFirstWindowIndex(c.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && order.size < tl.windowCount) {
                order += i
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        if (order.size != c.mediaItemCount) { order.clear(); order.addAll(0 until c.mediaItemCount) }
        val next = NowPlaying(
            songId = c.current()?.mediaId?.toLongOrNull(),
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            queueIndex = c.currentMediaItemIndex,
            queueSize = c.mediaItemCount,
            queue = order.map { i ->
                val item = c.getMediaItemAt(i)
                QueueEntry(
                    index = i,
                    songId = item.mediaId.toLongOrNull() ?: -1L,
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                )
            },
            queuePosition = order.indexOf(c.currentMediaItemIndex).coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            output = extras.getString(PlaybackService.EXTRA_OUTPUT),
            usbConnected = extras.getBoolean(PlaybackService.EXTRA_USB),
            outputNeedsRestart = extras.getBoolean(PlaybackService.EXTRA_NEEDS_RESTART),
            speed = c.playbackParameters.speed,
            shownPosition = if (_filling.value) fillStart - fillInsertedBefore + order.indexOf(c.currentMediaItemIndex).coerceAtLeast(0)
            else order.indexOf(c.currentMediaItemIndex).coerceAtLeast(0),
            shownSize = if (_filling.value) fillTotal else c.mediaItemCount,
            videoActive = isVideoItem(c.current()),
        )
        if (next.songId == null && _state.value.songId != null) {
            android.util.Log.w("KairosNow", "no current song: items=${c.mediaItemCount} index=${c.currentMediaItemIndex} state=${c.playbackState} err=${c.playerError?.errorCodeName}")
        }
        if (next != _state.value) _state.value = next
        // Position only changes on its own while playing, so only tick then.
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = appScope.launch(Dispatchers.Main) {
                while (isActive && controller?.isPlaying == true) {
                    _position.value = controller?.currentPosition ?: 0
                    delay(200)
                }
            }
        }
    }

    private fun SongEntity.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        // Online songs carry their stream Uri across to the service; local ones are resolved there from the id.
        .apply {
            val online = com.skarif.kairos.online.Flavor.online
            if (isOnline && online != null) {
                setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(online.mediaUri(this@toMediaItem)).build())
            }
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                // Legacy album-art Uri; still served by MediaStore and used for the notification.
                .setArtworkUri(artUrl?.let(Uri::parse) ?: Uri.parse("content://media/external/audio/albumart/$albumId"))
                .setIsPlayable(true)
                .build(),
        )
        .build()
}
