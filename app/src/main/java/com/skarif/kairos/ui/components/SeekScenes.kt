package com.skarif.kairos.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.skarif.kairos.data.SeekMotion
import com.skarif.kairos.data.SeekStyle
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Animated "scenes": the seek bar's look and the Home card's look come from the same drawing code.
 *
 * A scene is plain Canvas drawing (a gradient, a few dozen dots, three sine-wave fills), no bitmaps,
 * no blur, no shaders, so it costs little. Every colour is derived from one accent colour (the
 * album's), so the same style turns pink for a J-pop cover and amber for a lo-fi one.
 *
 * Time comes from [rememberSceneClock], which ticks at about 30 fps and only while something is
 * playing (seek bar) or on screen and not scrolling (Home card). Drawing reads the clock inside the
 * draw lambda, so a tick redraws the scene and never recomposes anything.
 *
 * "Beat" is a steady pulse at a tempo picked from the song's id. It is not audio analysis (that
 * would mean decoding the file), the same trade-off the old bar waveform made.
 */

private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f

/** Scales every scene's time, so "Normal" is a gentle drift rather than a busy one. */
private const val BASE_SPEED = 0.6f

private fun fract(v: Float): Float = v - floor(v)

/** A colour from hue (degrees, wraps), saturation and value. */
private fun hsv(h: Float, s: Float, v: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hh, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
}

/** Everything a scene draws with, all taken from one accent colour. */
@Immutable
class ScenePalette(
    /** Darkest sky. */
    val deep: Color,
    /** Lighter sky near the horizon. */
    val mid: Color,
    /** The accent itself, vivid. Waves and the played part of the bar. */
    val base: Color,
    /** A bright, slightly desaturated accent for glows. */
    val glow: Color,
    /** Near-white with a touch of the accent: stars, petals, the moon. */
    val hi: Color,
    /** The accent's hue nudged slightly towards warm and towards cool, for aurora bands and the planet. */
    val warm: Color,
    val cool: Color,
    /** The bright line along the played wave: white at night, a deeper tint of the accent by day. */
    val edge: Color = Color.White,
)

/**
 * @param lift 0 for a night scene, 1 for a bright daytime one (the Home card in the morning and afternoon).
 */
fun scenePalette(accent: Color, lift: Float = 0f, blend: Color = Color.Unspecified, day: Boolean = false): ScenePalette {
    val hv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hv)
    val h = hv[0]
    val neutral = hv[1] < 0.12f // a grey cover stays grey instead of turning red
    val s = if (neutral) hv[1] else hv[1].coerceIn(0.5f, 1f)
    val l = lift.coerceIn(0f, 1f)
    // The sky is a quiet tint of the cover's colour, not a saturated dark block: less saturated and a
    // little lighter than before, and (with [blend]) pulled towards the page colour so it sits in the app.
    var deep = hsv(h, if (neutral) s else (s * 0.55f).coerceIn(0.30f, 0.65f), 0.22f + 0.30f * l)
    var mid = hsv(h, if (neutral) s else s * 0.70f, 0.48f + 0.28f * l)
    // In-app night (dark theme, [blend] given): the same quiet look as the day sky, mirrored. A dark
    // tint of the cover's colour that sits on the dark surface, not a saturated block.
    val appNight = !day && blend != Color.Unspecified
    if (appNight) {
        deep = lerp(hsv(h, if (neutral) s else s * 0.45f, 0.24f), blend, 0.45f)
        mid = lerp(hsv(h, if (neutral) s else s * 0.38f, 0.40f), blend, 0.35f)
    }
    if (day) {
        // Light theme: a daytime sky, pale at the horizon and a soft tint above it, with the waves a
        // little deeper so they still read against it. White clouds, petals and stars stay visible high up.
        // A richer daytime sky: keep the cover hue more present at both the top and horizon.
        // Neutral artwork remains neutral rather than gaining an artificial colour cast.
        deep = hsv(h, if (neutral) s else (s * 0.92f).coerceAtMost(1f), 0.68f)
        mid = hsv(h, if (neutral) s else (s * 0.68f).coerceAtMost(1f), 0.93f)
        // Keep the page tint subtle; too much blending washed the daytime sky towards the surface.
        if (blend != Color.Unspecified) { deep = lerp(deep, blend, 0.04f); mid = lerp(mid, blend, 0.08f) }
    }
    return ScenePalette(
        deep = deep,
        mid = mid,
        base = when { day -> hsv(h, (s * 1.12f).coerceAtMost(1f), 0.72f); appNight -> hsv(h, s * 0.85f, 0.88f); else -> hsv(h, s, 0.92f) },
        glow = if (day) hsv(h, (s * 0.95f).coerceAtMost(1f), 0.84f) else hsv(h, s * 0.55f, 1f),
        hi = lerp(hsv(h, s * 0.30f, 1f), Color.White, 0.45f),
        // Close to the accent on the colour wheel and a touch softer: the bands read as shades of the
        // cover's colour, not a rainbow (they used to sit 40 degrees either side at full strength).
        warm = hsv(h - 16f, if (neutral) s else s * 0.85f, 1f),
        cool = hsv(h + 18f, if (neutral) s else s * 0.85f, 1f),
        edge = when { day -> hsv(h, s, 0.46f); appNight -> hsv(h, s * 0.25f, 1f); else -> Color.White },
    )
}

/** Where the dots, petals and stars start, fixed per song so a scene looks the same every time. */
internal class SceneField(seed: Long, n: Int = 32) {
    val x = FloatArray(n)
    val y = FloatArray(n)
    val v = FloatArray(n)
    val s = FloatArray(n)
    val ph = FloatArray(n)
    val size get() = x.size

    init {
        val r = Random(seed * 31L + 7L)
        for (i in 0 until n) {
            x[i] = r.nextFloat(); y[i] = r.nextFloat()
            v[i] = 0.4f + r.nextFloat() * 0.6f; s[i] = 0.4f + r.nextFloat() * 0.6f
            ph[i] = r.nextFloat() * TAU
        }
    }
}

