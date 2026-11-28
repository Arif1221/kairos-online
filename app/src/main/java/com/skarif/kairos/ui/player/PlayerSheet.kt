package com.skarif.kairos.ui.player

import com.skarif.kairos.ui.components.LocalAppLayer
import com.skarif.kairos.ui.components.drawFrost
import com.skarif.kairos.ui.components.frostTint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.backdrop
import com.skarif.kairos.ui.motion.Motion
import com.skarif.kairos.ui.motion.Swipe
import com.skarif.kairos.ui.screens.LyricsPane
import com.skarif.kairos.ui.screens.NowPlayingPane
import com.skarif.kairos.ui.screens.QueuePane
import com.skarif.kairos.ui.screens.rememberCoverNow
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PlayerPage { MAIN, LYRICS, QUEUE }

/**
 * The player is one surface with a single [progress]: 0 is the mini player above the dock,
 * 1 is full screen. Fingers, buttons and the back gesture all move the same number, so the
 * mini player, the cover and the full player are always one connected thing.
 */
@Stable
class PlayerController(private val scope: CoroutineScope) {
    val progress = Animatable(0f)
    var page by mutableStateOf(PlayerPage.MAIN)
    /** Sideways offset of the cover while swiping between songs. */
    val skipX = Animatable(0f)
    /** A cover swipe changed song (-1 next, 1 previous) and is waiting for the new song to arrive. */
    internal var pendingSkip = 0
    /** Video defaults to filling/cropping the cover; double-tap switches to fit-the-whole-frame. */
    var videoFill by mutableStateOf(true)
    /** Vinyl mode: the cover becomes a spinning record (long-press the cover). */
    var vinyl by mutableStateOf(false)
    /** The record's angle in degrees. Read only while drawing. */
    var vinylAngle by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** True while a finger turns the record; the motor lets go meanwhile. */
    internal var vinylHeld = false
    /** Platter speed in degrees a second, kept across play/pause so it spins up and down smoothly. */
    internal var vinylSpeed = 0f
    /** Width of the hero cover, one swipe step. Set by the sheet. */
    internal var coverWidth = 1f
    /** Scroll of the full player, so the cover scrolls with the page. */
    val mainScroll = ScrollState(0)
    /** Pixels between collapsed and expanded, so a drag of N pixels moves the sheet N pixels. */
    internal var rangePx = 1f

    val isOpen: Boolean get() = progress.targetValue > 0.5f

    /** True while a finger is moving the player. */
    var dragging by mutableStateOf(false)
        private set

    /** Where the current drag started, to measure it against the shared swipe rule. */
    private var dragStart: Float? = null
    /** Screen density, set by the sheet, for the swipe rule. */
    internal var density = 1f

    // Only one animation drives the player at a time; a new one replaces the old.
    private var job: Job? = null
    private fun run(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch(block = block)
    }

    /** Asks the sheet to build the full player now (before it is visible). */
    var warmRequested by mutableStateOf(false)
    /** The full player has been built at least once. */
    var pagesBuilt by mutableStateOf(false)

    fun expand(to: PlayerPage = PlayerPage.MAIN) {
        // Already open or opening on this page (a double tap on the dock or mini player): leave the
        // running spring alone; restarting it would drop its velocity and stutter.
        if (isOpen && page == to && !dragging) return
        page = to
        run {
            // First open since the app started: build the player while it is still closed, let
            // two frames pass, then start the spring. Building it inside the animation's first
            // frame is what made it skip from closed to almost open.
            if (!pagesBuilt) {
                warmRequested = true
                kotlinx.coroutines.withTimeoutOrNull(800) { androidx.compose.runtime.snapshotFlow { pagesBuilt }.first { it } }
                androidx.compose.runtime.withFrameNanos { }
                androidx.compose.runtime.withFrameNanos { }
            }
            progress.animateTo(1f, Motion.sheet())
        }
    }

    fun collapse() = run {
        dragging = false
        progress.animateTo(0f, Motion.sheet())
        page = PlayerPage.MAIN
        mainScroll.scrollTo(0)
    }

