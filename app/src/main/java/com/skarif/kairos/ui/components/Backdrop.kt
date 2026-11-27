package com.skarif.kairos.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.skarif.kairos.data.DEFAULT_BACKGROUND_GLOW
import com.skarif.kairos.ui.theme.P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The app's background: theme colour, the album cover heavily blurred, a wash, and a dot grid.
 *
 * Every page draws its own copy, so pages are opaque and can slide over each other the iOS way.
 * That is only cheap because nothing here is blurred live: the cover is shrunk to 64 px,
 * box-blurred and colour-boosted once per song on a background thread, then drawn stretched
 * (two drifting textured rectangles). The dots are one tiny tile repeated by a shader. No
 * RenderEffect, no Crossfade, no per-frame colour filter, the same on every Android version.
 *
 * The motion clock ticks at about 30 fps, not at the display's rate: the drift is slow (periods
 * of 5 to 13 s), so 120 redraws a second of a full-screen, multi-layer backdrop bought nothing
 * visible and cost a lot of GPU time.
 */

/** The current and previous blurred covers, shared by every page and the player. */
@Stable
class BackdropState {
    var current by mutableStateOf<ImageBitmap?>(null)
        internal set
    var previous by mutableStateOf<ImageBitmap?>(null)
        internal set
    /** 0 → 1 while the new cover fades in over the old one. Read only while drawing. */
    internal val fade = Animatable(1f)
    var enabled by mutableStateOf(true)
        internal set
    /** Settings > Background glow, 0..1. Read only while drawing. */
    var glow by mutableFloatStateOf(DEFAULT_BACKGROUND_GLOW)
        internal set
    /** Two vivid colours taken from the current and previous cover; drawn as soft drifting glows. */
    var tints by mutableStateOf<CoverTints?>(null)
        internal set
    var previousTints by mutableStateOf<CoverTints?>(null)
        internal set
    /**
     * Seconds of "living" motion: advances only while music plays, so the cover drifts and
     * breathes with the music and freezes where it is on pause. Read only while drawing.
     */
    internal var phase by mutableFloatStateOf(0f)

    /** When something in the app last scrolled (System.nanoTime). Plain field: nothing recomposes for it. */
    @Volatile internal var lastScrollNanos: Long = 0L

    /**
     * Put this on the app root with Modifier.nestedScroll: every list below reports its drags and
     * flings through it, and the living motion pauses meanwhile so scrolling gets the whole GPU.
     * It consumes nothing.
     */
    internal val scrollWatch = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
            lastScrollNanos = System.nanoTime()
            return Offset.Zero
        }
        override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
            lastScrollNanos = System.nanoTime()
            return androidx.compose.ui.unit.Velocity.Zero
        }
    }
}

// How much of the theme's veil is drawn over the living cover (was 0.88 / 0.42 / 0.52 / 0.86 / 0.72).
// Lower = more cover colour shows through; raise VEIL_MID if text over a very bright cover gets hard to read.
private const val VEIL_EDGE = 0.58f
private const val VEIL_MID = 0.22f
private const val VEIL_MID_PURE = 0.30f
private const val VEIL_LIGHT = 0.40f

/** The backdrop's motion clock steps at most this often (see [rememberBackdrop]). */
private const val MOTION_STEP_NANOS = 28_000_000L

/** The motion stays paused this long after the last scroll movement. */
private const val SCROLL_IDLE_NANOS = 250_000_000L

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