/** A soft pulse at [bpm]: 1 on the beat, easing in and out with no hard edge. */
internal fun beatPulse(t: Float, bpm: Float): Float {
    val c = 0.5f + 0.5f * cos(TAU * fract(t * bpm / 120f))
    return c * c
}

/**
 * Seconds of scene motion. Steps every frame (so it stays smooth) and only while [active]; a [speed] of 0
 * freezes it. [pauseOnScroll] also holds it still while any list scrolls, like the page background does.
 */
@Composable
internal fun rememberSceneClock(active: Boolean, speed: Float, pauseOnScroll: Boolean = false): State<Float> {
    val backdrop = LocalBackdrop.current
    return produceState(3.2f, active, speed, pauseOnScroll) {
        if (!active || speed <= 0f) return@produceState
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            if (pauseOnScroll && now - backdrop.lastScrollNanos < 250_000_000L) { last = now; continue }
            // A slow frame must not make the scene lurch ahead, so one step is capped.
            val dt = (now - last).coerceAtMost(40_000_000L)
            last = now
            value += dt / 1e9f * speed * BASE_SPEED
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Waves
// ---------------------------------------------------------------------------------------------

/**
 * Height of wave [layer] (0 = front, 2 = back) at [x]. Layer 0 is also where the progress bar's
 * crest and knob ride. [wide] (the Minimal Wave+ style) makes the waves bigger and higher.
 */
internal fun waveY(x: Float, h: Float, t: Float, amp: Float, beat: Float, layer: Int, wide: Boolean): Float {
    val yb: Float
    val a: Float
    val f1: Float
    val f2: Float
    val s1: Float
    val s2: Float
    val ph: Float
    when (layer) {
        0 -> { yb = if (wide) 0.64f else 0.76f; a = if (wide) 0.12f else 0.07f; f1 = 1f; f2 = 2.1f; s1 = 1.7f; s2 = 1.1f; ph = 0f }
        1 -> { yb = if (wide) 0.56f else 0.68f; a = if (wide) 0.14f else 0.085f; f1 = 0.8f; f2 = 1.9f; s1 = -1.3f; s2 = 0.9f; ph = 1.9f }
        else -> { yb = if (wide) 0.48f else 0.60f; a = if (wide) 0.16f else 0.10f; f1 = 0.6f; f2 = 1.5f; s1 = 1.0f; s2 = -0.8f; ph = 3.7f }
    }
    val k = TAU / (h * 3.4f)
    val pulse = 1f + 0.35f * beat
    return h * yb + h * a * amp * pulse * (0.68f * sin(x * k * f1 + t * s1 + ph) + 0.32f * sin(x * k * f2 + t * s2 + ph * 1.7f))
}

/** Fills [path] with wave [layer] from the left edge to [toX]; [close] adds the bottom edge so it can be filled. */
private fun DrawScope.buildWave(path: Path, layer: Int, toX: Float, t: Float, amp: Float, beat: Float, wide: Boolean, close: Boolean) {
    path.reset()
    val h = size.height
    val step = 3.dp.toPx()
    path.moveTo(0f, waveY(0f, h, t, amp, beat, layer, wide))
    var x = 0f
    while (x < toX) {
        x = (x + step).coerceAtMost(toX)
        path.lineTo(x, waveY(x, h, t, amp, beat, layer, wide))
    }
    if (close) {
        path.lineTo(toX, h)
        path.lineTo(0f, h)
        path.close()
    }
}

private fun DrawScope.drawWaves(style: SeekStyle, p: ScenePalette, t: Float, amp: Float, beat: Float, path: Path) {
    val h = size.height
    val w = size.width
    val wide = style == SeekStyle.WAVE
    val colors = if (wide) listOf(p.cool.copy(alpha = 0.34f), p.warm.copy(alpha = 0.30f), p.base.copy(alpha = 0.46f))
    else listOf(p.glow.copy(alpha = 0.22f), lerp(p.mid, p.base, 0.5f).copy(alpha = 0.55f), p.base.copy(alpha = 0.42f))
    for (layer in 2 downTo 0) {
        buildWave(path, layer, w, t, amp, beat, wide, close = true)
        val c = colors[2 - layer]
        drawPath(path, Brush.verticalGradient(listOf(c, c.copy(alpha = c.alpha * 0.25f)), startY = h * 0.35f, endY = h))
    }
}

// ---------------------------------------------------------------------------------------------
// The five scenes
// ---------------------------------------------------------------------------------------------

/**
 * Draws style [style] over the whole drawing area. [hero] is the Home card: the artwork and title
 * sit on the left and a fan of covers on the right, so the busiest parts of a scene move out of the way.
 */
internal fun DrawScope.drawSeekScene(
    style: SeekStyle, p: ScenePalette, f: SceneField, t: Float, amp: Float, beat: Float, path: Path,
    compact: Boolean = false, hero: Boolean = false,
) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(p.deep, p.mid), startY = 0f, endY = h))
    val n = if (compact) minOf(8, f.size) else if (hero) 14 else f.size
    when (style) {
        SeekStyle.SAKURA -> drawSakura(p, f, t, n, hero, path, compact)
        SeekStyle.STARRY -> drawStarry(p, f, t, n, path, hero, compact)
        SeekStyle.AURORA -> drawAurora(p, f, t, amp, path, hero, compact)
        SeekStyle.COSMIC -> drawCosmic(p, f, t, n, hero, compact)
        else -> drawSparkle(p, f, t, minOf(n, 12), hero, compact)
    }
    drawWaves(style, p, t, amp, beat, path)
}

/** A four-point twinkle. */
private fun DrawScope.drawTwinkle(c: Offset, r: Float, color: Color) {
    val sw = (r * 0.20f).coerceAtLeast(1f)
    drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = sw, cap = StrokeCap.Round)
    drawCircle(color, r * 0.28f, c)
}

