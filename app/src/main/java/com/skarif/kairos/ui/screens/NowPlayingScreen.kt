package com.skarif.kairos.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.db.formatLabel
import kotlinx.coroutines.launch
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.formatTime
import com.skarif.kairos.ui.components.sharedCard
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T

/**
 * The full player's main page. The cover itself is drawn by the player layer underneath, so it
 * can morph from the mini player; this page draws everything on top of it and handles the
 * cover's gestures: sideways to change song, down to close.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingPane(
    vm: MainViewModel,
    ctrl: com.skarif.kairos.ui.player.PlayerController,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onEqualizer: () -> Unit,
    onSleep: () -> Unit,
    onAlbum: (Long) -> Unit,
    onOpenCollection: (String) -> Unit,
    onSkip: (dir: Int, velocityX: Float) -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean,
) {
    val c = P.colors
    val song by vm.currentSong.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    // Shake the phone to reshuffle what's coming up; only while this page is actually showing.
    val haptic = LocalHapticFeedback.current
    var artistPick by remember { androidx.compose.runtime.mutableStateOf<List<String>?>(null) }
    com.skarif.kairos.ui.components.ShakeDetector(LocalPlayerVisible.current.value && ctrl.page == com.skarif.kairos.ui.player.PlayerPage.MAIN) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        vm.reshuffle()
    }
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val s = song ?: return
    // Must match the cover rectangle in PlayerSheet.
    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.47f).coerceIn(300f, 440f).dp
    val scope = rememberCoroutineScope()
    val tracker = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }
    var axis by remember { mutableIntStateOf(0) } // 0 undecided, 1 sideways, 2 down
    val liked = s.id in favs
    val from by vm.playingFrom.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { androidx.compose.runtime.mutableStateOf(false) }
    var speedDialog by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (menu) PlayerMenu(vm, s, onAlbum, { a -> onOpenCollection("artist:$a") }, onSleep, onEqualizer) { menu = false }
    if (speedDialog) SpeedDialog(vm) { speedDialog = false }

    // Pulling down anywhere on the player closes it once the page is scrolled to the top,
    // with the same swipe rule as the cover (before, only the cover could be dragged).
    val pullToClose = remember(ctrl) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // Already pulling the sheet: keep moving it both ways instead of scrolling.
                if (ctrl.dragging && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    ctrl.dragBy(available.y)
                    return available.copy(x = 0f)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    ctrl.dragBy(available.y)
                    return available.copy(x = 0f)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (ctrl.dragging) { ctrl.settle(available.y); return available }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    Column(Modifier.fillMaxSize().nestedScroll(pullToClose).verticalScroll(ctrl.mainScroll, flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling()).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(heroHeight + 38.dp)) {
            Box(Modifier.fillMaxWidth().height(heroHeight).clip(RoundedCornerShape(bottomStart = 46.dp, bottomEnd = 46.dp))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // Long-press the cover: vinyl mode on or off.
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                ctrl.vinyl = !ctrl.vinyl
                            })
                        }
                        .pointerInput(s.id, hasPrev, hasNext, ctrl.vinyl) {
                            // Vinyl: turn the record to scrub. One full turn is 10 seconds of music.
                            var turned = 0f
                            detectDragGestures(
                                onDragStart = { tracker.resetTracking(); axis = 0; turned = 0f; if (ctrl.vinyl) ctrl.vinylHeld = true },
                                onDragEnd = {
                                    val v = tracker.calculateVelocity()
                                    if (ctrl.vinyl) {
                                        ctrl.vinylHeld = false
                                        val to = vm.position.value + (turned / 360f * 10_000f).toLong()
                                        vm.seekTo(to.coerceIn(0L, (vm.now.value.durationMs - 500).coerceAtLeast(0L)))
                                    } else if (axis == 2) {
                                        ctrl.settle(v.y)
                                    } else {
                                        // Shared swipe rule: 72 dp or a quick flick, same as everywhere else.
                                        val dir = com.skarif.kairos.ui.motion.Swipe.decide(ctrl.skipX.value, v.x, density)
                                        onSkip(dir, v.x)
                                    }
                                },
                                onDragCancel = {
                                    if (ctrl.vinyl) ctrl.vinylHeld = false
                                    else if (axis == 2) ctrl.settle(0f) else onSkip(0, 0f)
                                },
                            ) { change, amount ->
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (ctrl.vinyl) {
                                    // The angle the finger swept around the record's centre.
                                    val c0 = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                    fun deg(o: androidx.compose.ui.geometry.Offset) = Math.toDegrees(kotlin.math.atan2((o.y - c0.y).toDouble(), (o.x - c0.x).toDouble())).toFloat()
                                    var d = deg(change.position) - deg(change.previousPosition)
                                    if (d > 180f) d -= 360f else if (d < -180f) d += 360f
                                    turned += d
                                    ctrl.vinylAngle += d
                                    return@detectDragGestures
                                }
                                // Lock to one direction from the first movement: change song or close, never both.
                                if (axis == 0) axis = if (kotlin.math.abs(amount.x) >= kotlin.math.abs(amount.y)) 1 else 2
                                if (axis == 1) {
                                    // Resist at the ends of the queue.
                                    val nx = ctrl.skipX.value + amount.x
                                    val edge = (nx > 0 && !hasPrev) || (nx < 0 && !hasNext)
                                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { ctrl.skipX.snapTo(if (edge) ctrl.skipX.value + amount.x * 0.3f else nx) }
                                } else {
                                    ctrl.dragBy(amount.y)
                                }
                            }
                        },
                )
                // Scrims keep the buttons and the title readable on any cover, light or dark.
                Box(
                    Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent))),
                )
                Box(
                    Modifier.fillMaxWidth().height(240.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000), Color(0xC7000000)))),
                )
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleButton(Ic.Down, "Close player", { ctrl.collapse() }, container = Color(0x59000000), content = Color.White)
                    // "Playing from": where this queue came from, like Spotify's header.
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", style = T.small.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp), color = Color.White.copy(alpha = 0.8f))
                        Text(from ?: s.album, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CircleButton(Ic.Lyrics, "Lyrics", onLyrics, container = Color(0x59000000), content = Color.White)
                    CircleButton(Ic.Menu, "More options", { menu = true }, container = Color(0x59000000), content = Color.White)
                }
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 34.dp, end = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Ic.Music, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text(if (s.year > 0) "${s.year}" else "Album", color = Color.White, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Text(
                        s.album, color = Color.White, style = T.big.copy(fontSize = 26.sp, lineHeight = 30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open album") { onAlbum(s.albumId) },
                    )
                    Text(
                        "Open album", color = Color.White, style = T.small.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                        modifier = Modifier.clickable(role = Role.Button) { onAlbum(s.albumId) },
                    )
                }
            }
            // Online songs: download next to the heart, where it's seen.
            if (s.volume == com.skarif.kairos.data.db.ONLINE_VOLUME && vm.onlineName != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 108.dp, bottom = 10.dp)) {
                    DownloadButton(vm, s, 56.dp, container = c.card, content = c.text)
                }
            }
            CircleButton(
                if (liked) Ic.HeartFilled else Ic.Heart,
                if (liked) "Remove from favourites" else "Add to favourites",
                { vm.toggleFavorite(s.id) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp),
                size = 76.dp, container = c.accent, content = c.onAccent, iconSize = 28.dp,
            )
        }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        s.artist.uppercase(), color = c.text, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = T.small.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClickLabel = "Open artist") {
                                // One artist: straight there. Several ("A, B & C"): pick which one.
                                val all = com.skarif.kairos.data.splitArtists(s.artist)
                                if (all.size == 1) onOpenCollection("artist:${all.first()}") else artistPick = all
                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    artistPick?.let { names ->
                        com.skarif.kairos.ui.components.ActionSheet(
                            title = "Artists", subtitle = s.title,
                            art = { SongArt(vm, s, 52.dp, corner = 12.dp) },
                            groups = listOf(names.map { n -> com.skarif.kairos.ui.components.SheetAction(n, Ic.Person) { onOpenCollection("artist:$n") } }),
                            onDismiss = { artistPick = null },
                        )
                    }
                    Text(
                        s.title, color = c.text, style = T.h2.copy(fontSize = 30.sp, lineHeight = 34.sp), textAlign = TextAlign.Center, maxLines = 1,
                        modifier = Modifier.liveMarquee(),
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(Ic.Wave, now.output?.let { "${s.formatLabel()} $it" } ?: s.formatLabel())
                    InfoChip(Ic.Clock, formatTime(s.durationMs))
                    if (now.shownSize > 0) InfoChip(Ic.Queue, "${now.shownPosition + 1} of ${now.shownSize}", onQueue)
                    if (sleep.active) InfoChip(Ic.Clock, "Sleep timer on", onSleep)
                    if (kotlin.math.abs(now.speed - 1f) > 0.01f) InfoChip(Ic.Wave, speedLabel(now.speed)) { speedDialog = true }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LiveWaveformSeek(vm, now.durationMs.takeIf { it > 0 } ?: s.durationMs, s.id)
                    TimeRow(vm, now.durationMs.takeIf { it > 0 } ?: s.durationMs)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircleButton(Ic.Prev, "Previous song", vm::previous, size = 64.dp, iconSize = 22.dp)
                    val playPress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Row(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .then(com.skarif.kairos.ui.components.PressScaleHolder.modifier(playPress, 0.95f))
                            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card))
                            .background(c.ink)
                            .clickable(interactionSource = playPress, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = vm::toggle),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    ) {
                        AnimatedContent(
                            now.isPlaying,
                            transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(200), 0.7f)) togetherWith fadeOut(tween(120)) },
                            label = "playPause",
                        ) { playing ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (playing) Ic.Pause else Ic.Play, null, tint = c.inkFg, modifier = Modifier.size(20.dp))
                                Text(if (playing) "Pause" else "Play", color = c.inkFg, style = T.title.copy(fontSize = 17.sp))
                            }
                        }
                    }
                    CircleButton(Ic.Next, "Next song", vm::next, size = 64.dp, iconSize = 22.dp)
                }
                Row(Modifier.padding(bottom = 24.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleButton(Ic.Shuffle, if (now.shuffle) "Shuffle, on" else "Shuffle, off", vm::toggleShuffle, size = 48.dp,
                        container = if (now.shuffle) c.ink else c.card, content = if (now.shuffle) c.inkFg else c.text)
                    CircleButton(
                        Ic.Wave, if (settings.smartShuffle) "Smart shuffle, on" else "Smart shuffle, off",
                        { vm.updateSettings { it.copy(smartShuffle = !it.smartShuffle) } }, size = 48.dp,
                        container = if (settings.smartShuffle) c.ink else c.card, content = if (settings.smartShuffle) c.inkFg else c.text,
                    )
                    CircleButton(
                        if (now.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Ic.RepeatOne else Ic.Repeat,
                        "Repeat", vm::cycleRepeat, size = 48.dp,
                        container = if (now.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) c.ink else c.card,
                        content = if (now.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) c.inkFg else c.text,
                    )
                    CircleButton(Ic.Queue, "Queue", onQueue, size = 48.dp)
                    CircleButton(Ic.Radio, "Start song radio", { vm.startRadio(s) }, size = 48.dp)
                    CircleButton(Ic.Share, "Share", { shareSong(ctx, s) }, size = 48.dp)
                }
            }
            // The cards below the fold are composed after the player has finished opening, so the
            // opening animation's first frames only have the visible part of the player to build.
            var cardsReady by remember { androidx.compose.runtime.mutableStateOf(false) }
            val shown = LocalPlayerVisible.current.value
            LaunchedEffect(shown) {
                if (shown && !cardsReady) {
                    kotlinx.coroutines.delay(150)
                    androidx.compose.runtime.snapshotFlow { com.skarif.kairos.ui.motion.PlayerMotion.animating }.first { !it }
                    com.skarif.kairos.perf.PerfLog.state("player cards", "building")
                    cardsReady = true
                    kotlinx.coroutines.delay(300)
                    com.skarif.kairos.perf.PerfLog.state("player cards", null)
                }
            }
            if (cardsReady) NowPlayingCards(vm, s, onQueue = onQueue, onLyrics = onLyrics, onOpenCollection = onOpenCollection)
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: (() -> Unit)? = null) {
    val c = P.colors
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.height(44.dp)
            .then(if (onClick != null) com.skarif.kairos.ui.components.PressScaleHolder.modifier(press, 0.94f) else Modifier)
            .clip(RoundedCornerShape(22.dp)).background(c.card)
            .then(if (onClick != null) Modifier.clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(16.dp))
        Text(text, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
    }
}