    fun dragBy(dyPx: Float) {
        if (!dragging) dragStart = progress.value
        dragging = true
        job?.cancel()
        // Undispatched: the snap happens now, in order, never after a later settle().
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.snapTo((progress.value - dyPx / rangePx).coerceIn(0f, 1f))
        }
    }

    /** Predictive back: the sheet follows the back gesture down, up to a third of the way. */
    fun backProgress(fraction: Float) {
        dragging = true
        job?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(1f - 0.33f * fraction.coerceIn(0f, 1f)) }
    }

    /** The back gesture was cancelled: spring back open. */
    fun backCancel() {
        dragging = false
        run { progress.animateTo(1f, Motion.sheet()) }
    }

    /**
     * Ends a drag with the shared swipe rule ([Swipe]): from open, 72 dp down or a flick closes;
     * from closed, 72 dp up or a flick opens. Anything less springs back. Not after a drag
     * (a half-open player left behind), whichever side is closer.
     */
    fun settle(velocityY: Float) {
        val start = dragStart
        dragging = false
        dragStart = null
        val movedDownPx = start?.let { (it - progress.value) * rangePx } ?: 0f
        val dir = Swipe.decide(movedDownPx, velocityY, density) // 1 = down
        val target = when {
            start == null -> if (progress.value > 0.5f) 1f else 0f
            start > 0.5f -> if (dir == 1) 0f else 1f
            else -> if (dir == -1) 1f else 0f
        }
        run {
            progress.animateTo(target, Motion.sheet(), initialVelocity = -velocityY / rangePx)
            if (target == 0f) { page = PlayerPage.MAIN; mainScroll.scrollTo(0) }
        }
    }
}

val LocalPlayer = staticCompositionLocalOf<PlayerController> { error("No player") }

