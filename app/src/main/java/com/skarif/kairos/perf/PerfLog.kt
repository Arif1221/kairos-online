package com.skarif.kairos.perf

import android.view.View
import android.view.Window
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

/**
 * A small on-device performance log: every dropped frame (a frame that took longer than the
 * screen allows, as measured by Android's JankStats), with what the app was doing at that
 * moment ("player: opening", "queue: loading", "screen: library"...). Shown and shared from
 * Settings, so a lag report comes with numbers instead of guesses.
 *
 * In memory only, for this run of the app; nothing leaves the phone unless you share it.
 */
object PerfLog {
    data class Jank(val atMs: Long, val frameMs: Double, val states: String)

    private const val MAX = 300
    private val janks = ArrayDeque<Jank>()
    private var frames = 0L
    private var jankFrames = 0L
    private var jankStats: JankStats? = null
    private var holder: PerformanceMetricsState.Holder? = null
    private val startedAt = System.currentTimeMillis()

    fun install(window: Window) {
        jankStats = JankStats.createAndTrack(window) { fd: FrameData -> record(fd) }
        holder = PerformanceMetricsState.getHolderForHierarchy(window.decorView)
    }

    fun setTracking(on: Boolean) { jankStats?.isTrackingEnabled = on }

    /** Marks what the app is doing; shows next to any frame dropped while it is set. */
    fun state(key: String, value: String?) {
        val s = holder?.state ?: return
        if (value == null) s.removeState(key) else s.putState(key, value)
    }

    @Synchronized
    private fun record(fd: FrameData) {
        frames++
        if (!fd.isJank) return
        jankFrames++
        val ms = fd.frameDurationUiNanos / 1_000_000.0
        janks.addLast(Jank(System.currentTimeMillis(), ms, fd.states.joinToString(", ") { "${it.key}: ${it.value}" }))
        while (janks.size > MAX) janks.removeFirst()
    }

    @Synchronized
    fun summary(): String {
        val pct = if (frames > 0) jankFrames * 100.0 / frames else 0.0
        return "${jankFrames} of $frames frames dropped (${"%.1f".format(pct)}%)"
    }

    @Synchronized
    fun report(appVersion: String, device: String): String = buildString {
        append("Kairos $appVersion performance log\n")
        append("Device: $device\n")
        append(summary()).append('\n')
        append("Since app start: ${(System.currentTimeMillis() - startedAt) / 1000} s\n\n")
        append("Slowest dropped frames:\n")
        janks.sortedByDescending { it.frameMs }.take(25).forEach {
            append("  ${"%.0f".format(it.frameMs)} ms at +${(it.atMs - startedAt) / 1000.0}s  [${it.states.ifEmpty { "idle" }}]\n")
        }
        append("\nGrouped by what was happening:\n")
        janks.groupBy { it.states.ifEmpty { "idle" } }.entries.sortedByDescending { it.value.size }.forEach { (k, v) ->
            append("  ${v.size} frames, worst ${"%.0f".format(v.maxOf { it.frameMs })} ms: $k\n")
        }
        append("\nLast 40 in order:\n")
        janks.takeLast(40).forEach {
            append("  +${(it.atMs - startedAt) / 1000.0}s ${"%.0f".format(it.frameMs)} ms [${it.states.ifEmpty { "idle" }}]\n")
        }
    }

    @Synchronized
    fun clear() { janks.clear(); frames = 0; jankFrames = 0 }
}
