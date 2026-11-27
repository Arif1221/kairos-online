package com.skarif.kairos.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import kotlin.math.roundToInt
import kotlin.random.Random

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * The press every tappable element shares. Down the instant you touch, a little dimmer; back up
 * with a soft bounce. A quick tap always shows the whole press: inside a scrolling list Android
 * reports the press late (it first waits to see if you're scrolling), and a fast tap used to be
 * released before the button had moved at all. Now a release finishes the dip first, then bounces.
 * Read only while drawing: nothing recomposes.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.93f): Modifier {
    val scale = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(interaction, pressed) {
        kotlinx.coroutines.coroutineScope {
            interaction.interactions.collect { i ->
                when (i) {
                    is androidx.compose.foundation.interaction.PressInteraction.Press -> launch {
                        scale.animateTo(pressed, androidx.compose.animation.core.tween(90, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    }
                    is androidx.compose.foundation.interaction.PressInteraction.Release,
                    is androidx.compose.foundation.interaction.PressInteraction.Cancel -> launch {
                        // Not down far enough yet (a fast tap): finish the dip so it's felt.
                        if (scale.value > pressed + (1f - pressed) * 0.4f) {
                            scale.animateTo(pressed, androidx.compose.animation.core.tween(70, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                        }
                        // Spring back past 1 a touch and settle: lively, never wobbly.
                        scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 520f))
                    }
                }
            }
        }
    }
    // Pressed things also dim slightly, like an iOS highlight.
    return this.graphicsLayer {
        val v = scale.value
        scaleX = v; scaleY = v
        alpha = (1f - ((1f - v) / (1f - pressed).coerceAtLeast(0.01f)).coerceIn(0f, 1f) * 0.12f).coerceIn(0f, 1f)
    }
}

@Composable
fun CircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    container: Color = P.colors.card,
    content: Color = P.colors.text,
    iconSize: Dp = 20.dp,
) {
    val c = P.colors
    val interaction = remember { MutableInteractionSource() }
    val view = androidx.compose.ui.platform.LocalView.current
    val bg by animateColorAsState(container, tween(250), label = "circleBg")
    val fg by animateColorAsState(content, tween(250), label = "circleFg")
    val needsBorder = container == c.card || container == c.chip
    Box(
        modifier
            .size(size)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(bg)
            .then(if (needsBorder) Modifier.border(1.dp, c.line.copy(alpha = if (c.dark) 0.30f else 0.20f), CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button) { view.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(icon, animationSpec = tween(180), label = "icon") { ic ->
            Icon(ic, contentDescription, tint = fg, modifier = Modifier.size(iconSize))
        }
    }
}

/** A light tick under the finger, so a tap feels answered (it follows the phone's touch-feedback setting). */
fun android.view.View.tick() {
    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
}

@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Boolean = false,
    big: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = P.colors
    val bg = when {
        accent -> c.accent
        selected -> c.ink
        else -> LocalPillContainer.current ?: c.card
    }
    val fg = when {
        accent -> c.onAccent
        selected -> c.inkFg
        else -> c.text
    }
    val interaction = remember { MutableInteractionSource() }
    val view = androidx.compose.ui.platform.LocalView.current
    val bgA by animateColorAsState(bg, tween(250), label = "pillBg")
    val fgA by animateColorAsState(fg, tween(250), label = "pillFg")
    Row(
        modifier
            .height(if (big) 60.dp else 44.dp)
            .pressScale(interaction, 0.96f)
            .clip(RoundedCornerShape(50))
            .background(bgA)
            .then(if (!accent && !selected) Modifier.border(1.dp, c.line.copy(alpha = if (c.dark) 0.30f else 0.20f), RoundedCornerShape(50)) else Modifier)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button) { view.tick(); onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fgA, modifier = Modifier.size(18.dp))
        Text(text, color = fgA, style = if (big) T.title.copy(fontSize = 17.sp) else T.label, maxLines = 1)
    }
}

/** Rounded content card from the design. */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color = P.colors.card, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card))
            .background(color)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * The seek bar as a bar chart of the song (design reference: the finance chart card).
 * The played part fills continuously across the bars, a black playhead marks the position,
 * and while playing the bars near the playhead gently pulse.
 * Bar heights are a stable pattern per song; a real waveform needs decoding the file.
 */