/** Keeps [BackdropState] in step with the playing song's artwork. Call once, at the app root. */
@Composable
fun rememberBackdrop(art: Bitmap?, enabled: Boolean, playing: Boolean = false, glow: Float = DEFAULT_BACKGROUND_GLOW): BackdropState {
    val state = remember { BackdropState() }
    state.enabled = enabled
    state.glow = glow.coerceIn(0f, 1f)
    val dark = P.colors.dark
    // The living background: runs at frame rate while music plays, and the frame clock stops when
    // the app isn't visible, so this costs nothing in the background.
    LaunchedEffect(playing, enabled, art) {
        if (!playing || !enabled || art == null) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Scrolling: hold still (and don't let the paused time jump the drift on resume).
            if (now - state.lastScrollNanos < SCROLL_IDLE_NANOS) { last = now; continue }
            // Write the clock only every ~30 ms (30 fps on 60/90/120 Hz screens): each write
            // redraws every visible backdrop. Time still advances by what really elapsed, so the
            // speed of the drift is unchanged.
            if (now - last >= MOTION_STEP_NANOS) {
                state.phase += (now - last) / 1e9f
                last = now
            }
        }
    }
    LaunchedEffect(art, enabled, dark) {
        // Tints are read from the plain blur; the colour boost is then baked into the bitmap once,
        // so drawing never runs a saturation filter over a full-screen texture again.
        val built = if (art == null || !enabled) null else withContext(Dispatchers.Default) {
            val tiny = blurredTiny(art)
            saturated(tiny, if (dark) 1.55f else 1.25f) to coverTints(tiny)
        }
        val next = built?.first
        if (next === state.current) return@LaunchedEffect
        state.previous = state.current
        state.previousTints = state.tints
        state.current = next
        state.tints = built?.second
        state.fade.snapTo(0f)
        state.fade.animateTo(1f, tween(600, easing = LinearEasing))
        state.previous = null
        state.previousTints = null
    }
    return state
}

/**
 * A page's background. Opaque; costs about as much as drawing one image. The backdrop sits in its
 * own layer under the content, so the living motion redraws that one image, never the page.
 */
@Composable
fun ScreenBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize()) {
        // Offscreen: the backdrop is rendered into its own texture and only re-rendered when it
        // changes (motion tick, new cover). Without it Android re-executes all of its full-screen
        // draws on every frame of anything on top, such as a scrolling list, even while it is still.
        Box(Modifier.matchParentSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.backdrop())
        content()
    }
}

/** Draws the backdrop behind this element. [alpha] is read while drawing. */
@Composable
fun Modifier.backdrop(alpha: () -> Float = { 1f }): Modifier {
    val c = P.colors
    val state = LocalBackdrop.current
    return drawWithCache {
        val step = 20.dp.toPx().toInt().coerceAtLeast(4)
        // Light dots on dark, grey on light: the old grey dots read as noise on a dark page.
        val tile = dotTile(step, 1.1.dp.toPx(), c.dots)
        val dots = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        // (The saturation boost that keeps the cover colours vivid under the theme wash is baked
        // into the cover bitmap, see rememberBackdrop.)
        // Pure black is the theme with the darkest wash; keep enough contrast while letting colour waves show.
        val pure = c.bg == Color.Black
        onDrawBehind {
            val a = alpha().coerceIn(0f, 1f)
            if (a <= 0f) return@onDrawBehind
            drawRect(c.bg, alpha = a)
            val cur = state.current
            if (state.enabled && (cur != null || state.previous != null)) {
                val f = state.fade.value
                val t = state.phase
                state.previous?.let { drawCover(it, a * (1f - f), t) }
                cur?.let { drawCover(it, a * f, t) }
                // Soft colour glows from the cover, drifting slowly, under the veil so text stays readable.
                state.previousTints?.let { drawTints(it, a * (1f - f), t, c.dark, state.glow) }
                state.tints?.let { drawTints(it, a * f, t, c.dark, state.glow) }
                if (c.dark) {
                    // A thin veil so the cover's colour can bloom through. It stays heavier only at
                    // the top and bottom edges, where the status bar, titles and dock need contrast.
                    // glow 0.5 keeps the constants as they are (default is a little lower, so a little heavier); 1 leaves only a trace, 0 doubles them.
                    val k = 0.15f + 1.7f * (1f - state.glow)
                    val mid = (if (pure) VEIL_MID_PURE else VEIL_MID) * k
                    val edge = VEIL_EDGE * k
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to c.veil.copy(alpha = (c.veil.alpha * edge).coerceIn(0f, 1f)),
                            0.45f to c.veil.copy(alpha = (c.veil.alpha * mid).coerceIn(0f, 1f)),
                            1f to c.veil.copy(alpha = (c.veil.alpha * edge).coerceIn(0f, 1f)),
                        ),
                        alpha = a,
                    )
                } else drawRect(c.veil, alpha = (a * VEIL_LIGHT * (0.15f + 1.7f * (1f - state.glow))).coerceIn(0f, 1f))
            }
            drawRect(dots, alpha = a)
        }
    }
}

