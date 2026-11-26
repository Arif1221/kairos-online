package com.skarif.kairos.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SleepState(
    val active: Boolean = false,
    /** Wall-clock time music stops, or 0 for "end of song". */
    val endsAt: Long = 0,
    val endOfSong: Boolean = false,
    val fade: Boolean = true,
)

/** Lives in the app scope, so it keeps running when the screen is closed. */
class SleepTimer(private val player: PlayerConnection, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SleepState())
    val state: StateFlow<SleepState> = _state.asStateFlow()
    private var job: Job? = null
    private var endListener: (() -> Unit)? = null

    fun start(minutes: Int, fade: Boolean) {
        cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.value = SleepState(active = true, endsAt = endsAt, fade = fade)
        job = scope.launch(Dispatchers.Main) {
            val fadeMs = if (fade) 60_000L else 0L
            delay((endsAt - System.currentTimeMillis() - fadeMs).coerceAtLeast(0))
            if (fade) fadeOut(fadeMs)
            finish()
        }
    }

    fun startEndOfSong(fade: Boolean) {
        cancel()
        _state.value = SleepState(active = true, endOfSong = true, fade = fade)
        val l = { finish() }
        endListener = l
        player.onAutoTransition(l)
    }

    fun cancel() {
        job?.cancel()
        job = null
        endListener?.let { player.removeAutoTransition(it) }
        endListener = null
        player.setVolume(1f)
        _state.value = SleepState()
    }

    /**
     * Fades like drifting off: loudness falls evenly to the ear, which means the volume level
     * follows a cube curve (a straight line sounds like nothing happens, then a sudden drop).
     * 120 small steps over a minute, so no step is audible.
     */
    private suspend fun fadeOut(ms: Long) {
        val steps = 120
        val start = player.volume
        for (i in 1..steps) {
            val left = 1f - i / steps.toFloat()
            player.setVolume(start * left * left * left)
            delay(ms / steps)
        }
    }

    private fun finish() {
        player.pause()
        endListener?.let { player.removeAutoTransition(it) }
        endListener = null
        job = null
        player.setVolume(1f)
        _state.value = SleepState()
    }
}