@Composable
fun WaveformSeek(
    position: androidx.compose.runtime.State<Long>,
    durationMs: Long,
    seed: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    barsHeight: Dp = 56.dp,
    bars: Int = 36,
    playing: Boolean = false,
) {
    val c = P.colors
    val heights = remember(seed, bars) { val r = Random(seed); List(bars) { 0.25f + r.nextFloat() * 0.75f } }
    // New song: the bars grow up from the baseline one after another, left to right.
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(seed) { appear.snapTo(0f); appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    // Frame clock for the bars' motion; only ticks while playing.
    val clock = androidx.compose.runtime.produceState(0f, playing) {
        if (!playing) return@produceState
        val t0 = androidx.compose.runtime.withFrameMillis { it } - (value * 1000).toLong()
        while (true) androidx.compose.runtime.withFrameMillis { t -> value = (t - t0) / 1000f }
    }
    val motion by animateFloatAsState(if (playing) 1f else 0f, tween(600), label = "motion")
    // While scrubbing, the bar shows where your finger is and seeks once, when you let go.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val lens by animateFloatAsState(if (scrub != null) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "lens")
    fun frac(): Float = scrub ?: if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownSec by remember(durationMs) {
        androidx.compose.runtime.derivedStateOf { (scrub?.let { (it * durationMs).toLong() } ?: position.value) / 1000 }
    }
    var widthPx by remember { mutableIntStateOf(0) }
    var tipW by remember { mutableIntStateOf(0) }
    val tipScale by animateFloatAsState(if (scrub != null) 1f else 0f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "tip")
    fun xToFrac(x: Float) = if (widthPx > 0) (x / widthPx).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card))
            .background(c.card.copy(alpha = if (c.dark) 0.72f else 0.88f))
            .border(1.dp, c.line.copy(alpha = if (c.dark) 0.35f else 0.25f), RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barsHeight)
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
            Canvas(Modifier.fillMaxSize()) {
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (bars - 1)) / bars
                val playX = frac() * size.width
                val t = clock.value
                val radius = CornerRadius(minOf(w / 2, 6.dp.toPx()))
                val unplayedColor = c.muted.copy(alpha = if (c.dark) 0.28f else 0.24f)
                val playedColor = c.accent
                val a = appear.value
                val rects = heights.mapIndexed { i, h ->
                    val left = i * (w + gap)
                    val cx = left + w / 2
                    val grow = ((a * 1.6f) - i.toFloat() / bars * 0.6f).coerceIn(0f, 1f)
                    val near = (1f - kotlin.math.abs(cx - playX) / (w * 4.5f)).coerceIn(0f, 1f)
                    val played = cx < playX
                    val wave = if (played) 0.06f * kotlin.math.sin(t * 4.2f - i * 0.55f) else 0.02f * kotlin.math.sin(t * 1.4f + i * 0.4f)
                    val beat = 0.5f + 0.5f * kotlin.math.sin(t * 9.1f + i * 1.3f) * kotlin.math.sin(t * 2.7f + i * 0.8f)
                    val lensK = lens * (1f - (kotlin.math.abs(cx - playX) / (w * 3f)).coerceIn(0f, 1f))
                    val scale = 1f + motion * (wave + near * near * (beat * 0.28f - 0.06f)) + lensK * 0.35f
                    val bh = (size.height * h * scale * grow).coerceIn(w, size.height)
                    // Center-aligned bars look cleaner and more modern than bottom-aligned bars.
                    val top = (size.height - bh) / 2f
                    RoundRect(left, top, left + w, top + bh, radius)
                }
                rects.forEach { r -> drawPath(Path().apply { addRoundRect(r) }, unplayedColor) }
                clipRect(right = playX) {
                    rects.forEach { r -> drawPath(Path().apply { addRoundRect(r) }, playedColor) }
                }
                val ph = 3.dp.toPx()
                val halo = (5.dp.toPx() + motion * 3.dp.toPx() * (0.5f + 0.5f * kotlin.math.sin(t * 3f))) * (1f + lens)
                drawRoundRect(
                    c.accent.copy(alpha = 0.26f), Offset((playX - halo).coerceIn(0f, size.width - 2 * halo), 0f),
                    Size(2 * halo, size.height), CornerRadius(halo),
                )
                drawRoundRect(
                    c.text, Offset((playX - ph / 2).coerceIn(0f, size.width - ph), 0f),
                    Size(ph, size.height), CornerRadius(ph / 2),
                )
            }
            if (widthPx > 0 && scrub != null) {
                Text(
                    formatTime(shownSec * 1000),
                    style = T.num,
                    color = c.onAccent,
                    modifier = Modifier
                        .onSizeChanged { tipW = it.width }
                        .offset {
                            val tipX = ((frac() * widthPx) - tipW / 2f).roundToInt().coerceIn(0, (widthPx - tipW).coerceAtLeast(0))
                            IntOffset(tipX, (-28).dp.roundToPx())
                        }
                        .graphicsLayer { scaleX = tipScale; scaleY = tipScale; alpha = tipScale.coerceIn(0f, 1f); transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }
                        .clip(RoundedCornerShape(50))
                        .background(c.accent)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownSec * 1000), style = T.num, color = c.text)
            Text("−" + formatTime((durationMs - shownSec * 1000).coerceAtLeast(0)), style = T.num, color = c.muted)
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    val c = P.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.title, color = c.text)
            if (subtitle != null) Text(subtitle, style = T.small, color = c.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = c.line, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = P.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item))
            .background(c.line.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .pressScale(press, 0.95f)
                    .clip(RoundedCornerShape(21.dp))
                    // The chosen option wears the album's colour, like the rest of the controls.
                    .background(animateColorAsState(if (on) c.accent else Color.Transparent, tween(250), label = "seg").value)
                    .selectable(selected = on, interactionSource = press, indication = ripple(), role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = T.label.copy(fontSize = 14.sp), color = animateColorAsState(if (on) c.onAccent else c.text, tween(250), label = "segT").value, maxLines = 1)
            }
        }
    }
}