/**
 * Dual-layer counter-drifting cover: a base layer plus a mirrored/counter-rotating second layer
 * on an opposing Lissajous path so colour pockets visibly morph and flow into each other.
 */
private fun DrawScope.drawCover(img: ImageBitmap, alpha: Float, t: Float = 0f, filter: androidx.compose.ui.graphics.ColorFilter? = null) {
    if (alpha <= 0f) return
    val tau = 2f * PI.toFloat()

    // Layer 1: primary drift, breath, and rotation.
    val breath1 = 1f + 0.14f * sin(t * tau / 5.5f)
    val side1 = maxOf(size.width, size.height) * 2.0f * breath1
    val dx1 = size.width * 0.24f * sin(t * tau / 7.5f)
    val dy1 = size.height * 0.16f * sin(t * tau / 6.5f + 1.3f)
    val angle1 = 22f * sin(t * tau / 11f + 0.6f)
    val dst1 = IntSize(side1.toInt(), side1.toInt())
    val off1 = IntOffset(((size.width - side1) / 2 + dx1).toInt(), ((size.height - side1) / 2 + dy1).toInt())

    // Layer 2: counter-drifting mirrored layer so bright/dark regions sweep across layer 1.
    val breath2 = 1f + 0.16f * sin(t * tau / 4.7f + 2.1f)
    val side2 = maxOf(size.width, size.height) * 2.1f * breath2
    val dx2 = -size.width * 0.26f * sin(t * tau / 6.2f + 0.8f)
    val dy2 = -size.height * 0.18f * sin(t * tau / 5.3f + 2.4f)
    val angle2 = -28f * sin(t * tau / 9.2f + 1.7f) + 45f
    val dst2 = IntSize(side2.toInt(), side2.toInt())
    val off2 = IntOffset(((size.width - side2) / 2 + dx2).toInt(), ((size.height - side2) / 2 + dy2).toInt())

    clipRect {
        rotate(angle1, pivot = center + androidx.compose.ui.geometry.Offset(dx1, dy1)) {
            drawImage(img, dstOffset = off1, dstSize = dst1, alpha = alpha, colorFilter = filter, filterQuality = FilterQuality.Low)
        }
        rotate(angle2, pivot = center + androidx.compose.ui.geometry.Offset(dx2, dy2)) {
            drawImage(img, dstOffset = off2, dstSize = dst2, alpha = alpha * 0.55f, colorFilter = filter, filterQuality = FilterQuality.Low)
        }
    }
}

/** Two vivid colours from a cover: the weighted average of its upper-left and lower-right halves. */
@Stable
class CoverTints(val a: Color, val b: Color)

/**
 * Picks two glow colours from the small blurred cover. Each pixel counts by how vivid and bright it
 * is, so a mostly dark or grey cover still gives its one bright accent instead of mud.
 */
