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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
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
import com.skarif.kairos.online.VideoState
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
    // Song / Video: the video is only looked for while the player is open, and the picture is dropped
    // (back to the sound, same second) when the player closes or the app goes to the background.
    val videoState by vm.videoState.collectAsStateWithLifecycle()
    val wantsVideo by vm.wantsVideo.collectAsStateWithLifecycle()
    val wantsVideoNow = rememberUpdatedState(wantsVideo)
    val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
    val hasSyncedLyrics = (lyricsState as? com.skarif.kairos.ui.LyricsState.Ready)?.lyrics?.let { it.synced && !it.isEmpty() } == true
    val s = song ?: return
    val showToggle = vm.onlineName != null && s.volume == com.skarif.kairos.data.db.ONLINE_VOLUME
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
    var videoHelp by remember { androidx.compose.runtime.mutableStateOf<VideoState?>(null) }
    var videoPicker by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (videoPicker) VideoPickerDialog(vm, s) { videoPicker = false }
    val onlineNav = LocalOnlineNav.current
    if (menu) PlayerMenu(vm, s, onAlbum, { a -> onOpenCollection("artist:$a") }, onSleep, onEqualizer) { menu = false }
    if (speedDialog) SpeedDialog(vm) { speedDialog = false }
    videoHelp?.let { st ->
        val err = st as? VideoState.Error
        val isError = err != null
        com.skarif.kairos.ui.components.KairosDialog(
            onDismissRequest = { videoHelp = null },
            containerColor = c.sheet,
            title = {
                Text(
                    if (isError) "Couldn't check for video" else "No matching video found",
                    color = c.text,
                )
            },
            text = {
                Text(
                    if (err != null) {
                        "${err.reason ?: "Kairos couldn't check YouTube for a video right now."} You can retry, or pick a video yourself."
                    } else {
                        "No music video matched \"${s.title}\" automatically.\n\nPick one yourself from the search results, and Kairos will remember it for this song."
                    },
                    color = c.muted,
                    style = T.body,
                )
            },
            // Pick a video: search right here and choose one, instead of leaving the player.
            confirmButton = {
                androidx.compose.material3.TextButton({
                    videoHelp = null
                    videoPicker = true
                }) { Text("Pick a video", color = c.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton({
                    videoHelp = null
                    vm.retryVideoCheck(s)
                }) { Text("Retry", color = c.muted) }
                androidx.compose.material3.TextButton({
                    videoHelp = null
                    ctrl.collapse()
                    onlineNav.search("${s.title} ${s.artist}".trim(), com.skarif.kairos.online.OnlineKind.VIDEOS)
                }) { Text("Search tab", color = c.muted) }
            },
        )
    }

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
                        // Long-press the cover: vinyl mode on or off (not over a video).
                        // Double-tap a video: fill the cover, or fit it whole.
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { if (wantsVideoNow.value) ctrl.videoFill = !ctrl.videoFill },
                                onLongPress = {
                                    if (wantsVideoNow.value) return@detectTapGestures
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    ctrl.vinyl = !ctrl.vinyl
                                },
                            )
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
                        .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))),
                )
                if (!wantsVideo) Box(
                    Modifier.fillMaxWidth().height(240.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000), Color(0xC7000000)))),
                )
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleButton(Ic.Down, "Close player", { ctrl.collapse() }, container = Color(0x59000000), content = Color.White)
                    if (showToggle) {
                        // Online songs: Song | Video, like YouTube Music. ("Playing from" moves to the cover's caption.)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            ModeToggle(
                                videoState,
                                wantsVideo,
                                onSelect = { video ->
                                    if (video) ctrl.vinyl = false // a record and a video can't share the cover
                                    vm.setVideo(video)
                                },
                                onUnavailable = { st -> videoHelp = st },
                            )
                        }
                    } else {
                        // "Playing from": where this queue came from, like Spotify's header.
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PLAYING FROM", style = T.kicker, color = Color.White.copy(alpha = 0.8f))
                            Text(from ?: s.album, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    CircleButton(Ic.Lyrics, "Lyrics", onLyrics, container = Color(0x59000000), content = Color.White)
                    CircleButton(Ic.Menu, "More options", { menu = true }, container = Color(0x59000000), content = Color.White)
                }
                // Video controls. They are drawn here, after the gesture layer, so they get the touches;
                // the video itself is drawn by the player layer underneath this page.
                if (wantsVideo) Column(
                    Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasSyncedLyrics) CircleButton(
                        icon = Ic.Lyrics,
                        contentDescription = if (settings.videoSubtitles) "Hide lyrics subtitles" else "Show lyrics as subtitles",
                        onClick = { vm.updateSettings { it.copy(videoSubtitles = !it.videoSubtitles) } },
                        size = 40.dp,
                        container = if (settings.videoSubtitles) Color(0xB3FFFFFF) else Color(0x80000000),
                        content = if (settings.videoSubtitles) Color.Black else Color.White,
                        iconSize = 19.dp,
                    )
                    CircleButton(
                        icon = if (ctrl.videoFill) Ic.FillFrame else Ic.FitFrame,
                        contentDescription = if (ctrl.videoFill) "Video fills cover; switch to fit" else "Video fits cover; switch to fill",
                        onClick = { ctrl.videoFill = !ctrl.videoFill },
                        size = 40.dp,
                        container = Color(0x80000000),
                        content = Color.White,
                        iconSize = 19.dp,
                    )
                }
                if (!wantsVideo) Column(
                    Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 34.dp, end = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (showToggle) Text(
                        "Playing from ${from ?: s.album}", style = T.small, color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
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
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp)
                    .border(2.dp, c.bg, androidx.compose.foundation.shape.CircleShape),
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
                    val sourceKind = if (s.volume == com.skarif.kairos.data.db.ONLINE_VOLUME) "Online" else "Offline"
                    val mediaKind = if (now.videoActive) "Video" else "Audio"
                    // No video was found for this online song: say so here and offer to look again or search by hand.
                    if (showToggle && (videoState is VideoState.Unavailable || videoState is VideoState.Error)) {
                        InfoChip(Ic.Video, "Find video") { videoHelp = videoState }
                    }
                    InfoChip(Ic.Wave, "$mediaKind · $sourceKind")
                    InfoChip(Ic.Wave, now.output?.let { "${s.formatLabel()} $it" } ?: s.formatLabel())
                    InfoChip(Ic.Clock, formatTime(s.durationMs))
                    if (now.shownSize > 0) InfoChip(Ic.Queue, "${now.shownPosition + 1} of ${now.shownSize}", onQueue)
                    if (sleep.active) InfoChip(Ic.Clock, "Sleep timer on", onSleep)
                    if (kotlin.math.abs(now.speed - 1f) > 0.01f) InfoChip(Ic.Wave, speedLabel(now.speed)) { speedDialog = true }
                }
                // WaveformSeek already renders elapsed and remaining time below the bars.
                // Keep a single timestamp row to avoid the duplicated labels in the player.
                LiveWaveformSeek(vm, now.durationMs.takeIf { it > 0 } ?: s.durationMs, s.id)
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
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    PlayerActionTile(Ic.Shuffle, "Shuffle", active = now.shuffle, onClick = vm::toggleShuffle)
                    PlayerActionTile(
                        Ic.Wave, "Smart", active = settings.smartShuffle,
                        onClick = { vm.updateSettings { it.copy(smartShuffle = !it.smartShuffle) } },
                    )
                    PlayerActionTile(
                        if (now.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Ic.RepeatOne else Ic.Repeat,
                        if (now.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) "Repeat 1" else "Repeat",
                        active = now.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF,
                        onClick = vm::cycleRepeat,
                    )
                    PlayerActionTile(Ic.Queue, "Queue", active = false, onClick = onQueue)
                    PlayerActionTile(Ic.Radio, "Radio", active = false, onClick = { vm.startRadio(s) })
                    PlayerActionTile(Ic.Share, "Share", active = false, onClick = { shareSong(ctx, s) })
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
private fun PlayerActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val c = P.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        CircleButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            size = 46.dp,
            container = if (active) c.ink else c.card,
            content = if (active) c.inkFg else c.text,
            iconSize = 19.dp,
        )
        Text(
            label,
            style = T.small.copy(fontSize = 10.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
            color = c.text.copy(alpha = if (active) 1f else 0.80f),
            maxLines = 1,
        )
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


/**
 * Song | Video, at the top of the cover. Video is greyed out (with a spinner while the video is
 * being looked for) when the song has none, so the switch never jumps in and out.
 */
@Composable
private fun ModeToggle(state: VideoState, video: Boolean, onSelect: (Boolean) -> Unit, onUnavailable: (VideoState) -> Unit) {
    val videoReady = state is VideoState.Available
    val canExplain = state is VideoState.Unavailable || state is VideoState.Error
    Row(
        Modifier.clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0x59000000))
            .border(1.dp, Color.White.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegment("Audio", selected = !video, enabled = true, dimmed = false, busy = false) { onSelect(false) }
        ModeSegment(
            "Video",
            selected = video,
            enabled = videoReady || canExplain,
            dimmed = !videoReady,
            busy = state is VideoState.Checking,
            hint = videoReady && !video,
        ) {
            if (videoReady) onSelect(true) else if (canExplain) onUnavailable(state)
        }
    }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, enabled: Boolean, dimmed: Boolean, busy: Boolean, hint: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label, style = T.small.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) Color.Black else Color.White.copy(alpha = if (!dimmed) 1f else 0.50f),
        )
        // A video exists for this song and isn't showing: a small accent dot says "tap me".
        if (hint) Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(P.colors.accent))
        if (busy) androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = Color.White.copy(alpha = 0.8f),
        )
    }
}