@Composable
fun RadioCard(title: String, description: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val c = P.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item))
            .background(c.card)
            .border(2.dp, if (selected) c.accent else Color.Transparent, RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(22.dp).border(2.dp, if (selected) c.accent else c.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = T.title, color = if (enabled) c.text else c.muted)
            Text(description, style = T.small, color = c.muted)
        }
    }
}

/** Back button, centred title, optional action on the right (52 dp each side). */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircleButton(Ic.Back, "Back", onBack)
        Text(title, Modifier.weight(1f), style = T.title, color = P.colors.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) action() else Box(Modifier.size(52.dp))
    }
}

/** Row of filled and dashed pills, the design's progress meter. */
@Composable
fun PillMeter(filled: Int, total: Int, color: Color, modifier: Modifier = Modifier, height: Dp = 30.dp) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            val shape = RoundedCornerShape(11.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .then(
                        if (i < filled) Modifier.clip(shape).background(color)
                        else Modifier.border(1.5.dp, color.copy(alpha = 0.45f), shape),
                    ),
            )
        }
    }
}

/** Bars with hatched fill; the selected bar is solid black with a tooltip (finance reference). */
@Composable
fun HatchedBars(
    values: List<Float>,
    labels: List<String>,
    selected: Int,
    tooltip: String?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 150.dp,
) {
    val c = P.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(chartHeight), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).fillMaxSize().selectable(selected = on, role = Role.Tab) { onSelect(i) }
                        .semantics { contentDescription = labels.getOrElse(i) { "" } },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Canvas(Modifier.fillMaxWidth().fillMaxSize()) {
                        val h = (size.height * v.coerceIn(0.04f, 1f))
                        val top = size.height - h
                        val r = CornerRadius(10.dp.toPx())
                        if (on) {
                            drawRoundRect(Color(0xFF151515), Offset(0f, top), Size(size.width, h), r)
                        } else {
                            val path = Path().apply { addRoundRect(RoundRect(0f, top, size.width, size.height, r)) }
                            clipPath(path) {
                                var x = -h
                                while (x < size.width) {
                                    drawLine(c.onAccent.copy(alpha = 0.42f), Offset(x, size.height), Offset(x + h, top), strokeWidth = 2.dp.toPx())
                                    x += 6.dp.toPx()
                                }
                            }
                        }
                    }
                    if (on && tooltip != null) {
                        Text(
                            tooltip, style = T.small.copy(fontWeight = FontWeight.Bold), color = Color(0xFF151515),
                            modifier = Modifier.align(Alignment.TopCenter).clip(RoundedCornerShape(50)).background(Color.White)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, Modifier.weight(1f), style = T.small.copy(fontWeight = if (i == selected) FontWeight.ExtraBold else FontWeight.Medium),
                    color = c.onAccent, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}

/**
 * A real bottom sheet: drag it down to close, tap outside to close, and the page behind stays
 * put. [content] gets a `close` that slides the sheet away before [onDismiss] runs.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KairosSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.(close: () -> Unit) -> Unit) {
    val c = P.colors
    val state = androidx.compose.material3.rememberBottomSheetState(
        androidx.compose.material3.SheetValue.Hidden,
        setOf(androidx.compose.material3.SheetValue.Hidden, androidx.compose.material3.SheetValue.Expanded),
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) onDismiss() }
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.sheet,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        scrimColor = Color(0x66000000),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(c.muted.copy(alpha = 0.5f)))
        },
    ) { content(close) }
}

/**
 * iOS large-title behaviour: once the page's big title has scrolled away, a small bar with the
 * title fades and slides in at the top. Tapping it scrolls back to the top.
 */
@Composable
fun BoxScope.CompactTitleBar(title: String, visible: Boolean, onTop: () -> Unit) {
    val c = P.colors
    val panel = c.panel
    androidx.compose.animation.AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) +
            androidx.compose.animation.slideInVertically(com.skarif.kairos.ui.motion.Motion.snappy()) { -it / 2 },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
            androidx.compose.animation.slideOutVertically(com.skarif.kairos.ui.motion.Motion.snappy()) { -it / 2 },
    ) {
        Column(
            Modifier.fillMaxWidth().background(panel)
                .clickable(onClickLabel = "Scroll to top", role = Role.Button, onClick = onTop)
                .statusBarsPadding(),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(title, style = T.title, color = c.text, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(0.8.dp).background(c.line.copy(alpha = 0.25f)))
        }
    }
}

