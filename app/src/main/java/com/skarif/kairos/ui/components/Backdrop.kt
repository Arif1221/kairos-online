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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
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
 * Colour: the glows come from the cover's two most vivid, distinct hues (see [coverTints]), not
 * from an average, which turns a multi-coloured cover brown. With no cover (nothing playing, or a
 * song with no art) the page isn't left flat grey: soft glows in the theme's accent colour take over.
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
    /** Average brightness of the current cover, 0 (black) to 1 (white). Frosted bars use it to pick their tint. */
    var luma by mutableFloatStateOf(0.5f)
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
private const val VEIL_MID = 0.30f
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
            // Light theme: lift the cover's brightness (keeping hue and saturation), so a dark cover
            // becomes a bright pastel instead of a muddy grey-purple under the pale veil.
            // Dark theme, the mirror image: a bright cover is brought down to a deep, rich tone (no neon),
            // so both themes read as the same soft, tinted page.
            val shown = if (dark) dimmed(tiny, DARK_CEILING) else lifted(tiny, LIGHT_FLOOR)
            Triple(saturated(shown, if (dark) 1.15f else 1.25f), coverTints(tiny), lumaOf(shown))
        }
        val next = built?.first
        if (next === state.current) return@LaunchedEffect
        state.previous = state.current
        state.previousTints = state.tints
        state.current = next
        state.tints = built?.second
        state.luma = built?.third ?: 0.5f
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
    val source = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val frost = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val glass = remember(source, frost) { GlassState(source, frost) }
    Box(modifier.fillMaxSize()) {
        // Offscreen: the backdrop is rendered into its own texture and only re-rendered when it
        // changes (motion tick, new cover). Without it Android re-executes all of its full-screen
        // draws on every frame of anything on top, such as a scrolling list, even while it is still.
        Box(Modifier.matchParentSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.backdrop())
        androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides glass) {
            // The page is recorded into a layer only while the small title bar is showing (it is the
            // only thing that reads it). While hidden the page draws straight, as before the glass bar.
            Box(
                Modifier.matchParentSize().drawWithContent {
                    if (glass.title != null && glass.showBar) {
                        source.record { this@drawWithContent.drawContent() }
                        drawLayer(source)
                    } else drawContent()
                },
                content = content,
            )
            if (glass.title != null) GlassTitleBar(glass)
        }
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
        // With no cover to take colour from, the theme accent tints the page instead (not on pure black, whose point is no light).
        val ambient = ambientTints(c.accent)
        onDrawBehind {
            val a = alpha().coerceIn(0f, 1f)
            if (a <= 0f) return@onDrawBehind
            drawRect(c.bg, alpha = a)
            val cur = state.current
            val hasCover = cur != null || state.previous != null
            if (state.enabled && (hasCover || !pure)) {
                val f = state.fade.value
                val t = state.phase
                if (hasCover) {
                    // The first cover fades in over the idle glow instead of the glow vanishing first.
                    if (state.previous == null && !pure) drawTints(ambient, a * (1f - f), t, c.dark, state.glow, 0.75f)
                    state.previous?.let { drawCover(it, a * (1f - f), t) }
                    cur?.let { drawCover(it, a * f, t) }
                    // Soft colour glows from the cover, drifting slowly, under the veil so text stays readable.
                    state.previousTints?.let { drawTints(it, a * (1f - f), t, c.dark, state.glow) }
                    state.tints?.let { drawTints(it, a * f, t, c.dark, state.glow) }
                } else {
                    drawTints(ambient, a, t, c.dark, state.glow, 0.75f)
                }
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

/** Three glow colours: the cover's main hue, a second and a third. */
@Stable
class CoverTints(val a: Color, val b: Color, val c: Color = b)

private const val HUE_BINS = 12

/** [c] with its hue turned by [degrees]. */
private fun hueShifted(c: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Makes a picked colour glow-ready: a little more saturated, never dark. */
private fun finishTint(rgb: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(rgb, hsv)
    hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f)
    hsv[2] = hsv[2].coerceIn(0.6f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Picks up to three glow colours from the small blurred cover by hue. Pixels are sorted into 12
 * hue buckets, each pixel counting by how vivid it is (squared, so a dull mass can't outvote a
 * small bright accent). The heaviest bucket is the first colour; the second and third are the
 * heaviest buckets at least two steps (60 degrees) away from the ones already taken, so the glows are
 * truly different colours instead of three shades of one. A cover with only one hue gets its
 * neighbours on the colour wheel instead. The old version averaged the cover's two halves, which
 * turned any multi-coloured cover into the same muddy brown.
 */
internal fun coverTints(img: ImageBitmap): CoverTints {
    val w = img.width
    val h = img.height
    val px = IntArray(w * h)
    img.readPixels(px)
    val wt = DoubleArray(HUE_BINS)
    val sr = DoubleArray(HUE_BINS)
    val sg = DoubleArray(HUE_BINS)
    val sb = DoubleArray(HUE_BINS)
    val hsv = FloatArray(3)
    for (p in px) {
        android.graphics.Color.colorToHSV(p, hsv)
        val vivid = (hsv[1] * hsv[2]).toDouble()
        val weight = vivid * vivid + 0.005
        val bin = ((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
        wt[bin] += weight
        sr[bin] += ((p shr 16) and 255) * weight
        sg[bin] += ((p shr 8) and 255) * weight
        sb[bin] += (p and 255) * weight
    }
    fun bucket(i: Int): Color =
        if (wt[i] <= 0.0) Color.Transparent
        else finishTint(android.graphics.Color.rgb((sr[i] / wt[i]).toInt(), (sg[i] / wt[i]).toInt(), (sb[i] / wt[i]).toInt()))
    fun apart(i: Int, j: Int): Boolean { val d = kotlin.math.abs(i - j); return minOf(d, HUE_BINS - d) >= 2 }
    val first = wt.indices.maxByOrNull { wt[it] } ?: 0
    val second = wt.indices.filter { apart(it, first) }.maxByOrNull { wt[it] }
    val a = bucket(first)
    // A second hue joins only if it really shows (a quarter of the first's weight); a stray accent
    // would just add clutter, so otherwise the second glow is a close neighbour of the first.
    val min = wt[first] * 0.25
    val b = if (second != null && wt[second] > min) bucket(second) else hueShifted(a, 24f)
    val c = b // the third glow is no longer drawn
    return CoverTints(a, b, c)
}

/** Glow colours from the theme accent alone: the accent and its two neighbours on the colour wheel. */
internal fun ambientTints(accent: Color): CoverTints {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    val s = if (hsv[1] < 0.12f) hsv[1] else hsv[1].coerceAtLeast(0.45f)
    val v = hsv[2].coerceIn(0.6f, 1f)
    fun at(d: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(((hsv[0] + d) % 360f + 360f) % 360f, s, v)))
    return CoverTints(at(0f), at(40f), at(-40f))
}

/**
 * Two big, soft radial glows (top-left and bottom-right) on slow Lissajous paths; they move only while music plays ([t] stands
 * still on pause). [boost] scales the strength (the no-cover glows are a little quieter).
 */
private fun DrawScope.drawTints(tints: CoverTints, alpha: Float, t: Float, dark: Boolean, glow: Float, boost: Float = 1f) {
    if (alpha <= 0f) return
    val tau = 2f * PI.toFloat()
    // Less glow setting = heavier veil = quieter tints.
    val strength = (if (dark) 0.30f else 0.30f) * (0.4f + 0.6f * glow) * alpha * boost
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

/** Dark theme: the brightest a cover pixel may be (HSV value, 0..1). Lower for deeper, calmer colour. */
private const val DARK_CEILING = 0.62f

/** Light theme: the darkest a cover pixel may be (HSV value, 0..1). Raise for brighter, lower for more contrast. */
private const val LIGHT_FLOOR = 0.66f

/** [img] with HSV value remapped from 0..1 to [floor]..1: hue and saturation stay, darkness goes. */
internal fun lifted(img: ImageBitmap, floor: Float): ImageBitmap {
    val w = img.width
    val h = img.height
    val px = IntArray(w * h)
    img.readPixels(px)
    val hsv = FloatArray(3)
    for (i in px.indices) {
        android.graphics.Color.colorToHSV(px[i], hsv)
        hsv[2] = floor + (1f - floor) * hsv[2]
        px[i] = android.graphics.Color.HSVToColor(px[i] ushr 24, hsv)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** [img] with HSV value scaled to 0..[ceiling]: hue and saturation stay, glare goes. */
internal fun dimmed(img: ImageBitmap, ceiling: Float): ImageBitmap {
    val w = img.width
    val h = img.height
    val px = IntArray(w * h)
    img.readPixels(px)
    val hsv = FloatArray(3)
    for (i in px.indices) {
        android.graphics.Color.colorToHSV(px[i], hsv)
        hsv[2] = hsv[2] * ceiling
        px[i] = android.graphics.Color.HSVToColor(px[i] ushr 24, hsv)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Mean brightness of [img], 0..1 (Rec. 709 weights). */
internal fun lumaOf(img: ImageBitmap): Float {
    val px = IntArray(img.width * img.height)
    img.readPixels(px)
    var sum = 0.0
    for (p in px) sum += 0.2126 * ((p shr 16) and 255) + 0.7152 * ((p shr 8) and 255) + 0.0722 * (p and 255)
    return (sum / px.size / 255.0).toFloat()
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
