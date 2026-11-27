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
import com.skarif.kairos.ui.theme.P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The app's background: theme colour, the album cover heavily blurred, a wash, and a dot grid.
 *
 * Every page draws its own copy, so pages are opaque and can slide over each other the iOS way.
 * That is only cheap because nothing here is blurred live: the cover is shrunk to 48 px and
 * box-blurred once per song on a background thread, then drawn stretched (one textured
 * rectangle). The dots are one tiny tile repeated by a shader. No RenderEffect, no Crossfade,
 * the same on every Android version.
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
    /**
     * Seconds of "living" motion: advances only while music plays, so the cover drifts and
     * breathes with the music and freezes where it is on pause. Read only while drawing.
     */
    internal var phase by mutableFloatStateOf(0f)
}

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

/** Keeps [BackdropState] in step with the playing song's artwork. Call once, at the app root. */
@Composable
fun rememberBackdrop(art: Bitmap?, enabled: Boolean, playing: Boolean = false): BackdropState {
    val state = remember { BackdropState() }
    state.enabled = enabled
    // The living background. ~30 updates a second is plenty for motion this slow, and the frame
    // clock stops when the app isn't visible, so this costs nothing in the background.
    LaunchedEffect(playing, enabled, art) {
        if (!playing || !enabled || art == null) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            kotlinx.coroutines.delay(33)
            val now = withFrameNanos { it }
            state.phase += (now - last) / 1e9f
            last = now
        }
    }
    LaunchedEffect(art, enabled) {
        val next = if (art == null || !enabled) null else withContext(Dispatchers.Default) { blurredTiny(art) }
        if (next === state.current) return@LaunchedEffect
        state.previous = state.current
        state.current = next
        state.fade.snapTo(0f)
        state.fade.animateTo(1f, tween(600, easing = LinearEasing))
        state.previous = null
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
        Box(Modifier.matchParentSize().graphicsLayer().backdrop())
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
        // Dark themes: a little more colour from the cover, which the dark wash otherwise greys out.
        val vivid = if (c.dark) androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(1.35f) },
        ) else null
        // Pure black is the theme with the darkest wash; it gets a little less of it than before.
        val pure = c.bg == Color.Black
        onDrawBehind {
            val a = alpha().coerceIn(0f, 1f)
            if (a <= 0f) return@onDrawBehind
            drawRect(c.bg, alpha = a)
            val cur = state.current
            if (state.enabled && (cur != null || state.previous != null)) {
                val f = state.fade.value
                val t = state.phase
                state.previous?.let { drawCover(it, a * (1f - f), t, vivid) }
                cur?.let { drawCover(it, a * f, t, vivid) }
                // The theme's wash, at about two thirds strength: full strength (62% white in the
                // light theme) all but hid pale covers.
                // Dark themes keep more of the wash: weak, it let bright covers fight with the text.
                // Dark: a lighter wash in the middle of the page, where the cover shows between cards,
                // and fuller at the top and bottom, where the header and the dock sit.
                if (c.dark) {
                    val mid = if (pure) 0.78f else 0.62f
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to c.veil.copy(alpha = c.veil.alpha * 0.95f),
                            0.45f to c.veil.copy(alpha = c.veil.alpha * mid),
                            1f to c.veil.copy(alpha = c.veil.alpha * 0.92f),
                        ),
                        alpha = a,
                    )
                } else drawRect(c.veil, alpha = a * 0.68f)
            }
            drawRect(dots, alpha = a)
        }
    }
}

/** The tiny cover stretched to fill, cropped to keep its square shape (like ContentScale.Crop, zoomed 1.2×). */
private fun DrawScope.drawCover(img: ImageBitmap, alpha: Float, t: Float = 0f, filter: androidx.compose.ui.graphics.ColorFilter? = null) {
    if (alpha <= 0f) return
    // Living motion, big enough to see through the blur and the wash: a slow wander (two
    // unrelated periods, so it never visibly loops), a turn of a few degrees, and a breath in size.
    // 1.45x zoom keeps the edges off screen at every angle and offset.
    val tau = 2f * PI.toFloat()
    val breath = 1f + 0.07f * sin(t * tau / 8f)
    val side = maxOf(size.width, size.height) * 1.45f * breath
    val dx = size.width * 0.12f * sin(t * tau / 13f)
    val dy = size.height * 0.06f * sin(t * tau / 11f + 1.3f)
    val angle = 7f * sin(t * tau / 19f + 0.6f)
    val dst = IntSize(side.toInt(), side.toInt())
    val off = IntOffset(((size.width - side) / 2 + dx).toInt(), ((size.height - side) / 2 + dy).toInt())
    // The square is wider than a portrait page, so clip it to the page. Unclipped, the overflow
    // painted over the page underneath while this one slid away (the "blur card" on back swipe).
    clipRect {
        rotate(angle, pivot = center + androidx.compose.ui.geometry.Offset(dx, dy)) {
            drawImage(img, dstOffset = off, dstSize = dst, alpha = alpha, colorFilter = filter, filterQuality = FilterQuality.Low)
        }
    }
}

private fun dotTile(step: Int, radius: Float, color: Color): ImageBitmap {
    val bmp = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()) }
    canvas.drawCircle(step / 2f, step / 2f, radius, paint)
    return bmp.asImageBitmap()
}

/**
 * 48 px copy of [src], lightly box-blurred three times (close to a Gaussian), then stretched to
 * the screen: the same look as the old live 24 dp blur, with the cover's colours still distinct.
 */
internal fun blurredTiny(src: Bitmap, side: Int = 48, radius: Int = 1): ImageBitmap {
    val small = Bitmap.createScaledBitmap(src, side, side, true)
    val px = IntArray(side * side)
    small.getPixels(px, 0, side, 0, 0, side, side)
    val tmp = IntArray(px.size)
    repeat(3) {
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