/** Grows a large title a little while the page is pulled down past the top (iOS). Draw phase only. */
/**
 * A tab's header panel background that stretches up with the iOS pull: pulled down at the top,
 * the panel grows to fill the gap above it instead of the list sliding away from the top edge.
 * One shape drawn per frame, read in the draw phase only (no recomposition). Put it before any clip.
 */
/** The tab header panel's shape: square top under the status bar, round bottom. */
val HeaderShape = RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)

/**
 * Everything a tab header panel needs, in one place: full width, the stretching background,
 * the clip, the status bar and the padding. Used by [TabHeader] and the Home and Library headers.
 */
@Composable
fun Modifier.headerPanel(pull: () -> Float = { 0f }): Modifier = this
    .fillMaxWidth()
    .stretchPanel(P.colors.panel, pull = pull)
    .clip(HeaderShape)
    .statusBarsPadding()
    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp)

fun Modifier.stretchPanel(
    color: Color,
    shape: androidx.compose.ui.graphics.Shape = HeaderShape,
    pull: () -> Float,
): Modifier = drawBehind {
    val p = pull().coerceAtLeast(0f)
    val outline = shape.createOutline(androidx.compose.ui.geometry.Size(size.width, size.height + p), layoutDirection, this)
    translate(top = -p) { drawOutline(outline, color) }
}

fun Modifier.stretchTitle(pull: () -> Float): Modifier = graphicsLayer {
    val s = 1f + (pull().coerceAtLeast(0f) / 1400f).coerceAtMost(0.1f)
    scaleX = s; scaleY = s
    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
}

/**
 * Every dialog in the app. Same slots as Material's AlertDialog, but it pops in like an iOS
 * alert: from slightly larger and transparent to its size, on a spring with a touch of life.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KairosDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = P.colors.sheet,
) {
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        val appear = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, com.skarif.kairos.ui.motion.Motion.ios(0.38f, 0.78f)) }
        Column(
            Modifier
                .graphicsLayer {
                    val v = appear.value
                    val s = 1.1f - 0.1f * v
                    scaleX = s; scaleY = s
                    alpha = v.coerceIn(0f, 1f)
                }
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(30.dp)).background(containerColor)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            title?.invoke()
            if (text != null) Box(Modifier.weight(1f, fill = false)) { text() }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/** For call sites inside `.then(...)`: the same press as [pressScale]. */
object PressScaleHolder {
    @Composable
    fun modifier(interaction: MutableInteractionSource, pressed: Float = 0.93f): Modifier = Modifier.pressScale(interaction, pressed)
}

/**
 * The header every main tab uses (Home, Library, Search, Statistics): a frosted panel under the
 * status bar with round buttons left and right, the page name in the middle, and the page's big
 * title and controls below. One component, so the tabs look like one app.
 */
@Composable
fun TabHeader(
    title: String,
    left: @Composable (chip: Color) -> Unit,
    right: @Composable (chip: Color) -> Unit,
    modifier: Modifier = Modifier,
    /** The list's [com.skarif.kairos.ui.motion.RubberBand.pull], so the panel stretches with it. */
    pull: () -> Float = { 0f },
    content: @Composable ColumnScope.(chip: Color) -> Unit,
) {
    val c = P.colors
    val chip = c.chip
    Column(
        modifier.headerPanel(pull),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            left(chip)
            Text(title, Modifier.weight(1f), color = c.text, style = T.title, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            right(chip)
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalPillContainer provides chip) { content(chip) }
    }
}

/** Unselected pills inside a header panel use the panel's chip colour, so they don't vanish into it. */
val LocalPillContainer = androidx.compose.runtime.staticCompositionLocalOf<Color?> { null }