internal fun coverTints(img: ImageBitmap): CoverTints {
    val w = img.width
    val h = img.height
    val px = IntArray(w * h)
    img.readPixels(px)
    val sum = Array(2) { DoubleArray(4) } // r, g, b, weight for each half
    val hsv = FloatArray(3)
    for (y in 0 until h) for (x in 0 until w) {
        val p = px[y * w + x]
        android.graphics.Color.colorToHSV(p, hsv)
        val wt = (hsv[1] * hsv[2]).toDouble() + 0.02
        val g = if (x * h + y * w < w * h) 0 else 1 // above or below the anti-diagonal
        sum[g][0] += ((p shr 16) and 255) * wt
        sum[g][1] += ((p shr 8) and 255) * wt
        sum[g][2] += (p and 255) * wt
        sum[g][3] += wt
    }
    fun tint(g: DoubleArray): Color {
        if (g[3] <= 0.0) return Color.Transparent
        val rgb = android.graphics.Color.rgb((g[0] / g[3]).toInt(), (g[1] / g[3]).toInt(), (g[2] / g[3]).toInt())
        android.graphics.Color.colorToHSV(rgb, hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceIn(0.55f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
    return CoverTints(tint(sum[0]), tint(sum[1]))
}

/** Two big, soft radial glows on slow Lissajous paths; they move only while music plays ([t] stands still on pause). */
private fun DrawScope.drawTints(tints: CoverTints, alpha: Float, t: Float, dark: Boolean, glow: Float) {
    if (alpha <= 0f) return
    val tau = 2f * PI.toFloat()
    // Less glow setting = heavier veil = quieter tints.
    val strength = (if (dark) 0.42f else 0.30f) * (0.4f + 0.6f * glow) * alpha
    val r = maxOf(size.width, size.height) * 0.8f
    val c1 = Offset(size.width * (0.28f + 0.22f * sin(t * tau / 13f)), size.height * (0.22f + 0.14f * sin(t * tau / 9f + 1.1f)))
    val c2 = Offset(size.width * (0.74f + 0.20f * sin(t * tau / 11f + 2.3f)), size.height * (0.78f + 0.14f * sin(t * tau / 8f + 0.4f)))
    drawCircle(brush = Brush.radialGradient(listOf(tints.a.copy(alpha = strength), Color.Transparent), center = c1, radius = r), radius = r, center = c1)
    drawCircle(brush = Brush.radialGradient(listOf(tints.b.copy(alpha = strength), Color.Transparent), center = c2, radius = r), radius = r, center = c2)
}

private fun dotTile(step: Int, radius: Float, color: Color): ImageBitmap {
    val bmp = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()) }
    canvas.drawCircle(step / 2f, step / 2f, radius, paint)
    return bmp.asImageBitmap()
}

/** [img] with its saturation scaled by [amount] (1 = unchanged), same luminance weights as ColorMatrix.setToSaturation. */
internal fun saturated(img: ImageBitmap, amount: Float): ImageBitmap {
    val w = img.width
    val h = img.height
    val px = IntArray(w * h)
    img.readPixels(px)
    for (i in px.indices) {
        val p = px[i]
        val r = (p shr 16) and 255
        val g = (p shr 8) and 255
        val b = p and 255
        val l = 0.213f * r + 0.715f * g + 0.072f * b
        val nr = (l + amount * (r - l)).toInt().coerceIn(0, 255)
        val ng = (l + amount * (g - l)).toInt().coerceIn(0, 255)
        val nb = (l + amount * (b - l)).toInt().coerceIn(0, 255)
        px[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * 64 px copy of [src], lightly box-blurred twice so distinct colour pockets remain visible
 * when animated across the screen.
 */
internal fun blurredTiny(src: Bitmap, side: Int = 64, radius: Int = 1): ImageBitmap {
    val small = Bitmap.createScaledBitmap(src, side, side, true)
    val px = IntArray(side * side)
    small.getPixels(px, 0, side, 0, 0, side, side)
    val tmp = IntArray(px.size)
    repeat(2) {
        boxPass(px, tmp, side, radius, horizontal = true)
        boxPass(tmp, px, side, radius, horizontal = false)
    }
    val out = Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
    if (small !== src) small.recycle()
    return out.asImageBitmap()
}

private fun boxPass(src: IntArray, dst: IntArray, n: Int, r: Int, horizontal: Boolean) {
    val win = 2 * r + 1
    for (line in 0 until n) {
        var a = 0; var rr = 0; var g = 0; var b = 0
        fun at(i: Int): Int {
            val k = i.coerceIn(0, n - 1)
            return if (horizontal) src[line * n + k] else src[k * n + line]
        }
        for (i in -r..r) { val p = at(i); a += p ushr 24; rr += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255 }
        for (i in 0 until n) {
            val o = if (horizontal) line * n + i else i * n + line
            dst[o] = ((a / win) shl 24) or ((rr / win) shl 16) or ((g / win) shl 8) or (b / win)
            val add = at(i + r + 1); val sub = at(i - r)
            a += (add ushr 24) - (sub ushr 24)
            rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
            g += ((add shr 8) and 255) - ((sub shr 8) and 255)
            b += (add and 255) - (sub and 255)
        }
    }
}