/** Places a child at a rectangle computed at layout time, so moving it never recomposes. */
private fun Modifier.placeAt(rect: () -> Rect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val w = r.width.roundToInt().coerceAtLeast(1)
    val h = r.height.roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

/**
 * The player layer. Sits above every page; [miniBottomPx] is how far above the screen's bottom
 * edge the mini player rests (higher when the dock is showing).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerSheet(
    vm: MainViewModel,
    ctrl: PlayerController,
    miniBottomPx: Float,
    onAlbum: (Long) -> Unit,
    onOpenCollection: (String) -> Unit,
    onSleep: () -> Unit,
    onEqualizer: () -> Unit,
    onSaveQueue: () -> Unit,
) {
    val c = P.colors
    val song by vm.currentSong.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val wantsVideo by vm.wantsVideo.collectAsStateWithLifecycle()
    val s = song
    LaunchedEffect(s == null) { if (s == null) ctrl.progress.snapTo(0f) }
    if (s == null) return

    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    val heroHeightPx = with(density) { (LocalConfiguration.current.screenHeightDp * 0.47f).coerceIn(300f, 440f).dp.toPx() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val songMap by vm.songMap.collectAsStateWithLifecycle()
    val prevSong = now.queue.getOrNull(now.queuePosition - 1)?.let { songMap[it.songId] }
    val nextSong = now.queue.getOrNull(now.queuePosition + 1)?.let { songMap[it.songId] }
    val curImg = rememberCoverNow(vm, s)
    val artImg = remember(art) { art?.asImageBitmap() }
    val prevImg = rememberCoverNow(vm, prevSong)
    val nextImg = rememberCoverNow(vm, nextSong)
    // A swipe changes song the moment you let go, while the cover is still sliding. When the new
    // song arrives, the neighbour that was sliding in *is* the current cover now: shift the offset
    // by one cover width so nothing jumps, and finish the slide from there with the same speed.
    // (Before, the song changed only after the slide had finished: 0.6-0.8 s later than the buttons.)
    LaunchedEffect(s.id) {
        val dir = ctrl.pendingSkip
        ctrl.pendingSkip = 0
        if (dir == 0) { ctrl.skipX.snapTo(0f); return@LaunchedEffect }
        val v = ctrl.skipX.velocity
        val width = ctrl.coverWidth.coerceAtLeast(1f)
        ctrl.skipX.snapTo(ctrl.skipX.value - dir * width)
        ctrl.skipX.animateTo(0f, Motion.sheet(), initialVelocity = v)
    }

    val open by remember { derivedStateOf { ctrl.progress.value > 0.001f } }
    // Track player open/close at the PlayerSheet level (not inside NowPlayingPane) so switching
    // between Main, Lyrics, and Queue never drops or restarts the active video stream.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, open) {
        vm.setPlayerOpen(open)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.pauseVideoForBackground()
                androidx.lifecycle.Lifecycle.Event.ON_START -> vm.resumeVideoIfPreferred()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Long-press on the mini player: the full player's menu, without opening the player.
    var miniMenu by remember { mutableStateOf(false) }
    if (miniMenu) com.skarif.kairos.ui.screens.PlayerMenu(
        vm, s, onAlbum, { a -> onOpenCollection("artist:$a") }, onSleep, onEqualizer,
    ) { miniMenu = false }
    // Safety net: if anything ever leaves the player resting half open, finish the move.
    LaunchedEffect(ctrl) {
        // Only wakes when motion stops, not on every frame of it.
        androidx.compose.runtime.snapshotFlow { !ctrl.progress.isRunning && !ctrl.dragging }
            .collect { still -> val v = ctrl.progress.value; if (still && v > 0.001f && v < 0.999f) ctrl.settle(0f) }
    }
    // Back closes the player interactively: a back swipe pulls the sheet down under your thumb
    // (predictive back), and letting go either finishes closing or springs it back open.
    // On a lyrics or queue page, back returns to the main player.
    // Stays enabled a moment after the player closes: the system keeps animating the back gesture
    // after it was committed, and calling a handler that was already disabled crashes
    // ("This input is not added to any dispatcher").
    val backEnabled = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(ctrl.isOpen) {
        if (ctrl.isOpen) backEnabled.value = true else { kotlinx.coroutines.delay(600); backEnabled.value = false }
    }
    androidx.activity.compose.PredictiveBackHandler(enabled = backEnabled.value) { events ->
        val onMain = ctrl.page == PlayerPage.MAIN
        try {
            events.collect { e -> if (onMain) ctrl.backProgress(e.progress) }
            if (onMain) ctrl.collapse() else ctrl.page = PlayerPage.MAIN
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (onMain) ctrl.backCancel()
            throw e
        }
    }

    // Publish "the player is animating" for work that should wait (see PlayerMotion).
    LaunchedEffect(ctrl) {
        androidx.compose.runtime.snapshotFlow { ctrl.progress.isRunning || ctrl.dragging }
            .collect {
                com.skarif.kairos.ui.motion.PlayerMotion.animating = it
                com.skarif.kairos.perf.PerfLog.state(
                    "player",
                    when { !it -> null; ctrl.dragging -> "dragging"; ctrl.progress.targetValue > 0.5f -> "opening"; else -> "closing" },
                )
            }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val px = { d: Float -> with(density) { d.dp.toPx() } }
        ctrl.coverWidth = w
        val miniH = px(76f)
        val inset = px(14f)
        val miniTop = h - miniBottomPx - miniH
        ctrl.rangePx = miniTop.coerceAtLeast(1f)
        ctrl.density = LocalDensity.current.density

        fun sheetRect(p: Float) = Rect(mix(inset, 0f, p), mix(miniTop, 0f, p), mix(w - inset, w, p), mix(miniTop + miniH, h, p))

        // Where the cover sits on each page of the full player; animated when the page changes.
        fun pageTarget(page: PlayerPage) = when (page) {
            PlayerPage.MAIN -> Rect(0f, 0f, w, heroHeightPx)
            PlayerPage.LYRICS -> Rect(px(74f), statusTop + px(14f), px(74f + 40f), statusTop + px(54f))
            PlayerPage.QUEUE -> Rect(px(30f), statusTop + px(82f), px(30f + 56f), statusTop + px(138f))
        }
        fun pageCorners(page: PlayerPage) = when (page) {
            PlayerPage.MAIN -> Offset(0f, px(46f)) // (top, bottom)
            PlayerPage.LYRICS -> Offset(px(12f), px(12f))
            PlayerPage.QUEUE -> Offset(px(18f), px(18f))
        }
        val pageRect = remember { Animatable(pageTarget(ctrl.page), Rect.VectorConverter) }
        val pageRadius = remember { Animatable(pageCorners(ctrl.page), Offset.VectorConverter) }
        LaunchedEffect(ctrl.page, w, h) {
            launch { pageRect.animateTo(pageTarget(ctrl.page), Motion.sheet()) }
            launch { pageRadius.animateTo(pageCorners(ctrl.page), Motion.sheet()) }
        }

        fun artRect(): Rect {
            val p = ctrl.progress.value
            val sr = sheetRect(0f)
            val mini = Rect(sr.left + px(8f), sr.top + px(8f), sr.left + px(68f), sr.top + px(68f))
            val full = pageRect.value.let { r ->
                if (ctrl.page == PlayerPage.MAIN) r.translate(0f, -ctrl.mainScroll.value.toFloat()) else r
            }.translate(0f, sheetRect(p).top) // rides down with the sheet while it's dragged
            return lerp(mini, full, p.coerceIn(0f, 1f))
        }

        // Frosted mini player: with an app layer to blur, the mini surface is see-through glass
        // (tint chosen from the cover, see frostTint); without one it stays the old near-opaque colour.
        val appLayer = LocalAppLayer.current
        val miniFrost = androidx.compose.ui.graphics.rememberGraphicsLayer()
        val miniBg = if (appLayer != null) frostTint(c.mini, true) else c.mini

        // 1. The surface: a floating card when collapsed, the whole screen when open. It is drawn
        // full size and clipped to the sheet's shape, so the blurred backdrop inside is drawn once
        // and only the clip moves: no re-render of the blur while the sheet grows.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = ctrl.progress.value
                    // A new shape each frame moves the clip; the content underneath is not redrawn.
                    shape = SheetShape(sheetRect(p), mix(30f, 0f, p).coerceAtLeast(0f).dp.toPx())
                    clip = true
                    shadowElevation = mix(22f, 0f, p).dp.toPx() // lifted: a mini player should float
                },
        ) {
            if (appLayer != null) Box(
                Modifier.fillMaxSize().drawBehind {
                    val p = ctrl.progress.value
                    // Only the resting mini player is blurred: a growing sheet re-blurring each frame is what made opening lag.
                    if (p > 0.01f) return@drawBehind
                    val r = sheetRect(p)
                    drawFrost(appLayer, miniFrost, r.left, r.top, r.left, r.top, r.width, r.height, 22.dp.toPx())
                },
            )
            Box(
                Modifier.fillMaxSize()
                    // Cached as one texture, so scrolling the player page doesn't redraw the whole backdrop each frame.
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawBehind {
                        val p = ctrl.progress.value
                        // The glass tint firms up to solid within the first 1% of the drag, so dropping the blur doesn't pop.
                        val firm = miniBg.copy(alpha = miniBg.alpha + (1f - miniBg.alpha) * (p / 0.01f).coerceIn(0f, 1f))
                        drawRect(lerp(firm, c.bg, p))
                        // Depth while it rests: a soft light on top fading to a faint shade at the bottom,
                        // and a hairline edge, so it reads as a raised pane rather than a flat strip.
                        val lift = 1f - (p / 0.3f).coerceIn(0f, 1f)
                        if (lift > 0f) {
                            val r = sheetRect(p)
                            drawRect(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color.White.copy(alpha = (if (c.dark) 0.10f else 0.50f) * lift),
                                    0.55f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = (if (c.dark) 0.18f else 0.07f) * lift),
                                    startY = r.top, endY = r.bottom,
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
                                size = androidx.compose.ui.geometry.Size(r.width, r.height),
                            )
                            val inset = 0.5.dp.toPx()
                            drawRoundRect(
                                (if (c.dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.70f)).copy(alpha = (if (c.dark) 0.14f else 0.70f) * lift),
                                topLeft = androidx.compose.ui.geometry.Offset(r.left + inset, r.top + inset),
                                size = androidx.compose.ui.geometry.Size(r.width - 2 * inset, r.height - 2 * inset),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(30.dp.toPx() - inset),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                            )
                        }
                    }
                    .backdrop { ctrl.progress.value },
            )
        }
        // Touch area and mini content, sized to the sheet.
        Box(
            Modifier
                .placeAt { sheetRect(ctrl.progress.value) }
                .draggable(
                    rememberDraggableState { ctrl.dragBy(it) },
                    Orientation.Vertical,
                    enabled = !ctrl.isOpen || ctrl.progress.isRunning,
                    onDragStopped = { v -> ctrl.settle(v) },
                )
                .combinedClickable(
                    enabled = !ctrl.isOpen, role = Role.Button, onClickLabel = "Open player",
                    interactionSource = null, indication = null,
                    onLongClickLabel = "Song options",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        miniMenu = true
                    },
                ) { ctrl.expand() },
        ) {
            // Always measured at the resting mini size. The box around it grows with every frame of the
            // opening; without this, the whole mini row (texts, buttons) was measured again each frame
            // for a row that has faded out by a quarter of the way.
            val miniW = (w - 2 * inset).roundToInt().coerceAtLeast(1)
            val miniHi = miniH.roundToInt().coerceAtLeast(1)
            Box(
                Modifier.layout { m, c ->
                    val pl = m.measure(Constraints.fixed(miniW, miniHi))
                    layout(c.maxWidth, c.maxHeight) { pl.place(0, 0) }
                },
            ) {
                MiniContent(vm, ctrl, s.id, s.title, s.artist, now.isPlaying, now.durationMs.takeIf { it > 0 } ?: s.durationMs)
            }
        }

        // 2. The cover, morphing between the mini player, the hero and each page's thumbnail.
        // Neighbours sit either side for swiping between songs.
        // Apple Music's touch: the cover settles back a little while paused and springs forward on play.
        // No overshoot: a bounce past full size pushed the cover out of its frame on play.
        // Vinyl: 33 1/3 rpm = 200 degrees a second while playing; spins down to a stop on pause
        // (a real platter doesn't stop dead). Only the angle state changes, read while drawing.
        val vinylT by androidx.compose.animation.core.animateFloatAsState(
            if (ctrl.vinyl) 1f else 0f, Motion.spatial(), label = "vinylMorph",
        )
        LaunchedEffect(ctrl.vinyl, now.isPlaying) {
            if (!ctrl.vinyl) return@LaunchedEffect
            var last = withFrameNanos { it }
            while (true) {
                val t = withFrameNanos { it }
                val dt = (t - last) / 1e9f
                last = t
                val target = if (now.isPlaying) 200f else 0f
                ctrl.vinylSpeed += (target - ctrl.vinylSpeed) * (dt * 3f).coerceAtMost(1f)
                if (!ctrl.vinylHeld) ctrl.vinylAngle = (ctrl.vinylAngle + ctrl.vinylSpeed * dt) % 360f
                if (!now.isPlaying && ctrl.vinylSpeed < 1f) { ctrl.vinylSpeed = 0f; break }
            }
        }
        val pauseScale by androidx.compose.animation.core.animateFloatAsState(
            if (now.isPlaying) 1f else 0.94f, Motion.ios(0.45f, 1f), label = "pauseScale",
        )
        @Composable
        fun cover(img: ImageBitmap?, offset: Int, desc: String?) {
            Box(
                Modifier
                    // On the queue and lyrics pages the cover sits inside the page's header card, which
                    // has its own background; draw the cover above the page there, or the card hides it.
                    // On the main player it stays below, so the buttons over the hero stay on top.
                    .zIndex(if (offset == 0 && ctrl.page != PlayerPage.MAIN) 2f else 0f)
                    .placeAt { artRect() }
                    .graphicsLayer {
                        val p = ctrl.progress.value
                        val corners = pageRadius.value
                        val miniR = 16.dp.toPx()
                        // Springs can overshoot a little; a corner radius must never go below zero.
                        val top = mix(miniR, corners.x, p).coerceAtLeast(0f)
                        val bottom = mix(miniR, corners.y, p).coerceAtLeast(0f)
                        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
                        clip = true
                        translationX = if (offset == 0) ctrl.skipX.value else ctrl.skipX.value + offset * size.width
                        val vt = if (offset == 0 && ctrl.page == PlayerPage.MAIN) vinylT * p.coerceIn(0f, 1f) else 0f
                        if (vt > 0.001f) {
                            // Morph the cover into a disc in the middle of the hero, then spin it.
                            shape = VinylShape(vt, top, bottom)
                            rotationZ = ctrl.vinylAngle * vt
                            shadowElevation = 20.dp.toPx() * vt
                        } else if (offset == 0 && ctrl.page == PlayerPage.MAIN && !wantsVideo) {
                            // Shrink toward the centre while paused, only once the player is open.
                            // (Not for video: a picture that shrinks on pause looks like a glitch.)
                            val k = ((1f - pauseScale) / 0.06f).coerceIn(0f, 1f) * p.coerceIn(0f, 1f)
                            val s = (1f - 0.06f * k).coerceAtMost(1f)
                            scaleX = s; scaleY = s
                            // Paused, the square top corners round off to match the bottom ones, like a
                            // card. Only the top changes, gradually; the bottom keeps its radius, so no
                            // corner ever jumps from one frame to the next.
                            val t = mix(top, bottom, k).coerceAtLeast(0f)
                            shape = RoundedCornerShape(topStart = t, topEnd = t, bottomStart = bottom, bottomEnd = bottom)
                        }
                        // Neighbours belong to the open player only: gone as soon as it's dragged down.
                        if (offset != 0) alpha = (0.55f + 0.45f * (1f - abs(translationX) / size.width)).coerceIn(0f, 1f) *
                            ((p - 0.97f) / 0.03f).coerceIn(0f, 1f)
                    }
                    .background(c.accent)
                    .drawWithContent {
                        val vt = if (offset == 0 && ctrl.page == PlayerPage.MAIN) vinylT else 0f
                        if (vt > 0.001f) drawVinyl(vt, ctrl.vinylAngle) { this@drawWithContent.drawContent() } else drawContent()
                    },
            ) {
                if (img != null) Image(img, desc, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // Video mode: the video over the cover, on the hero only (not the small thumbnails of the other pages).
                if (offset == 0 && ctrl.page == PlayerPage.MAIN && wantsVideo) VideoLayer(vm, ctrl.videoFill, showSubtitles = ctrl.isOpen) { ctrl.videoFill = !ctrl.videoFill }
            }
        }
        // Neighbours only once the cover has settled in the hero; during the move between pages
        // they used to show up beside the shrinking cover.
        val pagerOn = ctrl.page == PlayerPage.MAIN && open && !pageRect.isRunning && !ctrl.vinyl
        if (pagerOn && prevSong != null) cover(prevImg, -1, null)
        if (pagerOn && nextSong != null) cover(nextImg, 1, null)
        cover(curImg ?: artImg, 0, "${s.album} cover")
        // The tonearm: doesn't turn with the record; rests on it while playing, lifts on pause.
        val armDown by androidx.compose.animation.core.animateFloatAsState(
            if (now.isPlaying) 1f else 0f, Motion.ios(0.6f, 1f), label = "tonearm",
        )
        if (ctrl.page == PlayerPage.MAIN && (ctrl.vinyl || vinylT > 0.001f)) {
            Box(
                Modifier.placeAt { artRect() }.drawBehind {
                    val a = (vinylT * ctrl.progress.value).coerceIn(0f, 1f)
                    if (a > 0.01f) drawTonearm(a, armDown)
                },
            )
        }

        // 3. The full player's pages, fading in over the last part of the opening.
        // Built ahead of time, a moment after a song is loaded, and only *placed* while the player
        // is open. Building them on the first frame of opening is what froze the animation
        // (it showed half-open, then fully open: "two frames").
        var warm by remember { mutableStateOf(false) }
        // After app start has settled, not during its first frames.
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(1200); warm = true }
        if (open || warm || ctrl.warmRequested) {
            androidx.compose.runtime.SideEffect { ctrl.pagesBuilt = true }
            Box(
                Modifier.fillMaxSize()
                    .layout { m, c ->
                        val pl = m.measure(c)
                        layout(pl.width, pl.height) { if (ctrl.progress.value > 0.001f) pl.place(0, 0) }
                    }
                    .graphicsLayer {
                    val p = ctrl.progress.value
                    alpha = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    // Ride with the sheet's top edge and stay inside it. Before, the pages stayed
                    // full screen and only faded, so dragging down showed them ghosted over Home.
                    val r = sheetRect(p)
                    translationY = r.top
                    shape = SheetShape(r.translate(0f, -r.top), mix(30f, 0f, p).coerceAtLeast(0f).dp.toPx())
                    clip = true
                },
            ) {
                val visibleState = remember { androidx.compose.runtime.derivedStateOf { ctrl.progress.value > 0.001f } }
                androidx.compose.runtime.CompositionLocalProvider(com.skarif.kairos.ui.screens.LocalPlayerVisible provides visibleState) {
                AnimatedContent(
                    ctrl.page,
                    // Player, lyrics and queue: a quick crossfade while the cover glides to its new
                    // place, as in Apple Music. The cover carries the motion; the pages just swap.
                    transitionSpec = { fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                    label = "playerPage",
                ) { page ->
                    when (page) {
                        PlayerPage.MAIN -> NowPlayingPane(
                            vm, ctrl,
                            onLyrics = { ctrl.page = PlayerPage.LYRICS },
                            onQueue = { ctrl.page = PlayerPage.QUEUE },
                            onEqualizer = onEqualizer, onSleep = onSleep,
                            onAlbum = { id -> ctrl.collapse(); onAlbum(id) },
                            onOpenCollection = { k -> ctrl.collapse(); onOpenCollection(k) },
                            onSkip = { dir, vx ->
                                // Finish the swipe: slide the neighbour fully in, then change song.
                                scope.launch {
                                    val go = if (dir < 0) nextSong != null else prevSong != null
                                    if (go && dir != 0) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Change song now, as the buttons do; the slide carries on meanwhile.
                                        ctrl.pendingSkip = dir
                                        if (dir < 0) vm.next() else vm.previousTrack()
                                        ctrl.skipX.animateTo(dir * w, Motion.sheet(), initialVelocity = vx)
                                        // Slide done before the song arrived: the neighbour is centred,
                                        // so the plain snap back on arrival is right.
                                        ctrl.pendingSkip = 0
                                    } else {
                                        ctrl.skipX.animateTo(0f, Motion.spatial(), initialVelocity = vx)
                                    }
                                }
                            },
                            hasPrev = prevSong != null, hasNext = nextSong != null,
                        )
                        PlayerPage.LYRICS -> LyricsPane(vm, onBack = { ctrl.page = PlayerPage.MAIN }, onQueue = { ctrl.page = PlayerPage.QUEUE }, onCollapse = { ctrl.collapse() })
                        PlayerPage.QUEUE -> QueuePane(
                            vm, onBack = { ctrl.page = PlayerPage.MAIN },
                            onSaveAsPlaylist = { ctrl.collapse(); onSaveQueue() },
                            onOpenAlbum = { id -> ctrl.collapse(); onAlbum(id) },
                        )
                    }
                }
                }
            }
        }
    }
}

/** Collapsed row: title, artist, play/pause, next, and a thin progress line. Fades out as it opens. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniContent(vm: MainViewModel, ctrl: PlayerController, songId: Long, title: String, artist: String, playing: Boolean, durationMs: Long) {
    val c = P.colors
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val liked = songId in favs
    val pos = vm.position.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var nudge by remember { mutableFloatStateOf(0f) }
    val nudgeAnim = remember { Animatable(0f) }
    // The current synced lyric line replaces the artist while it plays (Settings > Lyrics in the mini player).
    val miniSettings by vm.settings.collectAsStateWithLifecycle()
    val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsOffset by vm.lyricsOffset.collectAsStateWithLifecycle()
    val syncedLyrics = (lyricsState as? com.skarif.kairos.ui.LyricsState.Ready)?.lyrics?.takeIf { it.synced && !it.isEmpty() }
    val subtitle by remember(syncedLyrics, miniSettings.miniLyrics, playing, lyricsOffset, artist) {
        derivedStateOf {
            val i = if (miniSettings.miniLyrics && playing && syncedLyrics != null) syncedLyrics.indexAt(pos.value - lyricsOffset) else -1
            (if (i >= 0) syncedLyrics?.lines?.get(i)?.text?.trim() else null).takeUnless { it.isNullOrEmpty() } ?: artist
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .graphicsLayer {
                alpha = (1f - ctrl.progress.value * 4f).coerceIn(0f, 1f)
                translationX = nudgeAnim.value
            }
            .pointerInput(Unit) {
                // Swipe the mini player sideways to change song, with the shared swipe rule.
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val dir = Swipe.decide(nudge, tracker.calculateVelocity().x, density)
                        nudge = 0f
                        scope.launch {
                            if (dir != 0) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (dir < 0) vm.next() else vm.previousTrack()
                            }
                            nudgeAnim.animateTo(0f, Motion.spatial())
                        }
                    },
                    onDragCancel = { nudge = 0f; scope.launch { nudgeAnim.animateTo(0f, Motion.spatial()) } },
                ) { change, amount ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    nudge += amount
                    scope.launch { nudgeAnim.snapTo(nudge * 0.5f) }
                }
            }
            .drawBehind {
                // Invisible once the player is a quarter open: skip, and stop listening to the position meanwhile.
                if (ctrl.progress.value >= 0.25f) return@drawBehind
                val p = pos.value.toFloat() / durationMs.coerceAtLeast(1)
                val y = size.height - 2.dp.toPx()
                val l = 22.dp.toPx()
                drawLine(c.line, Offset(l, y), Offset(size.width - l, y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(c.accent, Offset(l, y), Offset(l + (size.width - 2 * l) * p.coerceIn(0f, 1f), y), 3.dp.toPx(), StrokeCap.Round)
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.width(60.dp)) // the cover is drawn here by the cover layer
            Column(Modifier.weight(1f)) {
                Text(title, style = T.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = T.small.copy(fontSize = 13.sp), color = if (subtitle == artist) c.muted else c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CircleButton(
                if (liked) Ic.HeartFilled else Ic.Heart,
                if (liked) "Remove from favourites" else "Add to favourites",
                { vm.toggleFavorite(songId) },
                size = 42.dp, container = Color.Transparent, content = if (liked) c.accent else c.text,
            )
            CircleButton(if (playing) Ic.Pause else Ic.Play, if (playing) "Pause" else "Play", vm::toggle, size = 46.dp, container = c.ink, content = c.inkFg)
            CircleButton(Ic.Next, "Next song", vm::next, size = 42.dp, container = Color.Transparent)
        }
    }
}

/** A rounded rectangle at a given position inside the layer: the player's clip. */
private data class SheetShape(val rect: Rect, val radius: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ) = androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(radius)))
}

