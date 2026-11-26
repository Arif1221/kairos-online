package com.skarif.kairos.ui.screens

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Icon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.skarif.kairos.data.OutputMode
import com.skarif.kairos.data.SongSort
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.pressScale
import com.skarif.kairos.ui.components.Card
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.HatchedBars
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.KairosSheet
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.PillMeter
import com.skarif.kairos.ui.components.RadioCard
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.ScreenHeader
import com.skarif.kairos.ui.components.Segmented
import com.skarif.kairos.ui.components.ToggleRow
import com.skarif.kairos.ui.components.formatTime
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import com.skarif.kairos.ui.theme.mixedHeadline
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Spacer
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun QueuePane(vm: MainViewModel, onBack: () -> Unit, onSaveAsPlaylist: () -> Unit, onOpenAlbum: (Long) -> Unit) {
    val c = P.colors
    val now by vm.now.collectAsStateWithLifecycle()
    val cur by vm.currentSong.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Collected, not read once: rows redraw with the real title and cover as soon as the library loads.
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<Pair<Int, com.skarif.kairos.data.db.SongEntity>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val left = (now.queue.size - now.queuePosition - 1).coerceAtLeast(0)
    val list = rememberLazyListState()
    // Open on the current song, and follow it as songs change (unless you scrolled away).
    LaunchedEffect(Unit) { list.scrollToItem((now.queuePosition - 1).coerceAtLeast(0)) }
    val currentVisible by remember {
        androidx.compose.runtime.derivedStateOf { list.layoutInfo.visibleItemsInfo.any { it.index == vm.now.value.queuePosition } }
    }
    LaunchedEffect(now.queuePosition) {
        if (!list.isScrollInProgress) list.animateScrollToItem((now.queuePosition - 1).coerceAtLeast(0))
    }

    menuFor?.let { (i, song) -> SongActionsDialog(vm, song, onOpenAlbum, queueIndex = i) { menuFor = null } }
    if (confirmClear) {
        com.skarif.kairos.ui.components.KairosDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.sheet,
            title = { Text("Clear up next?", style = T.h2, color = c.text) },
            text = { Text("Removes the $left songs after the current one. The current song keeps playing.", style = T.body, color = c.muted) },
            confirmButton = { androidx.compose.material3.TextButton({ vm.clearUpcoming(); confirmClear = false }) { Text("Clear", color = c.text, style = T.title) } },
            dismissButton = { androidx.compose.material3.TextButton({ confirmClear = false }) { Text("Cancel", color = c.muted) } },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Top bar: back, title, and the less-used actions in a menu.
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(Ic.Back, "Back to now playing", onBack, size = 48.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Queue", style = T.h2, color = c.text)
                    Text("${now.shownPosition + 1} of ${now.shownSize}, ${(now.shownSize - now.shownPosition - 1).coerceAtLeast(0)} up next", style = T.small, color = c.muted)
                }
                Box {
                    CircleButton(Ic.Menu, "Queue options", { overflow = true }, size = 48.dp)
                    androidx.compose.material3.DropdownMenu(
                        expanded = overflow, onDismissRequest = { overflow = false },
                        containerColor = c.sheet, shape = RoundedCornerShape(20.dp),
                    ) {
                        @Composable
                        fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, action: () -> Unit) =
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(label, style = T.title, color = if (enabled) c.text else c.muted) },
                                leadingIcon = { androidx.compose.material3.Icon(icon, null, tint = if (enabled) c.text else c.muted, modifier = Modifier.size(20.dp)) },
                                enabled = enabled, onClick = { overflow = false; action() },
                            )
                        item("Save as playlist", Ic.PlaylistAdd) { onSaveAsPlaylist() }
                        item("Clear up next", Ic.Trash, enabled = left > 0) { confirmClear = true }
                        item("Clear played", Ic.Close, enabled = now.queuePosition > 0) { vm.clearPlayed() }
                    }
                }
            }

            // Pinned: the current song stays on screen however far you scroll.
            Column(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(c.accent)
                    .clickable(role = Role.Button, onClickLabel = "Show in queue") {
                        scope.launch { list.animateScrollToItem((now.queuePosition - 1).coerceAtLeast(0)) }
                    }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The player layer draws the cover here, moving it in from the big hero.
                    Spacer(Modifier.size(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur?.title ?: "Nothing playing", style = T.title.copy(fontSize = 18.sp), color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cur?.artist ?: "", style = T.small, color = c.onAccent.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CircleButton(
                        if (now.isPlaying) Ic.Pause else Ic.Play, if (now.isPlaying) "Pause" else "Play", vm::toggle,
                        size = 48.dp, container = c.onAccent, content = c.accent,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveProgressLine(vm, now.durationMs, c.onAccent, Modifier.weight(1f))
                    val on = c.onAccent
                    CircleButton(
                        Ic.Shuffle, if (now.shuffle) "Shuffle, on" else "Shuffle, off", vm::toggleShuffle, size = 40.dp, iconSize = 18.dp,
                        container = if (now.shuffle) on else on.copy(alpha = 0.14f), content = if (now.shuffle) c.accent else on,
                    )
                    CircleButton(
                        if (now.repeatMode == Player.REPEAT_MODE_ONE) Ic.RepeatOne else Ic.Repeat,
                        when (now.repeatMode) { Player.REPEAT_MODE_ALL -> "Repeat all, on"; Player.REPEAT_MODE_ONE -> "Repeat one, on"; else -> "Repeat, off" },
                        vm::cycleRepeat, size = 40.dp, iconSize = 18.dp,
                        container = if (now.repeatMode != Player.REPEAT_MODE_OFF) on else on.copy(alpha = 0.14f),
                        content = if (now.repeatMode != Player.REPEAT_MODE_OFF) c.accent else on,
                    )
                }
            }
            Text(
                if (now.shuffle) "Swipe left to remove. Hold a song for more."
                else "Drag ≡ to reorder. Swipe right to play next, left to remove.",
                Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = T.small, color = c.muted,
            )

            // Drag to reorder (shuffle off). Plain values, read while drawing: no recomposition per move.
            val drag = remember { QueueDrag() }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val gapPx = with(density) { 6.dp.toPx() }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(), state = list,
                    flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(now.queue, key = { _, e -> e.index }, contentType = { _, _ -> "queue" }) { pos, e ->
                        val song = songMap[e.songId]
                        val playing = pos == now.queuePosition
                        val played = pos < now.queuePosition
                        Box(
                            Modifier
                                .then(if (drag.key == e.index) Modifier else Modifier.animateItem())
                                .onSizeChanged { drag.rowPx = it.height.toFloat() }
                                .zIndex(if (drag.key == e.index) 1f else 0f)
                                .graphicsLayer {
                                    val from = drag.fromPos
                                    if (drag.key == null || from < 0) return@graphicsLayer
                                    if (drag.key == e.index) {
                                        translationY = drag.offset
                                        scaleX = 1.03f; scaleY = 1.03f
                                        shadowElevation = 12.dp.toPx(); shape = RoundedCornerShape(22.dp)
                                    } else {
                                        // Rows between the start and the target slide out of the way.
                                        val to = drag.target(gapPx, now.queue.size)
                                        val step = drag.rowPx + gapPx
                                        translationY = when {
                                            pos in (from + 1)..to -> -step
                                            pos in to until from -> step
                                            else -> 0f
                                        }
                                    }
                                },
                        ) {
                            SwipeActions(
                                "Play next", Ic.Next, if (playing || now.shuffle) null else ({ vm.moveToNext(e.index) }),
                                "Remove", Ic.Close, if (playing) null else ({ vm.removeQueueItem(e.index) }),
                            ) {
                                val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .pressScale(press, 0.97f)
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(if (playing) c.tint else c.card.copy(alpha = if (played) 0.4f else 0.8f))
                                        .combinedClickable(
                                            interactionSource = press, indication = androidx.compose.material3.ripple(),
                                            role = Role.Button, onClickLabel = "Play", onLongClickLabel = "More options",
                                            onLongClick = { if (song != null) menuFor = e.index to song },
                                        ) { vm.playQueueItem(e.index) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        if (playing) "▶" else "${pos + 1}",
                                        Modifier.width(34.dp), style = T.small.copy(fontWeight = FontWeight.Bold),
                                        color = if (playing) c.accent else c.muted, maxLines = 1,
                                    )
                                    SongArt(vm, song, 44.dp, corner = 12.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            song?.title ?: "Not on this phone any more", style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = c.text.copy(alpha = if (played) 0.5f else 1f),
                                        )
                                        Text(song?.artist ?: "", style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(formatTime(song?.durationMs ?: 0), style = T.num, color = c.muted)
                                    if (!now.shuffle) Icon(
                                        Ic.Menu, "Drag to reorder", tint = c.muted,
                                        modifier = Modifier.size(28.dp).pointerInput(e.index, pos) {
                                            detectVerticalDragGestures(
                                                onDragStart = { drag.key = e.index; drag.fromPos = pos; drag.offset = 0f },
                                                onDragEnd = {
                                                    val to = drag.target(gapPx, now.queue.size)
                                                    if (to != pos) now.queue.getOrNull(to)?.let { vm.moveQueueItem(e.index, it.index) }
                                                    drag.reset()
                                                },
                                                onDragCancel = { drag.reset() },
                                            ) { change, dy -> change.consume(); drag.offset += dy }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // Back to the current song after scrolling away.
                androidx.compose.animation.AnimatedVisibility(
                    !currentVisible && now.queue.isNotEmpty(),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
                ) {
                    Pill("Go to now playing", {
                        scope.launch { list.animateScrollToItem((now.queuePosition - 1).coerceAtLeast(0)) }
                    }, accent = true, icon = Ic.Music)
                }
            }
        }
    }
}

/** Thin live progress line for compact players. */
@Composable
private fun LiveProgressLine(vm: MainViewModel, durationMs: Long, color: Color, modifier: Modifier = Modifier) {
    val pos = vm.position.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val p = if (durationMs > 0) (pos.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            drawRoundRect(color.copy(alpha = 0.25f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            drawRoundRect(color, size = size.copy(width = size.width * p), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
        LiveTime(vm, durationMs, color)
    }
}

private val BANDS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
private val PRESETS = mapOf(
    "Flat" to List(10) { 0f },
    "Bass" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Vocal" to listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
    "Acoustic" to listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f),
    "Rock" to listOf(5f, 4f, 2f, -1f, -2f, 0f, 2f, 4f, 5f, 5f),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheet(vm: MainViewModel, onBack: () -> Unit) {
    val c = P.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    var band by remember { mutableIntStateOf(6) }
    val gains = s.eqGains
    val preset = PRESETS.entries.firstOrNull { it.value == gains }?.key ?: "Custom"
    fun setGain(i: Int, d: Float) = vm.updateSettings { st ->
        st.copy(eqEnabled = true, eqGains = st.eqGains.toMutableList().also { it[i] = (it[i] + d).coerceIn(-12f, 12f) })
    }

    KairosSheet(onBack) { close ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling()).navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mixedHeadline("Shape " to false, "your" to true, " sound" to false), Modifier.weight(1f), style = T.h1.copy(fontSize = 34.sp), color = c.text)
                CircleButton(Ic.Close, "Close", close)
            }
            if (s.outputMode == OutputMode.BIT_PERFECT) {
                Text("Bit-perfect output is on, so the equalizer is bypassed. Switch to Hi-res in Settings to use it.", style = T.body, color = c.text)
            }
            Card { ToggleRow("Equalizer", s.eqEnabled, { v -> vm.updateSettings { it.copy(eqEnabled = v) } }) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (PRESETS.keys + "Custom").forEach { name ->
                    Pill(name, { PRESETS[name]?.let { g -> vm.updateSettings { it.copy(eqEnabled = true, eqGains = g) } } }, selected = name == preset)
                }
            }
            Card(color = c.accent) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${BANDS[band]} Hz band", Modifier.weight(1f), style = T.title, color = c.onAccent)
                    CircleButton(Ic.Minus, "Lower ${BANDS[band]} hertz", { setGain(band, -1f) }, size = 44.dp, container = Color.White, content = Color(0xFF151515))
                    CircleButton(Ic.Plus, "Raise ${BANDS[band]} hertz", { setGain(band, 1f) }, size = 44.dp, container = Color.White, content = Color(0xFF151515))
                }
                HatchedBars(
                    values = gains.map { (it + 12f) / 24f },
                    labels = BANDS,
                    selected = band,
                    tooltip = gains[band].let { g -> (if (g > 0) "+" else "") + g.toInt() + " dB" },
                    onSelect = { band = it },
                    chartHeight = 170.dp,
                )
            }
            Card {
                EffectRow("Bass boost", s.bassBoost, { v -> vm.updateSettings { it.copy(bassBoost = v) } })
                EffectRow("Wider sound, for headphones", s.virtualizer, { v -> vm.updateSettings { it.copy(virtualizer = v) } })
            }
        }
    }
}

@Composable
private fun EffectRow(title: String, value: Int, onChange: (Int) -> Unit) {
    val c = P.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = T.title, color = c.text)
                Text("$value of 10", style = T.small, color = c.muted)
            }
            CircleButton(Ic.Minus, "Less", { onChange((value - 1).coerceAtLeast(0)) }, size = 44.dp, container = c.tint)
            CircleButton(Ic.Plus, "More", { onChange((value + 1).coerceAtMost(10)) }, size = 44.dp, container = c.tint)
        }
        PillMeter(value, 10, c.accent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(vm: MainViewModel, onBack: () -> Unit) {
    val c = P.colors
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val cur by vm.currentSong.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var minutes by remember { mutableIntStateOf(30) }
    var endOfSong by remember { mutableStateOf(false) }
    var fade by remember { mutableStateOf(true) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleep.active) { while (sleep.active) { nowMs = System.currentTimeMillis(); delay(1000) } }
    val clock = DateFormat.getTimeInstance(DateFormat.SHORT)

    KairosSheet(onBack) { close ->
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mixedHeadline("Sleep " to false, "timer" to true), Modifier.weight(1f), style = T.h1.copy(fontSize = 34.sp), color = c.text)
                CircleButton(Ic.Close, "Close", close)
            }
            if (sleep.active) {
                Card(color = c.accent) {
                    val text = if (sleep.endOfSong) "End of song" else formatTime((sleep.endsAt - nowMs).coerceAtLeast(0))
                    Text(text, style = T.big, color = c.onAccent)
                    Text(
                        if (sleep.endOfSong) "Stops when ${cur?.title ?: "this song"} ends" else "Music stops at ${clock.format(Date(sleep.endsAt))}",
                        style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.onAccent,
                    )
                }
                Pill("Cancel timer", { vm.cancelSleep(); close() }, selected = true, big = true, modifier = Modifier.fillMaxWidth())
            } else {
                Card(color = c.accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircleButton(Ic.Minus, "Five minutes less", { minutes = (minutes - 5).coerceAtLeast(5); endOfSong = false }, size = 44.dp, container = Color.White, content = Color(0xFF151515))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (endOfSong) "End of song" else "$minutes min", style = T.big, color = c.onAccent)
                            Text(
                                if (endOfSong) "Stops when ${cur?.title ?: "this song"} ends"
                                else "Music stops at ${clock.format(Date(System.currentTimeMillis() + minutes * 60_000L))}",
                                style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.onAccent,
                            )
                        }
                        CircleButton(Ic.Plus, "Five minutes more", { minutes = (minutes + 5).coerceAtMost(180); endOfSong = false }, size = 44.dp, container = Color.White, content = Color(0xFF151515))
                    }
                    PillMeter(if (endOfSong) 12 else (minutes / 5).coerceAtMost(12), 12, c.onAccent)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60).forEach { m -> Pill("$m min", { minutes = m; endOfSong = false }, selected = !endOfSong && minutes == m) }
                    Pill("End of song", { endOfSong = true }, selected = endOfSong)
                }
                ToggleRow("Fade out", fade, { fade = it }, "Gently lower the volume over the last minute")
                Pill("Start timer", {
                    if (endOfSong) vm.startSleepEndOfSong(fade) else vm.startSleep(minutes, fade)
                    close()
                }, accent = true, big = true, icon = Ic.Clock, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun SortSheet(vm: MainViewModel, onBack: () -> Unit) {
    val c = P.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val labels = when (s.songSort) {
        SongSort.DATE_ADDED -> listOf("Newest first", "Oldest first")
        SongSort.LENGTH -> listOf("Shortest first", "Longest first")
        SongSort.MOST_PLAYED -> listOf("Most played first", "Least played first")
        else -> listOf("A to Z", "Z to A")
    }
    // Date added and Most played read naturally newest/most first, so "first" option = descending there.
    val naturalDesc = s.songSort == SongSort.DATE_ADDED || s.songSort == SongSort.MOST_PLAYED
    val selectedIndex = if (s.sortDescending == naturalDesc) 0 else 1

    KairosSheet(onBack) { close ->
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mixedHeadline("Sort " to false, "songs" to true), Modifier.weight(1f), style = T.h1.copy(fontSize = 34.sp), color = c.text)
                CircleButton(Ic.Close, "Close", close)
            }
            SongSort.entries.forEach { o ->
                RadioCard(o.label, "", s.songSort == o, {
                    vm.updateSettings { it.copy(songSort = o, sortDescending = o == SongSort.DATE_ADDED || o == SongSort.MOST_PLAYED) }
                })
            }
            Segmented(labels, selectedIndex, { i -> vm.updateSettings { it.copy(sortDescending = if (i == 0) naturalDesc else !naturalDesc) } })
            Pill("Show ${songs.size} songs", close, selected = true, big = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Slide-in menu with every screen. */
@Composable
fun MenuDrawer(vm: MainViewModel, onClose: () -> Unit, onNavigate: (String) -> Unit) {
    val c = P.colors
    val cur by vm.currentSong.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val items = listOfNotNull(
        Triple("Home", "home", Ic.Home),
        vm.onlineName?.let { Triple("Explore", "discover", Ic.Discover) },
        vm.onlineName?.let { Triple("Downloads", "downloads", Ic.DownArrow) },
        Triple("Library", "library", Ic.Library), Triple("Playlists", "playlists", Ic.Queue),
        Triple("Search", "search", Ic.Search), Triple("Statistics", "stats", Ic.Stats), Triple("Queue", "queue", Ic.Queue),
        Triple("Equalizer", "eq", Ic.Sliders), Triple(if (sleep.active) "Sleep timer (on)" else "Sleep timer", "sleep", Ic.Clock),
        Triple("Music folders", "folders", Ic.Folder), Triple("Settings", "settings", Ic.Settings),
    )
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x61000000)).clickable(onClickLabel = "Close menu", onClick = onClose))
        Column(
            Modifier.fillMaxSize().padding(end = 70.dp).clip(RoundedCornerShape(topEnd = 38.dp, bottomEnd = 38.dp)).background(c.sheet)
                .verticalScroll(rememberScrollState(), flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling()).statusBarsPadding().navigationBarsPadding().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircleButton(Ic.Close, "Close menu", onClose, container = c.ink, content = c.inkFg)
            Text(mixedHeadline("Your " to false, "music" to true), style = T.h1.copy(fontSize = 32.sp), color = c.text)
            if (cur != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(c.accent)
                        .clickable(role = Role.Button, onClickLabel = "Open now playing") { onNavigate("now") }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SongArt(vm, cur, 48.dp, corner = 15.dp)
                    Column(Modifier.weight(1f)) {
                        Text(cur?.title ?: "", style = T.title, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cur?.artist ?: "", style = T.small, color = c.onAccent, maxLines = 1)
                    }
                    CircleButton(if (now.isPlaying) Ic.Pause else Ic.Play, if (now.isPlaying) "Pause" else "Play", vm::toggle, size = 44.dp, container = c.ink, content = c.inkFg)
                }
            }
            items.forEach { (label, route, icon) ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item))
                        .selectable(false, role = Role.Button) { onNavigate(route) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(c.tint), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
                    }
                    Text(label, style = T.title.copy(fontSize = 18.sp), color = c.text)
                }
            }
            Box(Modifier.width(1.dp).height(8.dp))
        }
    }
}

/** The queue row being dragged: which one, where it started, how far it has moved. */
@androidx.compose.runtime.Stable
private class QueueDrag {
    var key by androidx.compose.runtime.mutableStateOf<Int?>(null)
    var fromPos = -1
    var offset by androidx.compose.runtime.mutableFloatStateOf(0f)
    var rowPx = 1f
    /** The position the row would land on if dropped now. */
    fun target(gapPx: Float, size: Int): Int =
        (fromPos + kotlin.math.round(offset / (rowPx + gapPx)).toInt()).coerceIn(0, (size - 1).coerceAtLeast(0))
    fun reset() { key = null; fromPos = -1; offset = 0f }
}