/** A flat, soft cloud. Keep [color] faint: the overlaps add up. */
private fun DrawScope.drawCloud(c: Offset, r: Float, color: Color) {
    drawCircle(color, r, c)
    drawCircle(color, r * 0.75f, Offset(c.x - r, c.y + r * 0.25f))
    drawCircle(color, r * 0.80f, Offset(c.x + r, c.y + r * 0.20f))
    drawRoundRect(color, Offset(c.x - r * 1.6f, c.y + r * 0.2f), Size(r * 3.2f, r * 0.8f), CornerRadius(r * 0.4f))
}

/** A gull seen from far away: two strokes, [flap] from -1 to 1. */
private fun DrawScope.drawGull(c: Offset, r: Float, flap: Float, color: Color) {
    val sw = (r * 0.18f).coerceAtLeast(1.2f)
    val tip = c.y - r * 0.5f * flap
    drawLine(color, Offset(c.x - r, tip), c, strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color, c, Offset(c.x + r, tip), strokeWidth = sw, cap = StrokeCap.Round)
}

/** A pine tree of three stacked tiers, standing on [baseY]. */
private fun DrawScope.drawPine(path: Path, x: Float, baseY: Float, ph: Float, color: Color) {
    val tw = ph * 0.32f
    path.reset()
    path.moveTo(x, baseY - ph); path.lineTo(x + tw * 0.55f, baseY - ph * 0.62f); path.lineTo(x - tw * 0.55f, baseY - ph * 0.62f); path.close()
    path.moveTo(x, baseY - ph * 0.78f); path.lineTo(x + tw * 0.80f, baseY - ph * 0.32f); path.lineTo(x - tw * 0.80f, baseY - ph * 0.32f); path.close()
    path.moveTo(x, baseY - ph * 0.52f); path.lineTo(x + tw, baseY); path.lineTo(x - tw, baseY); path.close()
    drawPath(path, color)
}

/** Minimal Wave+: a sun with a halo, drifting clouds, gulls, twinkles and bubbles rising from the water. */
private fun DrawScope.drawSparkle(p: ScenePalette, f: SceneField, t: Float, n: Int, hero: Boolean, compact: Boolean) {
    val w = size.width
    val h = size.height
    val sc = Offset(if (hero) w * 0.56f else w * 0.84f, h * 0.27f)
    val sr = h * 0.14f
    val breathe = 1f + 0.08f * sin(t * 0.8f)
    drawCircle(Brush.radialGradient(listOf(p.glow.copy(alpha = 0.45f), Color.Transparent), center = sc, radius = sr * 3.6f * breathe), radius = sr * 3.6f * breathe, center = sc)
    drawCircle(lerp(Color.White, p.glow, 0.25f).copy(alpha = 0.95f), radius = sr, center = sc)
    if (!compact && !hero) {
        for (k in 0 until 2) {
            val cx = fract(0.2f + k * 0.55f + t * 0.007f * (1f + k)) * (w + h * 2f) - h
            drawCloud(Offset(cx, h * (0.16f + 0.13f * k)), h * (0.06f + 0.02f * k), Color.White.copy(alpha = 0.14f))
        }
        for (k in 0 until 3) {
            val gx = fract(f.x[k] + t * (0.012f + 0.004f * k)) * (w + h) - h * 0.5f
            val gy = h * (0.20f + 0.09f * k) + sin(t * 0.7f + k * 2f) * h * 0.02f
            drawGull(Offset(gx, gy), h * (0.035f + 0.008f * k), sin(t * 5f + f.ph[k]), p.hi.copy(alpha = 0.75f))
        }
        for (k in 0 until 3) {
            val i = 12 + k
            val a = (0.35f + 0.45f * sin(t * (1.4f + f.v[i]) + f.ph[i])).coerceIn(0f, 1f)
            drawTwinkle(Offset(f.x[i] * w, f.y[i] * h * 0.5f), h * (0.03f + 0.02f * f.s[i]), p.hi.copy(alpha = a))
        }
    }
    for (i in 0 until n) {
        val yy = fract(f.y[i] - t * (0.02f + 0.03f * f.v[i]))
        val xx = (f.x[i] + 0.04f * sin(t * 0.5f + f.ph[i])) * w
        val a = sin(yy * PI_F) * (0.15f + 0.25f * f.s[i])
        drawCircle(p.glow.copy(alpha = a), radius = h * (0.012f + 0.02f * f.s[i]), center = Offset(xx, yy * h))
    }
}

/** A cherry petal pointing down: narrow at the tip, wide and notched at the top. Centred on (0, 0). */
private fun petalPath(path: Path, len: Float, wid: Float) {
    path.reset()
    path.moveTo(0f, len * 0.5f)
    path.cubicTo(-wid * 0.95f, len * 0.15f, -wid * 0.80f, -len * 0.52f, -wid * 0.14f, -len * 0.50f)
    path.lineTo(0f, -len * 0.36f)
    path.lineTo(wid * 0.14f, -len * 0.50f)
    path.cubicTo(wid * 0.80f, -len * 0.52f, wid * 0.95f, len * 0.15f, 0f, len * 0.5f)
    path.close()
}

private fun DrawScope.drawPetal(path: Path, color: Color, cx: Float, cy: Float, len: Float, wid: Float, deg: Float, flip: Float = 1f) {
    petalPath(path, len, wid)
    withTransform({
        translate(cx, cy)
        rotate(deg, Offset.Zero)
        scale(flip, 1f, Offset.Zero)
    }) { drawPath(path, color) }
}