/** The record's disc inside the cover's box: [t] 0 = the cover's own shape, 1 = a centred circle. */
private data class VinylShape(val t: Float, val top: Float, val bottom: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val d = minOf(size.width, size.height) * 0.84f
        val disc = Rect(Offset((size.width - d) / 2, (size.height - d) / 2), androidx.compose.ui.geometry.Size(d, d))
        val r = androidx.compose.ui.geometry.lerp(Rect(Offset.Zero, size).topLeft, disc.topLeft, t)
        val r2 = androidx.compose.ui.geometry.lerp(Rect(Offset.Zero, size).bottomRight, disc.bottomRight, t)
        val rr = Rect(r, r2)
        val tr = mix(top, d / 2, t)
        val br = mix(bottom, d / 2, t)
        return androidx.compose.ui.graphics.Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(
                rr, topLeft = androidx.compose.ui.geometry.CornerRadius(tr), topRight = androidx.compose.ui.geometry.CornerRadius(tr),
                bottomRight = androidx.compose.ui.geometry.CornerRadius(br), bottomLeft = androidx.compose.ui.geometry.CornerRadius(br),
            ),
        )
    }
}

/**
 * The record, drawn inside the cover's (rotating) layer. [t] is the morph: at 0 the plain cover,
 * at 1 a black disc whose paper label is the whole cover art, shrunk into the middle.
 * [angle] is the record's turn: the light's reflections are drawn turned back by it, so they stay
 * still while the grooves spin under them, which is what makes it read as spinning.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVinyl(t: Float, angle: Float, content: () -> Unit) {
    val d = minOf(size.width, size.height) * 0.84f
    val r = d / 2
    val c = center
    val a = t.coerceIn(0f, 1f)
    val labelR = r * 0.36f
    // Vinyl.
    drawCircle(Color(0xFF0C0C0E).copy(alpha = a), r, c)
    // Grooves: fine rings, a little lighter every few for the bands between tracks.
    var g = labelR + 6f
    var k = 0
    while (g < r - 3f) {
        drawCircle(Color.White.copy(alpha = (if (k % 9 == 0) 0.08f else 0.035f) * a), g, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        g += 4.5f; k++
    }
    // Reflections, held still against the spin.
    rotate(-angle, c) {
        drawCircle(
            androidx.compose.ui.graphics.Brush.sweepGradient(
                0f to Color.Transparent, 0.1f to Color.White.copy(alpha = 0.16f * a), 0.18f to Color.Transparent,
                0.5f to Color.Transparent, 0.6f to Color.White.copy(alpha = 0.12f * a), 0.68f to Color.Transparent, 1f to Color.Transparent,
                center = c,
            ),
            r, c,
        )
    }
    // The label: the whole cover, scaled from full size into the middle as the morph runs.
    val labelScale = 1f + ((labelR * 2) / minOf(size.width, size.height) * 1.02f - 1f) * a
    val clipR = mix(maxOf(size.width, size.height), labelR, a)
    clipPath(androidx.compose.ui.graphics.Path().apply { addOval(Rect(c, clipR)) }) {
        scale(labelScale, c) { content() }
    }
    // Label rim and the spindle hole.
    drawCircle(Color.Black.copy(alpha = 0.35f * a), labelR, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    drawCircle(Color(0xFFE9E9E4).copy(alpha = a), r * 0.035f, c)
    drawCircle(Color(0xFF0C0C0E).copy(alpha = a), r * 0.018f, c)
}

/** Tonearm in the hero's top-right: pivot, arm and headshell. [down] 1 = resting on the record. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTonearm(alpha: Float, down: Float) {
    val d = minOf(size.width, size.height) * 0.84f
    val r = d / 2
    val pivot = Offset(center.x + r * 0.98f, center.y - r * 0.92f)
    val len = r * 1.32f
    // Pointing down-left; lowering swings it in over the grooves.
    val deg = 112f + 24f * down
    val rad = Math.toRadians(deg.toDouble())
    val tip = Offset(pivot.x + (len * kotlin.math.cos(rad)).toFloat(), pivot.y + (len * kotlin.math.sin(rad)).toFloat())
    val metal = Color(0xFFD9D9D4).copy(alpha = alpha)
    val dark = Color(0xFF2A2A2C).copy(alpha = alpha)
    drawLine(Color.Black.copy(alpha = 0.25f * alpha), pivot + Offset(6f, 8f), tip + Offset(6f, 8f), 10f, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(metal, pivot, tip, 7f, androidx.compose.ui.graphics.StrokeCap.Round)
    rotate(deg - 90f, tip) {
        drawRoundRect(dark, Offset(tip.x - 13f, tip.y - 4f), androidx.compose.ui.geometry.Size(26f, 34f), androidx.compose.ui.geometry.CornerRadius(5f))
    }
    drawCircle(dark, 26f, pivot)
    drawCircle(metal, 15f, pivot)
}
