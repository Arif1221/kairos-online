package com.skarif.kairos.playback

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.skarif.kairos.MainActivity
import com.skarif.kairos.PlayerApp
import com.skarif.kairos.data.LyricsRepository
import com.skarif.kairos.data.OutputMode
import com.skarif.kairos.data.ReplayGainMode
import com.skarif.kairos.data.Settings
import com.skarif.kairos.data.db.PlayEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * Owns the ExoPlayer. Runs in the background, shows the media notification and lock-screen
 * controls, and keeps playing when the UI is closed.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effects = AudioEffects()
    private val container get() = (application as PlayerApp).container
    private var builtWithFloat = false
    private var bitPerfectLabel: String? = null

    // Listening time for the current song, for Stats.
    private var currentSongId: Long? = null
    private var playingSince = 0L
    private var listenedMs = 0L
    private var songStartedAt = 0L
    /** Row id of the current song's play event once saved, so later saves update the same row. */
    private var eventRow = EventRow()
    private val saveLock = kotlinx.coroutines.sync.Mutex()
    private var tickJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        val s = container.settings.settings.value
        builtWithFloat = s.outputMode != OutputMode.STANDARD
        val player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableAudioFloatOutput(builtWithFloat))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            // Online flavor: a data source that also understands streamed songs' Uris.
            .apply {
                com.skarif.kairos.online.Flavor.online?.let {
                    setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(it.dataSourceFactory(this@PlaybackService, ::streamQuality)))
                }
            }
            // Streams need the network to stay up while the screen is off.
            .setWakeMode(if (com.skarif.kairos.online.Flavor.online != null) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(playerListener)
        player.addAnalyticsListener(analytics)

        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        scope.launch {
            container.settings.settings.collect { st ->
                effects.attach(player.audioSessionId, st)
                if (st.outputMode != OutputMode.BIT_PERFECT) BitPerfect.clear(this@PlaybackService)
                applyReplayGain(st)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        recordListening()
        effects.release()
        BitPerfect.clear(this)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Items arrive from the UI with only a media id; turn that into the file's content Uri here. */
    private val callback = object : MediaSession.Callback {
        /**
         * The lock screen / notification / system media controls connect through the "media
         * notification controller". Without the timeline command, Media3 stops mirroring the
         * whole queue to Android's media session: with a 2,000-song queue that mirror was rebuilt
         * on the main thread on every queue change and froze the app for 100-540 ms (measured
         * with the in-app performance log). Play/pause, next/previous, seeking and the song's
         * details keep working there; only the queue list is not shared with the system.
         */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!session.isMediaNotificationController(controller)) return super.onConnect(session, controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(androidx.media3.common.Player.COMMAND_GET_TIMELINE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailablePlayerCommands(commands)
                .build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(::resolve).toMutableList())
    }

    private fun resolve(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        item.requestMetadata.mediaUri?.let { return item.buildUpon().setUri(it).build() } // online song
        val id = item.mediaId.toLongOrNull() ?: return item
        return item.buildUpon()
            .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
            .build()
    }

    /** Online songs skipped in a row because they failed to load; stops a dead network racing through the queue. */
    private var onlineSkips = 0

    private val playerListener = object : Player.Listener {
        // An online song that fails to load (removed video, YouTube change, no network) is skipped
        // rather than stopping playback, up to 3 in a row.
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val p = session?.player ?: return
            val online = (p.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L) < 0
            if (online && onlineSkips < 3 && p.hasNextMediaItem()) {
                onlineSkips++
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            recordListening()
            currentSongId = mediaItem?.mediaId?.toLongOrNull()
            songStartedAt = System.currentTimeMillis()
            listenedMs = 0
            playingSince = if (session?.player?.isPlaying == true) System.currentTimeMillis() else 0
            applyReplayGain(container.settings.settings.value)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            if (isPlaying) {
                onlineSkips = 0 // something is playing again
                playingSince = now
                tickJob?.cancel()
                tickJob = scope.launch { while (true) { kotlinx.coroutines.delay(15_000); saveListening() } }
            } else {
                tickJob?.cancel()
                if (playingSince > 0) {
                    listenedMs += now - playingSince
                    playingSince = 0
                }
                saveListening()
            }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            effects.attach(audioSessionId, container.settings.settings.value)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            bitPerfectLabel = null
            if (container.settings.settings.value.outputMode == OutputMode.BIT_PERFECT && format.sampleRate > 0) {
                bitPerfectLabel = BitPerfect.apply(this@PlaybackService, format.sampleRate)
            }
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val label = bitPerfectLabel?.let { "$it, bit-perfect" }
                ?: "${BitPerfect.bits(audioTrackConfig.encoding)}-bit / ${BitPerfect.khz(audioTrackConfig.sampleRate)} kHz"
            session?.setSessionExtras(Bundle().apply {
                putString(EXTRA_OUTPUT, label)
                putBoolean(EXTRA_USB, BitPerfect.usbConnected(this@PlaybackService))
                putBoolean(EXTRA_NEEDS_RESTART, needsRestart())
            })
        }
    }

    /** The streaming tier for the next online song: data saver on mobile data if the user wants it. */
    private fun streamQuality(): com.skarif.kairos.data.StreamQuality {
        val s = container.settings.settings.value
        val metered = getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        return if (s.dataSaverOnMobile && metered) com.skarif.kairos.data.StreamQuality.LOW else s.streamQuality
    }

    /** Float output is chosen when the player is built; changing the mode needs a new player. */
    private fun needsRestart(): Boolean =
        (container.settings.settings.value.outputMode != OutputMode.STANDARD) != builtWithFloat

    private fun applyReplayGain(s: Settings) {
        val player = session?.player ?: return
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        // Negative ids are online songs: no file tags to read.
        if (s.replayGain == ReplayGainMode.OFF || s.outputMode == OutputMode.BIT_PERFECT || id == null || id < 0) {
            player.volume = 1f
            return
        }
        scope.launch {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            val tags = withContext(Dispatchers.IO) { LyricsRepository.readTags(this@PlaybackService, uri) }
            val gain = when (s.replayGain) {
                ReplayGainMode.TRACK -> tags?.trackGainDb ?: tags?.albumGainDb
                else -> tags?.albumGainDb ?: tags?.trackGainDb
            }
            // Volume can only go down, so positive gains are capped at 0 dB.
            if (player.currentMediaItem?.mediaId?.toLongOrNull() == id) {
                player.volume = gain?.let { 10.0.pow(it.coerceAtMost(0f) / 20.0).toFloat() } ?: 1f
            }
        }
    }

    /**
     * Saves the current song's listening time. Called every 15 s while playing, on pause, and
     * when the song changes, so Stats update live and nothing is lost if the app is killed.
     */
    private fun saveListening() {
        val id = currentSongId ?: return
        val total = listenedMs + if (playingSince > 0) System.currentTimeMillis() - playingSince else 0
        if (total < 5_000) return
        val duration = session?.player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val row = eventRow
        val startedAt = songStartedAt
        container.scope.launch(Dispatchers.IO) { saveLock.lock(); try {
            val dao = container.db.playEvents()
            val e = PlayEventEntity(id = row.id, songId = id, startedAt = startedAt, listenedMs = total, completed = total >= duration * 0.9)
            if (row.id == 0L) row.id = dao.insert(e) else dao.update(e)
        } finally { saveLock.unlock() } }
    }

    /** Final save for the song that is ending; the next song starts a new row. */
    private fun recordListening() {
        saveListening()
        eventRow = EventRow()
        listenedMs = 0
        playingSince = 0
    }

    companion object {
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_USB = "usb"
        const val EXTRA_NEEDS_RESTART = "needs_restart"
        val isBitPerfectPossible: Boolean get() = Build.VERSION.SDK_INT >= 34
    }
}

/** Row id of a song's play event once saved, so later saves update the same row. */
private class EventRow {
    var id = 0L
}