private fun DrawScope.drawSakura(p: ScenePalette, f: SceneField, t: Float, n: Int, hero: Boolean, path: Path, compact: Boolean) {
    val w = size.width
    val h = size.height
    if (!hero) {
        // Moon, with a soft halo.
        val mr = h * 0.17f
        val mc = Offset(w - h * 0.62f, h * 0.30f)
        drawCircle(Brush.radialGradient(listOf(p.hi.copy(alpha = 0.40f), Color.Transparent), center = mc, radius = mr * 3.2f), radius = mr * 3.2f, center = mc)
        drawCircle(lerp(Color.White, p.hi, 0.30f).copy(alpha = 0.95f), radius = mr, center = mc)
    }
    // A torii gate on the horizon.
    run {
        val gw = if (hero) h * 0.34f else h * 0.55f
        val gh = if (hero) h * 0.30f else h * 0.40f
        val gx = if (hero) w * 0.46f else w * 0.40f
        val by = h * 0.74f
        val col = p.deep.copy(alpha = 0.92f)
        val pw = gh * 0.11f
        drawRoundRect(col, Offset(gx - gw / 2f - pw / 2f, by - gh), Size(pw, gh), CornerRadius(pw / 3f))
        drawRoundRect(col, Offset(gx + gw / 2f - pw / 2f, by - gh), Size(pw, gh), CornerRadius(pw / 3f))
        drawRoundRect(col, Offset(gx - gw / 2f - pw * 1.6f, by - gh - pw * 0.2f), Size(gw + pw * 3.2f, pw * 1.1f), CornerRadius(pw * 0.5f))
        drawRect(col, Offset(gx - gw / 2f, by - gh * 0.72f), Size(gw, pw * 0.7f))
    }
    // A blossoming branch: in the corner on the left, on the right for the Home card (its text is on the left).
    val branch: DrawScope.() -> Unit = {
        val bw = h * 0.05f
        val bark = p.deep.copy(alpha = 0.95f)
        drawLine(bark, Offset(-2f, h * 0.62f), Offset(h * 0.55f, h * 0.34f), strokeWidth = bw, cap = StrokeCap.Round)
        drawLine(bark, Offset(h * 0.55f, h * 0.34f), Offset(h * 1.30f, h * 0.12f), strokeWidth = bw * 0.8f, cap = StrokeCap.Round)
        drawLine(bark, Offset(h * 0.55f, h * 0.34f), Offset(h * 0.92f, h * 0.55f), strokeWidth = bw * 0.6f, cap = StrokeCap.Round)
        val blossoms = floatArrayOf(
            0.25f, 0.45f, 0.09f, 0.50f, 0.33f, 0.11f, 0.80f, 0.22f, 0.10f, 1.10f, 0.13f, 0.09f, 0.62f, 0.50f, 0.07f,
            0.95f, 0.42f, 0.07f, 0.12f, 0.58f, 0.07f, 1.35f, 0.20f, 0.07f, 0.40f, 0.20f, 0.06f,
        )
        for (i in 0 until blossoms.size / 3) {
            val bx = blossoms[i * 3] * h
            val by = blossoms[i * 3 + 1] * h + sin(t * 0.9f + i) * h * 0.012f
            val br = blossoms[i * 3 + 2] * h
            val pink = lerp(p.hi, p.glow, 0.30f)
            drawCircle(pink.copy(alpha = 0.18f), radius = br * 1.7f, center = Offset(bx, by))
            for (k in 0 until 5) {
                val a = k * 72f * (PI_F / 180f)
                drawPetal(path, pink.copy(alpha = 0.95f), bx + sin(a) * br * 0.62f, by - cos(a) * br * 0.62f, br * 1.25f, br * 0.62f, k * 72f)
            }
            drawCircle(p.warm.copy(alpha = 0.9f), radius = br * 0.22f, center = Offset(bx, by))
        }
    }
    if (hero) scale(-1f, 1f, pivot = Offset(w / 2f, 0f)) { branch() } else branch()
    // Petals drifting down and across, turning as they fall.
    for (i in 0 until n) {
        val fall = 0.05f + f.v[i] * 0.09f
        val yy = fract(f.y[i] + t * fall)
        val xx = fract(f.x[i] + t * 0.025f * f.s[i]) * (w + 40f) - 20f + sin(t * 1.3f + f.ph[i]) * h * 0.1f
        val fade = sin(yy * PI_F)
        val len = h * (0.075f + 0.060f * f.s[i])
        val flip = 0.35f + 0.65f * abs(cos(t * (0.9f + f.v[i]) + f.ph[i]))
        val col = lerp(p.hi, p.glow, 0.30f + 0.25f * f.s[i]).copy(alpha = 0.40f + 0.55f * fade)
        drawPetal(path, col, xx, yy * h, len, len * 0.55f, t * 38f * f.v[i] + f.ph[i] * 57f, flip)
    }
    // Paper lanterns floating up.
    if (!compact && !hero) for (k in 0 until 3) {
        val i = 20 + k
        val yy = fract(f.y[i] - t * (0.018f + 0.010f * f.v[i]))
        val xx = (f.x[i] * 0.8f + 0.1f) * w + sin(t * 0.6f + f.ph[i]) * h * 0.05f
        val a = sin(yy * PI_F).coerceIn(0f, 1f)
        val c = Offset(xx, yy * h)
        val lw = h * 0.045f
        val lh = h * 0.06f
        drawCircle(Brush.radialGradient(listOf(p.warm.copy(alpha = 0.50f * a), Color.Transparent), center = c, radius = lh * 2.6f), radius = lh * 2.6f, center = c)
        drawRoundRect(p.warm.copy(alpha = 0.95f * a), Offset(c.x - lw / 2f, c.y - lh / 2f), Size(lw, lh), CornerRadius(lw * 0.35f))
        drawLine(Color.White.copy(alpha = 0.7f * a), Offset(c.x - lw * 0.3f, c.y), Offset(c.x + lw * 0.3f, c.y), strokeWidth = lh * 0.12f)
    }
}