/**
 * Choose a song's video by hand. Used when the automatic match found nothing (or the wrong clip):
 * the same YouTube video search the Search tab uses, right inside the player. The choice is kept
 * for this song and the player switches to Video as soon as it is ready.
 */
@Composable
private fun VideoPickerDialog(vm: MainViewModel, song: com.skarif.kairos.data.db.SongEntity, onDismiss: () -> Unit) {
    val c = P.colors
    var results by remember(song.id) { androidx.compose.runtime.mutableStateOf<List<com.skarif.kairos.data.db.SongEntity>?>(null) }
    var failed by remember(song.id) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(song.id) {
        try {
            results = vm.videoChoices(song)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    com.skarif.kairos.ui.components.KairosDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text("Pick a video", color = c.text) },
        text = {
            val list = results
            when {
                failed -> Text("Couldn't search YouTube right now. Check the connection and try again.", color = c.muted, style = T.body)
                list == null -> androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent,
                )
                list.isEmpty() -> Text("No videos came up for this song.", color = c.muted, style = T.body)
                else -> androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val shown = list.take(12)
                    items(shown.size) { i ->
                        val v = shown[i]
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable {
                                    vm.chooseVideo(song, v.fileName)
                                    onDismiss()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(v.title, color = c.text, style = T.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(v.artist, if (v.durationMs > 0) formatTime(v.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · "),
                                color = c.muted, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onDismiss) { Text("Cancel", color = c.muted) } },
    )
}
