package com.skarif.kairos.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.skarif.kairos.ui.theme.P

/*
 * Frosted glass for surfaces that float over the app: the dock, the mini player, the small title bar.
 *
 * MainActivity records the whole NavHost once into a graphics layer and provides it here. A frosted
 * surface draws a blurred copy of the part of that layer under itself, then a tint on top. The blur
 * only ever covers the surface's own rectangle, so the cost is the size of the bar, not the screen.
 *
 * Real blur needs Android 12 (API 31). Below that, or when no layer is provided, the tint is made
 * nearly opaque so text stays readable.
 */

/** The app behind the floating bars, or null when there is nothing to blur (Android 9-11, no tabs). */
val LocalAppLayer = staticCompositionLocalOf<GraphicsLayer?> { null }

/** Last blur radius set per layer ([BlurEffect.radiusX] is private, so it can't be read back). */
private val lastBlur = java.util.WeakHashMap<GraphicsLayer, Float>()

/** Sets a Clamp blur of [px] on this layer, skipping the write when it is unchanged (a new effect makes the layer re-validate). */
internal fun GraphicsLayer.setBlur(px: Float) {
    if (lastBlur[this] == px && renderEffect != null) return
    renderEffect = BlurEffect(px, px, TileMode.Clamp)
    lastBlur[this] = px
}

/**
 * Draws a blurred copy of [app] into the rectangle ([w] x [h]) at ([dstLeft], [dstTop]) of this
 * scope. ([srcLeft], [srcTop]) is where that rectangle sits in the app layer's own coordinates.
 */
internal fun DrawScope.drawFrost(
    app: GraphicsLayer, frost: GraphicsLayer,
    srcLeft: Float, srcTop: Float, dstLeft: Float, dstTop: Float,
    w: Float, h: Float, blurPx: Float,
) {
    if (w < 1f || h < 1f) return
    // Same radius as last frame: keep the effect (setting a new one makes the layer re-validate).
    frost.setBlur(blurPx)
    frost.record(IntSize(w.toInt(), h.toInt())) { translate(-srcLeft, -srcTop) { drawLayer(app) } }
    translate(dstLeft, dstTop) { drawLayer(frost) }
}

/**
 * The tint laid over (or, with no blur, instead of) the glass. It follows the cover:
 *  - a hint of the cover's main colour, so glass over a purple cover is lavender, not grey;
 *  - more opaque over a dark cover in the light theme (dark text needs a light surface), and over a
 *    bright cover in the dark theme (light text needs a dark one).
 * [real] false = no blur available, so it stays nearly opaque. [lift] shifts the alpha (negative =
 * more see-through; the header panel has no content under it, only the soft backdrop).
 */
@Composable
fun frostTint(base: Color, real: Boolean, lift: Float = 0f): Color {
    if (!real) return base.copy(alpha = maxOf(base.alpha, 0.94f))
    val c = P.colors
    val b = LocalBackdrop.current
    // How bright the page really looks behind the bar: the light theme lays a pale veil over the cover.
    val eff = if (c.dark) b.luma * 0.7f else 0.4f + 0.6f * b.luma
    val a = if (c.dark) 0.58f + 0.22f * eff else 0.80f - 0.20f * eff
    val hue = b.tints?.a
    val solid = base.copy(alpha = 1f)
    // Pure black is the theme with no coloured light at all: leave it black.
    val mixed = if (hue != null && b.enabled && c.bg != Color.Black) lerp(solid, hue, if (c.dark) 0.10f else 0.14f) else solid
    return mixed.copy(alpha = (a + lift).coerceIn(0.3f, 0.96f))
}

/**
 * A frosted surface: clipped to [shape], the app under it blurred, [base] tinted over it (see
 * [frostTint]) and a hairline on the edge. Use for bars that float over scrolling content.
 */
@Composable
fun Modifier.frosted(
    shape: Shape, base: Color, blur: Dp = 22.dp, lift: Float = 0f, edge: Boolean = true,
    /** Read while drawing; false skips the blur (the bar is moving or fading away) and draws a firmer tint. */
    active: () -> Boolean = { true },
): Modifier {
    val app = LocalAppLayer.current
    val real = app != null && Build.VERSION.SDK_INT >= 31
    val tint = frostTint(base, real, lift)
    val frost = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    if (!real) return this.clip(shape).background(tint)
    val dark = P.colors.dark
    val edgeColor = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f)
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (active()) {
                drawFrost(app!!, frost, origin.x, origin.y, 0f, 0f, size.width, size.height, blur.toPx())
                drawRect(tint)
            } else drawRect(tint.copy(alpha = 0.95f))
        }
        .then(if (edge) Modifier.border(0.75.dp, edgeColor, shape) else Modifier)
}