private fun DrawScope.drawStarry(p: ScenePalette, f: SceneField, t: Float, n: Int, path: Path, hero: Boolean, compact: Boolean) {
    val w = size.width
    val h = size.height
    // Soft clouds at the edges, drifting.
    val cloud = p.glow.copy(alpha = 0.10f)
    drawCircle(cloud, h * 0.34f, Offset(w * 0.08f + sin(t * 0.20f) * h * 0.10f, h * 0.62f))
    drawCircle(cloud, h * 0.26f, Offset(w * 0.92f + sin(t * 0.17f + 1f) * h * 0.10f, h * 0.55f))
    // Crescent moon.
    val mc = Offset(if (hero) w * 0.55f else w * 0.82f, h * 0.27f)
    val mr = h * 0.13f
    drawCircle(Brush.radialGradient(listOf(p.hi.copy(alpha = 0.30f), Color.Transparent), center = mc, radius = mr * 3f), radius = mr * 3f, center = mc)
    drawCircle(p.hi.copy(alpha = 0.95f), mr, mc)
    drawCircle(lerp(p.deep, p.mid, 0.27f), mr * 0.86f, Offset(mc.x + mr * 0.45f, mc.y - mr * 0.18f))
    // Three layers of stars, the near ones faster: parallax.
    for (i in 0 until n) {
        val layer = i % 3
        val xx = fract(f.x[i] - t * (0.004f + layer * 0.006f)) * w
        val yy = f.y[i] * h * 0.72f
        val tw = 0.55f + 0.45f * sin(t * (1.2f + f.v[i] * 2f) + f.ph[i])
        drawCircle(p.hi.copy(alpha = tw * (0.45f + 0.18f * layer)), radius = h * (0.008f + 0.008f * layer), center = Offset(xx, yy))
    }
    // A constellation with twinkling joints.
    if (!compact && !hero) {
        val cx = floatArrayOf(0.16f, 0.26f, 0.37f, 0.45f, 0.57f)
        val cy = floatArrayOf(0.30f, 0.46f, 0.34f, 0.18f, 0.30f)
        val pa = 0.18f + 0.10f * sin(t * 0.9f)
        for (k in 0 until 4) drawLine(p.hi.copy(alpha = pa), Offset(cx[k] * w, cy[k] * h), Offset(cx[k + 1] * w, cy[k + 1] * h), strokeWidth = 1.dp.toPx())
        for (k in 0 until 5) drawTwinkle(Offset(cx[k] * w, cy[k] * h), h * (0.030f + 0.012f * (k % 3)), p.hi.copy(alpha = (0.55f + 0.40f * sin(t * 1.5f + k * 1.3f)).coerceIn(0f, 1f)))
    }
    // Two shooting stars, now and then.
    for (k in 0 until 2) {
        val u0 = fract(t / (5.5f + k * 3.1f) + k * 0.37f) / 0.18f
        if (u0 < 1f) {
            val head = Offset(w * (0.92f - 0.80f * u0) - k * w * 0.18f, h * (0.04f + 0.60f * u0))
            val tail = Offset(head.x + h * 0.85f, head.y - h * 0.48f)
            val a = sin(u0 * PI_F)
            drawLine(
                Brush.linearGradient(listOf(Color.Transparent, p.hi.copy(alpha = a)), start = tail, end = head),
                start = tail, end = head, strokeWidth = h * 0.022f, cap = StrokeCap.Round,
            )
            drawCircle(Color.White.copy(alpha = a), h * 0.025f, head)
        }
    }
    drawMountains(path, t, h * 0.70f, p.deep.copy(alpha = 0.92f), 0.02f)
    val pineCol = lerp(p.deep, Color.Black, 0.30f)
    val px = if (hero) floatArrayOf(0.38f, 0.43f, 0.47f) else floatArrayOf(0.03f, 0.08f, 0.14f, 0.90f, 0.95f)
    for (k in px.indices) drawPine(path, px[k] * w, h * 0.82f, h * (0.26f + 0.08f * f.s[k]), pineCol)
}

