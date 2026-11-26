package com.skarif.kairos.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
)

/** [index] is the player's media item index; [songId] the song. */
data class QueueEntry(val index: Int, val songId: Long)

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

    private var controller: MediaController? = null
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
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) transitionListeners.toList().forEach { it() }
                }
            })
            publish()
        }
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
            songId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            queueIndex = c.currentMediaItemIndex,
            queueSize = c.mediaItemCount,
            queue = order.map { i -> QueueEntry(i, c.getMediaItemAt(i).mediaId.toLongOrNull() ?: -1L) },
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
        )
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
