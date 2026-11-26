package com.skarif.kairos.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.skarif.kairos.ui.motion.Motion
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import kotlinx.coroutines.launch

/**
 * One entry in an [ActionSheet].
 * - [submenu]: opens a second page inside the sheet (e.g. the playlists to add to).
 * - [keepOpen]: the caller swaps the sheet for something else itself (speed, song info), so the
 *   sheet must not close and dismiss on its own.
 * - [selected]: drawn in the accent colour (a filled heart for a favourite).
 */
@Immutable
class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val selected: Boolean = false,
    val submenu: List<SheetAction>? = null,
    val keepOpen: Boolean = false,
    val onClick: () -> Unit = {},
)

private val Destructive = Color(0xFFE5484D)

/**
 * Every long-press and "more" menu in the app: a sheet from the bottom, like Apple Music's.
 * A header (artwork, title, subtitle), up to four big quick actions, then grouped rows.
 * Swipe it down, tap outside or press back to close. Opens and closes on the sheet spring.
 */
@Composable
fun ActionSheet(
    title: String,
    subtitle: String? = null,
    art: (@Composable () -> Unit)? = null,
    quick: List<SheetAction> = emptyList(),
    groups: List<List<SheetAction>>,
    onDismiss: () -> Unit,
) {
    val c = P.colors
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<SheetAction?>(null) }
    val density = LocalDensity.current
    // Rows sit one step above the sheet: in dark themes the card colour equals the sheet's.
    val rowBg = if (c.dark) c.chip else c.card

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            shown.animateTo(0f, Motion.ios(0.3f, 1f))
            onDismiss()
        }
    }

    fun run(a: SheetAction) {
        when {
            a.submenu != null -> sub = a
            a.keepOpen -> a.onClick()
            else -> { a.onClick(); close() }
        }
    }

    Dialog(
        onDismissRequest = { if (sub != null) sub = null else close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The window's own dim would pop in and out; draw ours, tied to the sheet.
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        LaunchedEffect(Unit) { shown.animateTo(1f, Motion.sheet()) }
        BackHandler(enabled = sub != null) { sub = null }

        val maxH = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = shown.value * (1f - (drag.value / 1200f).coerceIn(0f, 1f)) }
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Close menu") { close() },
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .heightIn(max = maxH)
                    .graphicsLayer { translationY = (1f - shown.value) * (size.height + 40.dp.toPx()) + drag.value }
                    .clip(RoundedCornerShape(30.dp))
                    .background(c.sheet)
                    // Pull down on the header to close; the list below scrolls as usual.
                    .draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        onDragStopped = { v ->
                            if (drag.value > with(density) { 72.dp.toPx() } || v > 1500f) close()
                            else drag.animateTo(0f, Motion.spatial())
                        },
                    )
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.line))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    art?.invoke()
                    Column(Modifier.weight(1f)) {
                        Text(title, style = T.title.copy(fontWeight = FontWeight.Bold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CircleButton(Ic.Close, "Close menu", { close() }, size = 36.dp, container = rowBg, content = c.text)
                }

                AnimatedContent(
                    sub,
                    transitionSpec = { fadeIn(tween(160, delayMillis = 40)) togetherWith fadeOut(tween(100)) },
                    label = "sheetPage",
                ) { page ->
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (page != null) {
                            Group(listOf(SheetAction("Back", Ic.Back) { sub = null }), rowBg, iconFirst = true) { sub = null }
                            if (page.submenu.isNullOrEmpty()) {
                                Text("Nothing here yet.", Modifier.padding(8.dp), style = T.body, color = c.muted)
                            } else {
                                Group(page.submenu, rowBg) { run(it) }
                            }
                        } else {
                            if (quick.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    quick.take(4).forEach { a -> QuickTile(a, rowBg, Modifier.weight(1f)) { run(a) } }
                                }
                            }
                            groups.filter { it.isNotEmpty() }.forEach { g -> Group(g, rowBg) { run(it) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTile(a: SheetAction, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = P.colors
    val tint = when { a.destructive -> Destructive; a.selected -> c.accent; else -> c.text }
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier
            .pressScale(press, 0.94f)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (a.icon != null) Icon(a.icon, null, Modifier.size(24.dp), tint = tint)
        Text(
            a.label, style = T.small.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
            color = c.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A rounded card of rows, iOS-style: label on the left, icon on the right, hairlines between. */
@Composable
private fun Group(items: List<SheetAction>, bg: Color, iconFirst: Boolean = false, onClick: (SheetAction) -> Unit) {
    val c = P.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg)) {
        items.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 18.dp), thickness = 0.5.dp, color = c.line)
            val fg = if (a.destructive) Destructive else c.text
            val iconTint = when { a.destructive -> Destructive; a.selected -> c.accent; else -> c.muted }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    .clickable(role = Role.Button) { onClick(a) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (iconFirst && a.icon != null) Icon(a.icon, null, Modifier.size(20.dp), tint = iconTint)
                Text(a.label, Modifier.weight(1f), style = T.title.copy(fontSize = 16.sp), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when {
                    iconFirst -> Unit
                    a.submenu != null -> {
                        Text("${a.submenu.size}", style = T.small, color = c.muted)
                        Icon(Ic.Chevron, null, Modifier.size(18.dp), tint = c.muted)
                    }
                    a.icon != null -> Icon(a.icon, null, Modifier.size(22.dp), tint = iconTint)
                    else -> Spacer(Modifier.size(22.dp))
                }
            }
        }
    }
}