private fun DrawScope.drawMountains(path: Path, t: Float, baseline: Float, color: Color, drift: Float) {
    val w = size.width
    val h = size.height
    val k = TAU / (h * 4.2f)
    val shift = t * h * drift
    path.reset()
    path.moveTo(0f, h)
    var x = 0f
    val step = 8.dp.toPx()
    while (x <= w + step) {
        val y = baseline - h * 0.07f * (0.5f + 0.5f * sin((x + shift) * k * 0.7f + 1f)) - h * 0.05f * sin((x + shift) * k * 1.9f)
        path.lineTo(x, y)
        x += step
    }
    path.lineTo(w, h)
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawAurora(p: ScenePalette, f: SceneField, t: Float, amp: Float, path: Path, hero: Boolean, compact: Boolean) {
    val w = size.width
    val h = size.height
    for (i in 0 until minOf(10, f.size)) {
        val tw = 0.4f + 0.4f * sin(t * (1f + f.v[i]) + f.ph[i])
        drawCircle(p.hi.copy(alpha = tw * 0.6f), h * 0.008f, Offset(f.x[i] * w, f.y[i] * h * 0.5f))
    }
    val k = TAU / (h * 4f)
    val segs = (w / 6.dp.toPx()).toInt().coerceAtLeast(4)
    for (band in 0 until 3) {
        val color = when (band) { 0 -> p.glow; 1 -> p.warm; else -> p.cool }
        fun top(x: Float): Float =
            h * (0.20f + 0.07f * band) + h * 0.13f * amp * sin(x * k * (0.7f + 0.3f * band) + t * (0.5f + 0.2f * band) + band * 2f) + h * 0.05f * sin(x * k * 2f - t * 0.8f)
        fun thick(x: Float): Float = h * (0.30f + 0.10f * sin(x * k * 1.3f - t * 0.6f + band))
        path.reset()
        path.moveTo(0f, top(0f))
        for (i in 1..segs) { val x = w * i / segs; path.lineTo(x, top(x)) }
        for (i in segs downTo 0) { val x = w * i / segs; path.lineTo(x, top(x) + thick(x)) }
        path.close()
        drawPath(path, Brush.verticalGradient(0f to Color.Transparent, 0.45f to color.copy(alpha = 0.55f), 1f to Color.Transparent, startY = 0f, endY = h * 0.9f))
    }
    // Light shafts under the curtains, and snow.
    if (!compact && !hero) for (i in 0 until 9) {
        val x = w * (i + 0.5f) / 9f + sin(t * 0.4f + i) * h * 0.05f
        val a = 0.05f + 0.07f * (0.5f + 0.5f * sin(t * 0.9f + i * 1.7f))
        drawLine(Brush.verticalGradient(listOf(Color.Transparent, p.glow.copy(alpha = a), Color.Transparent), startY = h * 0.1f, endY = h * 0.7f), Offset(x, h * 0.1f), Offset(x, h * 0.7f), strokeWidth = h * 0.025f)
    }
    for (i in 10 until minOf(if (compact) 16 else 26, f.size)) {
        val yy = fract(f.y[i] + t * (0.03f + 0.05f * f.v[i]))
        val xx = fract(f.x[i] + sin(t * 0.5f + f.ph[i]) * 0.02f) * w
        drawCircle(Color.White.copy(alpha = 0.35f + 0.40f * f.s[i]), h * (0.006f + 0.007f * f.s[i]), Offset(xx, yy * h * 0.85f))
    }
    drawMountains(path, t, h * 0.74f, p.deep.copy(alpha = 0.94f), 0.012f)
    val pineCol = lerp(p.deep, Color.Black, 0.30f)
    val px = if (hero) floatArrayOf(0.36f, 0.41f, 0.46f) else floatArrayOf(0.04f, 0.09f, 0.15f, 0.86f, 0.92f, 0.97f)
    for (k in px.indices) drawPine(path, px[k] * w, h * 0.84f, h * (0.24f + 0.08f * f.s[k + 3]), pineCol)
}

private fun DrawScope.drawCosmic(p: ScenePalette, f: SceneField, t: Float, n: Int, hero: Boolean, compact: Boolean) {
    val w = size.width
    val h = size.height
    // Two nebula clouds and a few big twinkles.
    val n1 = Offset(w * 0.22f, h * 0.35f)
    val n2 = Offset(w * 0.62f, h * 0.85f)
    drawCircle(Brush.radialGradient(listOf(p.warm.copy(alpha = 0.22f), Color.Transparent), center = n1, radius = h * 1.15f), radius = h * 1.15f, center = n1)
    drawCircle(Brush.radialGradient(listOf(p.cool.copy(alpha = 0.20f), Color.Transparent), center = n2, radius = h), radius = h, center = n2)
    if (!compact && !hero) for (k in 0 until 3) {
        val i = 16 + k
        val a = (0.45f + 0.45f * sin(t * (1.1f + f.v[i]) + f.ph[i])).coerceIn(0f, 1f)
        drawTwinkle(Offset(f.x[i] * w, f.y[i] * h * 0.7f), h * (0.035f + 0.025f * f.s[i]), p.hi.copy(alpha = a))
    }
    for (i in 0 until minOf(n, 14)) {
        val tw = 0.35f + 0.35f * sin(t * (0.8f + f.v[i]) + f.ph[i])
        drawCircle(p.hi.copy(alpha = tw), h * 0.007f, Offset(f.x[i] * w, f.y[i] * h * 0.8f))
    }
    val pr = if (hero) h * 0.20f else h * 0.26f
    val pc = if (hero) Offset(w - h * 0.34f, h * 0.30f) else Offset(w - h * 0.95f, h * 0.40f)
    val tilt = -18f
    val tiltRad = tilt * PI_F / 180f
    val ct = cos(tiltRad)
    val st = sin(tiltRad)
    // Dust orbiting the planet on tilted ellipses.
    for (i in 0 until n) {
        val ang = f.ph[i] + t * (0.15f + 0.25f * f.v[i])
        val rx = h * (0.5f + 1.4f * f.s[i])
        val ex = cos(ang) * rx
        val ey = sin(ang) * rx * 0.32f
        val pos = Offset(pc.x + ex * ct - ey * st, pc.y + ex * st + ey * ct)
        drawCircle(p.hi.copy(alpha = 0.25f + 0.5f * f.v[i]), h * (0.007f + 0.008f * f.v[i]), pos)
    }
    // A moon circling the planet: behind it for half the orbit, in front for the other half.
    val ma = t * 0.55f
    val mex = cos(ma) * pr * 3.0f
    val mey = sin(ma) * pr * 3.0f * 0.30f
    val mpos = Offset(pc.x + mex * ct - mey * st, pc.y + mex * st + mey * ct)
    val mrad = pr * 0.26f
    fun moon() {
        drawCircle(Brush.radialGradient(listOf(Color.White, lerp(p.hi, p.cool, 0.4f), p.deep), center = Offset(mpos.x - mrad * 0.3f, mpos.y - mrad * 0.3f), radius = mrad * 1.7f), radius = mrad, center = mpos)
    }
    if (sin(ma) < 0f) moon()
    val rr = pr * 2.0f
    val ringTop = Offset(pc.x - rr, pc.y - rr * 0.30f)
    val ringSize = Size(rr * 2f, rr * 0.60f)
    val ringStroke = Stroke(width = h * 0.022f)
    rotate(tilt, pivot = pc) { drawOval(p.warm.copy(alpha = 0.55f), ringTop, ringSize, style = ringStroke) }
    drawCircle(Brush.radialGradient(listOf(lerp(p.warm, Color.White, 0.5f), p.warm, p.deep), center = Offset(pc.x - pr * 0.35f, pc.y - pr * 0.35f), radius = pr * 1.6f), radius = pr, center = pc)
    rotate(tilt, pivot = pc) { drawArc(p.warm.copy(alpha = 0.85f), 0f, 180f, false, ringTop, ringSize, style = ringStroke) }
    if (sin(ma) >= 0f) moon()
    // A comet looping around, with a tail.
    val cr = h * 1.7f
    for (j in 0 until 9) {
        val ang = t * 0.9f - j * 0.09f
        val ex = cos(ang) * cr
        val ey = sin(ang) * cr * 0.30f
        val pos = Offset(pc.x + ex * ct - ey * st, pc.y + ex * st + ey * ct)
        val a = (1f - j / 9f)
        drawCircle(Color.White.copy(alpha = a * 0.9f), h * (0.026f - 0.0025f * j), pos)
    }
}

// ---------------------------------------------------------------------------------------------
// Progress layer and knob
// ---------------------------------------------------------------------------------------------

/** Dims the unplayed part, lights the played part as a glowing wave, and outlines the pill. */
private fun DrawScope.drawProgress(p: ScenePalette, style: SeekStyle, t: Float, amp: Float, beat: Float, playX: Float, path: Path) {
    val h = size.height
    val w = size.width
    val wide = style == SeekStyle.WAVE
    drawRect(p.deep.copy(alpha = 0.42f), topLeft = Offset(playX, 0f), size = Size((w - playX).coerceAtLeast(0f), h))
    if (playX > 1f) {
        buildWave(path, 0, playX, t, amp, beat, wide, close = true)
        drawPath(
            path,
            Brush.verticalGradient(
                listOf(p.glow.copy(alpha = 0.92f), p.base.copy(alpha = 0.60f), p.base.copy(alpha = 0.28f)),
                startY = h * (if (wide) 0.40f else 0.62f), endY = h,
            ),
        )
        buildWave(path, 0, playX, t, amp, beat, wide, close = false)
        drawPath(path, p.glow.copy(alpha = 0.40f), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, p.edge.copy(alpha = 0.95f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    val sw = 1.5.dp.toPx()
    drawRoundRect(
        p.glow.copy(alpha = 0.80f), topLeft = Offset(sw / 2f, sw / 2f), size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius((h - sw) / 2f), style = Stroke(width = sw),
    )
}

private fun DrawScope.drawKnob(p: ScenePalette, style: SeekStyle, t: Float, amp: Float, beat: Float, playX: Float, lens: Float, compact: Boolean) {
    val r = (if (compact) 7.dp else 10.dp).toPx() * (1f + 0.3f * lens)
    val kx = playX.coerceIn(r, (size.width - r).coerceAtLeast(r))
    val crest = waveY(kx, size.height, t, amp, beat, 0, style == SeekStyle.WAVE)
    val ky = crest.coerceIn(r + 2f, (size.height - r - 2f).coerceAtLeast(r + 2f))
    val c = Offset(kx, ky)
    val halo = r * (3.2f + 0.8f * lens + 0.6f * beat)
    drawCircle(Brush.radialGradient(listOf(p.glow.copy(alpha = 0.65f), Color.Transparent), center = c, radius = halo), radius = halo, center = c)
    drawCircle(Color.White, r, c)
    drawCircle(p.base.copy(alpha = 0.40f), r, c, style = Stroke(width = 1.5.dp.toPx()))
}

/** The two canvases of a scene seek bar: the clipped pill with its scene and progress, and the knob on top, unclipped so its glow isn't cut. */
@Composable
internal fun SeekBarBody(
    style: SeekStyle, p: ScenePalette, field: SceneField, path: Path, clock: State<Float>,
    amp: Float, bpm: Float, playK: () -> Float, frac: () -> Float, lens: () -> Float,
    compact: Boolean, modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(50))) {
            val t = clock.value
            val beat = beatPulse(t, bpm) * playK()
            drawSeekScene(style, p, field, t, amp * (1f + 0.35f * lens()), beat, path, compact)
            drawProgress(p, style, t, amp * (1f + 0.35f * lens()), beat, frac() * size.width, path)
        }
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val beat = beatPulse(t, bpm) * playK()
            drawKnob(p, style, t, amp * (1f + 0.35f * lens()), beat, frac() * size.width, lens(), compact)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The seek bar
// ---------------------------------------------------------------------------------------------

/**
 * The player's seek bar as an animated scene. Same gestures as the bar waveform: tap to jump,
 * drag to scrub (a time bubble follows the finger; the song seeks once, on release).
 */
@Composable
fun SceneSeek(
    position: State<Long>,
    durationMs: Long,
    seed: Long,
    onSeek: (Long) -> Unit,
    style: SeekStyle,
    motion: SeekMotion,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    playing: Boolean = false,
    compact: Boolean = false,
) {
    val c = P.colors
    val palette = remember(c.accent, c.bg, c.card, c.dark) { scenePalette(c.accent, 0f, if (c.dark) c.card.copy(alpha = 1f) else c.bg, day = !c.dark) }
    val field = remember(seed) { SceneField(seed) }
    val path = remember { Path() }
    val bpm = remember(seed) { 84f + (abs(seed) % 56L).toFloat() }
    val clock = rememberSceneClock(playing, motion.speed)
    val playK by animateFloatAsState(if (playing) 1f else 0f, tween(500), label = "scenePlay")

    var scrub by remember { mutableStateOf<Float?>(null) }
    val lens by animateFloatAsState(if (scrub != null) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "sceneLens")
    // The position arrives a few times a second; gliding between updates keeps the knob from stepping.
    val glide by animateFloatAsState(
        if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        tween(300, easing = LinearEasing), label = "sceneGlide",
    )
    fun frac(): Float = scrub ?: glide
    val shownSec by remember(durationMs) {
        androidx.compose.runtime.derivedStateOf { (scrub?.let { (it * durationMs).toLong() } ?: position.value) / 1000 }
    }
    var widthPx by remember { mutableIntStateOf(0) }
    var tipW by remember { mutableIntStateOf(0) }
    val tipScale by animateFloatAsState(if (scrub != null) 1f else 0f, spring(dampingRatio = 0.65f, stiffness = 400f), label = "sceneTip")
    fun xToFrac(x: Float) = if (widthPx > 0) (x / widthPx).coerceIn(0f, 1f) else 0f

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 42.dp else height)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(durationMs) { detectTapGestures { if (durationMs > 0) onSeek((xToFrac(it.x) * durationMs).toLong()) } }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrub = xToFrac(it.x) },
                        onDragEnd = { scrub?.let { f -> if (durationMs > 0) onSeek((f * durationMs).toLong()) }; scrub = null },
                        onDragCancel = { scrub = null },
                    ) { change, _ -> change.consume(); scrub = xToFrac(change.position.x) }
                }
                .semantics {
                    contentDescription = "Seek bar"
                    progressBarRangeInfo = ProgressBarRangeInfo(if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f, 0f..1f)
                    setProgress { v -> onSeek((v * durationMs).toLong()); true }
                },
        ) {
            SeekBarBody(
                style, palette, field, path, clock, motion.amp, bpm,
                playK = { playK }, frac = { frac() }, lens = { lens }, compact = compact, modifier = Modifier.fillMaxSize(),
            )
            if (widthPx > 0 && scrub != null) {
                Text(
                    formatTime(shownSec * 1000),
                    style = T.num,
                    color = c.onAccent,
                    modifier = Modifier
                        .onSizeChanged { tipW = it.width }
                        .offset {
                            val tipX = ((frac() * widthPx) - tipW / 2f).roundToInt().coerceIn(0, (widthPx - tipW).coerceAtLeast(0))
                            IntOffset(tipX, (-34).dp.roundToPx())
                        }
                        .graphicsLayer { scaleX = tipScale; scaleY = tipScale; alpha = tipScale.coerceIn(0f, 1f); transformOrigin = TransformOrigin(0.5f, 1f) }
                        .clip(RoundedCornerShape(50))
                        .background(c.accent)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownSec * 1000), style = T.num, color = c.text)
            Text("\u2212" + formatTime((durationMs - shownSec * 1000).coerceAtLeast(0)), style = T.num, color = c.muted)
        }
    }
}

/**
 * A non-interactive seek bar for Settings: the real drawing code at a fixed [progress], animated
 * only when [animate] is on (the chosen style) so a grid of previews stays cheap.
 */
@Composable
fun SeekPreview(
    style: SeekStyle,
    motion: SeekMotion,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    animate: Boolean = true,
    progress: Float = 0.42f,
) {
    val c = P.colors
    if (style == SeekStyle.BARS) {
        BarsPreview(modifier.height(height), progress, animate, motion)
        return
    }
    val palette = remember(c.accent, c.bg, c.card, c.dark) { scenePalette(c.accent, 0f, if (c.dark) c.card.copy(alpha = 1f) else c.bg, day = !c.dark) }
    val field = remember(style) { SceneField(style.ordinal * 977L + 11L) }
    val path = remember { Path() }
    val clock = rememberSceneClock(animate, motion.speed)
    SeekBarBody(
        style, palette, field, path, clock, motion.amp, bpm = 104f,
        playK = { if (animate && motion.speed > 0f) 1f else 0f }, frac = { progress }, lens = { 0f },
        compact = height < 40.dp, modifier = modifier.height(height),
    )
}

/** A small stand-in for the original bar waveform, so it can sit in the style picker too. */
@Composable
private fun BarsPreview(modifier: Modifier, progress: Float, animate: Boolean, motion: SeekMotion) {
    val c = P.colors
    val clock = rememberSceneClock(animate, motion.speed)
    Canvas(modifier.clip(RoundedCornerShape(50)).background(c.card.copy(alpha = 0.6f))) {
        val bars = 24
        val gap = 3.dp.toPx()
        val pad = 12.dp.toPx()
        val bw = (size.width - pad * 2 - gap * (bars - 1)) / bars
        val t = clock.value
        val playX = pad + progress * (size.width - pad * 2)
        for (i in 0 until bars) {
            val hh = (0.35f + 0.65f * abs(sin(i * 1.7f + 0.8f))) * (1f + 0.12f * sin(t * 3f - i * 0.6f)).coerceAtLeast(0.5f)
            val bh = (size.height * 0.7f * hh).coerceIn(bw, size.height * 0.9f)
            val x = pad + i * (bw + gap)
            val col = if (x + bw / 2 < playX) c.accent else c.muted.copy(alpha = 0.3f)
            drawRoundRect(col, Offset(x, (size.height - bh) / 2f), Size(bw, bh), CornerRadius(bw / 2f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Home card background
// ---------------------------------------------------------------------------------------------

/**
 * A scene with no progress bar, as the background of the Home card. [clock] is passed in so the
 * card can move its covers on the same time. [lift] is 0 at night and 1 on a bright afternoon.
 */
@Composable
fun SceneBackdrop(
    style: SeekStyle,
    accent: Color,
    lift: Float,
    clock: State<Float>,
    motion: SeekMotion,
    seed: Long,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = remember(accent, lift) { scenePalette(accent, lift) }
    val field = remember(seed) { SceneField(seed) }
    val path = remember { Path() }
    val bpm = remember(seed) { 84f + (abs(seed) % 56L).toFloat() }
    val playK by animateFloatAsState(if (playing) 1f else 0f, tween(500), label = "heroPlay")
    Canvas(modifier) {
        val t = clock.value
        drawSeekScene(style, palette, field, t, motion.amp * 0.7f, beatPulse(t, bpm) * playK * 0.4f, path, hero = true)
        // A soft sheen from the top left keeps the card from looking flat.
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), start = Offset.Zero, end = Offset(size.width * 0.7f, size.height)))
    }
}
