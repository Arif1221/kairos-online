package com.skarif.kairos.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.launch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.skarif.kairos.ui.components.pressScale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.lyrics.Lyrics
import com.skarif.kairos.ui.LyricsState
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import kotlin.math.abs

// Follow the theme, like every other page: dark text on the light background, white on the dark one.
private val Glass: Color @Composable get() = if (P.colors.dark) Color(0x2EFFFFFF) else Color(0x14000000)
private val Dim: Color @Composable get() = P.colors.text.copy(alpha = 0.7f)
private val Fg: Color @Composable get() = P.colors.text

/**
 * Immersive lyrics over the player's blurred-cover background, in the theme's colours, with a
 * karaoke fill that sweeps across the current line. Timing controls hide behind the options button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsPane(vm: MainViewModel, onBack: () -> Unit, onQueue: () -> Unit, onCollapse: () -> Unit) {
    val c = P.colors
    val song by vm.currentSong.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val state by vm.lyrics.collectAsStateWithLifecycle()
    val offset by vm.lyricsOffset.collectAsStateWithLifecycle()
    // Keep the screen awake while reading along, if that setting is on.
    val keepOn = vm.settings.collectAsStateWithLifecycle().value.keepScreenOnLyrics
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    var options by rememberSaveable { mutableStateOf(false) }
    var getPanel by rememberSaveable { mutableStateOf(false) }
    // Lyrics-first: huge type, the controls tucked away. For reading along, or a phone on a stand.
    var big by rememberSaveable { mutableStateOf(false) }
    val downloading by vm.lyricsDownloading.collectAsStateWithLifecycle()
    // Saving an .lrc beside the song needs a folder you allow once (Android's own picker).
    val pickLyricsFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) vm.grantLyricsFolderAndSave(uri) }
    val fromOnline = (state as? LyricsState.Ready)?.lyrics?.source == Lyrics.SOURCE_ONLINE
    // The player's own background (the blurred cover) shows through, as on the main player.
    Box(Modifier.fillMaxSize()    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircleButton(Ic.Down, "Back to now playing", onBack, size = 44.dp, container = Glass, content = Fg)
                // The player layer draws the cover here (it shrinks from the big hero into this spot).
                Spacer(Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.title ?: "", style = T.title.copy(fontSize = 16.sp), color = Fg, maxLines = 1,
                        modifier = Modifier.liveMarquee(),
                    )
                    Text(song?.artist ?: "", style = T.small.copy(fontSize = 12.sp), color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!big) CircleButton(
                    if (getPanel) Ic.Close else Ic.DownArrow, if (getPanel) "Hide download" else "Download lyrics",
                    { getPanel = !getPanel; if (getPanel) options = false },
                    size = 44.dp, container = if (getPanel) c.ink else Glass, content = if (getPanel) c.inkFg else Fg,
                )
                val synced = (state as? LyricsState.Ready)?.lyrics?.synced == true
                if (synced && !big) {
                    CircleButton(
                        if (options) Ic.Close else Ic.Sliders, if (options) "Hide timing" else "Lyrics timing", { options = !options; if (options) getPanel = false },
                        size = 44.dp, container = if (options) c.ink else Glass, content = if (options) c.inkFg else Fg,
                    )
                }
            }

            // Download from LRCLIB, in the form you pick. Works even with auto-fetch off: you asked.
            AnimatedVisibility(getPanel, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(
                    Modifier.padding(horizontal = 18.dp).fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(Glass)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (downloading) "Searching lrclib.net…"
                        else "Download from lrclib.net. Kept privately in the app, no permission needed. \"Save .lrc\" also puts a file next to the song.",
                        style = T.small, color = Dim,
                    )
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallPill("Time-synced", "Download time-synced lyrics") {
                            vm.downloadLyrics(com.skarif.kairos.data.OnlineLyricsType.SYNCED); getPanel = false
                        }
                        SmallPill("Plain text", "Download plain lyrics") {
                            vm.downloadLyrics(com.skarif.kairos.data.OnlineLyricsType.PLAIN); getPanel = false
                        }
                        if (fromOnline) SmallPill("Save .lrc", "Save lyrics as a file next to the song") {
                            vm.saveLyricsFile { vm.notify("Pick the folder this song is in"); pickLyricsFolder.launch(null) }
                        }
                        if (fromOnline) SmallPill("Remove", "Remove downloaded lyrics") { vm.removeDownloadedLyrics(); getPanel = false }
                    }
                }
            }

            AnimatedVisibility(options, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(
                    Modifier.padding(horizontal = 18.dp).fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(Glass)
                        .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val source = (state as? LyricsState.Ready)?.lyrics?.let { when (it.source) { Lyrics.SOURCE_EMBEDDED -> "Embedded"; Lyrics.SOURCE_ONLINE -> "LRCLIB"; else -> ".lrc file" } }
                    Text(
                        (source?.let { "$it, " } ?: "") + if (offset == 0L) "in sync" else (if (offset > 0) "Later by " else "Earlier by ") + "%.1f s".format(abs(offset) / 1000f),
                        Modifier.weight(1f), style = T.title.copy(fontSize = 14.sp), color = Fg,
                    )
                    SmallPill("−0.1 s", "Show lyrics earlier") { vm.nudgeLyrics(-100) }
                    SmallPill("+0.1 s", "Show lyrics later") { vm.nudgeLyrics(100) }
                    if (offset != 0L) SmallPill("Reset", "Reset lyrics timing") { vm.nudgeLyrics(-offset) }
                }
            }

            Box(
                Modifier.weight(1f).fillMaxWidth()
                    // Lines fade out at the top and bottom edges instead of being cut off.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent, 0.12f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                when (val st = state) {
                    LyricsState.Loading -> Message("Looking for lyrics…")
                    LyricsState.None -> if (downloading) Message("Searching lrclib.net…") else Column {
                        Message(
                            "No lyrics for this song yet. Download them, add them to the file's tags, or put a .lrc " +
                                "file with the same name next to the song and allow that folder in Settings.",
                        )
                        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallPill("Get time-synced", "Download time-synced lyrics") { vm.downloadLyrics(com.skarif.kairos.data.OnlineLyricsType.SYNCED) }
                            SmallPill("Get plain", "Download plain lyrics") { vm.downloadLyrics(com.skarif.kairos.data.OnlineLyricsType.PLAIN) }
                        }
                    }
                    is LyricsState.Ready -> LyricsList(vm, st.lyrics, offset, c.accent, big) { t -> vm.seekTo(t) }
                }
            }

            // Compact transport. In lyrics-first mode only the seek bar and skip buttons step away;
            // Queue, the mode switch and play/pause stay, so the way back is always in reach.
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val s = song
                AnimatedVisibility(!big, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    if (s != null) LiveWaveformSeek(vm, now.durationMs.takeIf { it > 0 } ?: s.durationMs, s.id, barsHeight = 28.dp, bars = 32)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleButton(Ic.Queue, "Queue", onQueue, size = 46.dp, container = Glass, content = Fg)
                    if (state is LyricsState.Ready) FocusToggle(big) { big = !big; if (big) { getPanel = false; options = false } }
                    Box(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!big) CircleButton(Ic.Prev, "Previous song", vm::previous, size = 46.dp, container = Glass, content = Fg)
                        CircleButton(
                            if (now.isPlaying) Ic.Pause else Ic.Play, if (now.isPlaying) "Pause" else "Play", vm::toggle,
                            size = if (big) 46.dp else 56.dp, container = Color.White, content = Color.Black,
                        )
                        if (!big) CircleButton(Ic.Next, "Next song", vm::next, size = 46.dp, container = Glass, content = Fg)
                    }
                }
            }
        }
    }
}

/**
 * The staggered "wave" when lyrics move on, as in Apple Music: the list jumps to the new line
 * at once, then every line is drawn where it was and springs into place, each a little later
 * than the one above it. One frame clock drives all lines; each reads it in its draw phase.
 */
@androidx.compose.runtime.Stable
private class Cascade {
    var distance by androidx.compose.runtime.mutableFloatStateOf(0f)
    var startNanos by androidx.compose.runtime.mutableLongStateOf(0L)
    var nowNanos by androidx.compose.runtime.mutableLongStateOf(0L)
    var anchor by androidx.compose.runtime.mutableIntStateOf(0)

    /** Critically damped spring (no overshoot), response 0.6 s, delayed per line below [anchor]. */
    fun offsetFor(index: Int): Float {
        if (distance == 0f) return 0f
        val delayMs = ((index - anchor).coerceIn(0, 10)) * 45L
        val t = ((nowNanos - startNanos) / 1_000_000L - delayMs).coerceAtLeast(0L) / 1000f
        val w = (2 * Math.PI / 0.6).toFloat()
        return distance * (1 + w * t) * kotlin.math.exp(-w * t)
    }
}

/**
 * Apple Music-style lyrics: big bold lines, the current one bright, the rest dimmed and blurred
 * more the further they are from it. Lines move in a staggered wave, the current line fills as
 * it is sung, and instrumental breaks show three breathing dots. Scroll by hand to read ahead:
 * following pauses and every line comes into focus until you stop for a few seconds.
 * Tap any line to play from there.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricsList(vm: MainViewModel, lyrics: Lyrics, offsetMs: Long, accent: Color, big: Boolean = false, onSeek: (Long) -> Unit) {
    val list = rememberLazyListState()
    val position = rememberLivePosition(vm)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Long-press a line: a share card with the line over the blurred cover, in the album's colour.
    fun shareLines(text: String) {
        val song = vm.currentSong.value ?: return
        scope.launch {
            val cover = vm.cover(song)
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.skarif.kairos.ui.components.ShareCard.render(ctx, cover, accent.toArgb(), "Lyrics", text, song.title, song.artist)
            }
            com.skarif.kairos.ui.components.ShareCard.share(ctx, bmp, "${song.title} lyrics")
        }
    }
    // Positive offset means the lyrics should show later, so compare against an earlier position.
    val current by remember(lyrics, offsetMs) {
        derivedStateOf { if (lyrics.synced) lyrics.indexAt(position.value - offsetMs) else -1 }
    }
    var viewport by remember { mutableIntStateOf(0) }
    val cascade = remember { Cascade() }
    // One item above the lines: the intro dots (synced) or the "not synced" note.
    val header = 1

    // Reading ahead: a drag pauses following and unblurs; 3 s after the last touch it resumes.
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var browsing by remember { mutableStateOf(false) }
    // Sharing several lines: long-press starts a selection, taps add or remove lines (up to 8).
    val picked = remember(lyrics) { androidx.compose.runtime.mutableStateListOf<Int>() }
    val selecting = picked.isNotEmpty()
    fun toggle(i: Int) {
        if (i in picked) picked.remove(i) else if (picked.size < 8) picked.add(i)
        else vm.notify("Up to 8 lines on one card")
    }
    LaunchedEffect(dragged) {
        if (dragged) browsing = true
        else if (browsing) { kotlinx.coroutines.delay(3000); browsing = false }
    }

    LaunchedEffect(current, browsing, selecting, viewport) {
        if (current < 0 || browsing || selecting || viewport == 0) return@LaunchedEffect
        val target = current + header
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        if (info == null) {
            list.scrollToItem(target, -(viewport / 3))
            return@LaunchedEffect
        }
        val d = (info.offset - viewport / 3).toFloat()
        if (abs(d) < 1f) return@LaunchedEffect
        // A new line while the last wave is still moving: start from where things are on screen
        // now, not from where they were meant to end, or the lines snap.
        val leftover = cascade.offsetFor(target)
        // Set the wave to its start (time 0 = full offset) *before* scrolling, so the frame that
        // shows the scrolled list also shows the offset. Doing it after waited a frame, and that
        // one frame showed the lines jump up, then back down, then glide.
        cascade.startNanos = 0L
        cascade.nowNanos = 0L
        // Use how far the list really moved: at the top of the song it can't scroll up, and
        // offsetting by the full distance made the first lines jump up, then slide back.
        val moved = list.scrollBy(d)
        cascade.anchor = target
        cascade.distance = moved + leftover
        val t0 = androidx.compose.runtime.withFrameNanos { it }
        cascade.startNanos = t0
        cascade.nowNanos = t0
        val until = cascade.startNanos + 1_400_000_000L
        while (cascade.nowNanos < until) androidx.compose.runtime.withFrameNanos { cascade.nowNanos = it }
        cascade.distance = 0f
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it.height },
        flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
        contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 64.dp, bottom = 260.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header", contentType = "header") {
            if (!lyrics.synced) {
                Text("These lyrics aren't time-synced.", style = T.small, color = Dim)
            } else {
                // Intro: dots until the first line, if the song starts with music only.
                // Always the same height (the dots only fade): if this space collapsed when the singing
                // started, every line jumped up at that exact moment.
                val first = lyrics.lines.firstOrNull()?.timeMs?.plus(offsetMs) ?: 0L
                val intro = first > 3_000 && current < 0
                val introAlpha by animateFloatAsState(if (intro) 1f else 0f, tween(300), label = "intro")
                BreathingDots(position, 0L, first, Modifier.padding(vertical = 8.dp).graphicsLayer { alpha = introAlpha })
            }
        }
        itemsIndexed(lyrics.lines, key = { i, _ -> i }, contentType = { _, l -> if (l.text.isBlank()) "break" else "line" }) { i, line ->
            if (!lyrics.synced) {
                Text(line.text.ifEmpty { " " }, Modifier.fillMaxWidth(), style = T.body.copy(fontSize = 22.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold), color = Fg)
                return@itemsIndexed
            }
            val start = line.timeMs + offsetMs
            val end = lyrics.lines.getOrNull(i + 1)?.timeMs?.plus(offsetMs) ?: (start + 4000)
            val isCur = i == current
            val distance = abs(i - current)
            // Brightness and blur by distance from the current line; everything sharp while browsing.
            val alpha by animateFloatAsState(
                when { i in picked -> 1f; browsing || selecting -> 0.8f; isCur -> 1f; i < current -> 0.3f; else -> (0.55f - 0.06f * (distance - 1)).coerceAtLeast(0.25f) },
                com.skarif.kairos.ui.motion.Motion.ios(0.5f, 1f), label = "lineAlpha",
            )
            // Blur in dp, kept as State and read only while drawing: changing it never recomposes.
            val blur = animateFloatAsState(
                if (browsing || selecting || isCur) 0f else distance.coerceAtMost(4) * 1.1f,
                com.skarif.kairos.ui.motion.Motion.ios(0.5f, 1f), label = "lineBlur",
            )
            // Lyrics-first mode makes the line being sung clearly the biggest; the rest step back.
            val scale by animateFloatAsState(
                when { isCur -> 1f; big -> 0.7f; else -> 0.94f }, com.skarif.kairos.ui.motion.Motion.ios(0.55f, 0.8f), label = "lineScale",
            )
            val itemIndex = i + header
            val lineMod = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = cascade.offsetFor(itemIndex)
                    scaleX = scale; scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    this.alpha = alpha
                    val r = blur.value.dp.toPx()
                    renderEffect = if (r > 0.5f && android.os.Build.VERSION.SDK_INT >= 31) {
                        androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Decal)
                    } else null
                }
                .clip(RoundedCornerShape(14.dp))
                .background(if (i in picked) accent.copy(alpha = 0.22f) else Color.Transparent)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (selecting) "Select this line" else "Play from this line",
                    onClick = { if (selecting) { if (line.text.isNotBlank()) toggle(i) } else onSeek(start) },
                    onLongClickLabel = "Share lines",
                    onLongClick = if (line.text.isBlank()) null else ({
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        toggle(i)
                    }),
                )
                .padding(vertical = 6.dp)
                .then(if (isCur) Modifier.semantics { heading() } else Modifier)

            if (line.text.isBlank()) {
                // An instrumental break: dots while it lasts, a small gap otherwise.
                // Fixed height too; only the dots fade in and out.
                val dotsAlpha by animateFloatAsState(if (isCur) 1f else 0f, tween(300), label = "breakDots")
                Box(lineMod) { BreathingDots(position, start, end, Modifier.graphicsLayer { this.alpha = dotsAlpha }) }
                return@itemsIndexed
            }
            var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            Text(
                line.text,
                lineMod
                    .graphicsLayer { if (isCur) compositingStrategy = CompositingStrategy.Offscreen }
                    .then(if (isCur) Modifier.karaoke(position, start, end) { layout } else Modifier),
                // One weight for every line: switching weight re-wraps the text and makes it jump.
                // The sung words light up, in dark themes only: a small soft white glow, like Apple
                // Music. Small on purpose: each line is clipped to its own box (for the tap ripple and
                // the karaoke sweep), and a wide glow got cut into a hard-edged band. In light themes
                // dark words on a light page are already crisp, and a coloured glow only smudged them.
                // A shadow changes drawing, not layout, so the line doesn't re-wrap when it lights up.
                style = T.h1.copy(
                    fontSize = if (big) 46.sp else 34.sp, lineHeight = if (big) 52.sp else 40.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp,
                    shadow = if (isCur && P.colors.dark) androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = 0.45f), blurRadius = 10f) else null,
                ),
                color = Fg,
                onTextLayout = { layout = it },
            )
        }
    }
    // The share bar while lines are selected.
    AnimatedVisibility(
        selecting, Modifier.align(Alignment.BottomCenter),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom), exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)).background(P.colors.sheet).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (picked.size == 1) "1 line" else "${picked.size} lines", Modifier.weight(1f).padding(start = 12.dp),
                style = T.title, color = P.colors.text,
            )
            com.skarif.kairos.ui.components.Pill("Cancel", { picked.clear() })
            com.skarif.kairos.ui.components.Pill("Share", {
                shareLines(picked.sorted().joinToString("\n") { lyrics.lines[it].text })
                picked.clear()
            }, accent = true, icon = Ic.Share)
        }
    }
    }
}

/**
 * Apple Music's interlude: three dots that light up one by one over the break and breathe
 * gently, then swell and fade just before the singing comes back.
 */
@Composable
private fun BreathingDots(position: State<Long>, startMs: Long, endMs: Long, modifier: Modifier = Modifier) {
    val breathe = androidx.compose.animation.core.rememberInfiniteTransition(label = "breathe")
    val b by breathe.animateFloat(
        0.92f, 1.08f,
        androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "breatheScale",
    )
    val color = Fg
    Box(
        modifier.size(width = 88.dp, height = 34.dp).graphicsLayer {
            val span = (endMs - startMs).coerceAtLeast(1).toFloat()
            val p = ((position.value - startMs) / span).coerceIn(0f, 1f)
            // Last 10%: swell up and fade out, handing over to the next line.
            val outro = ((p - 0.9f) / 0.1f).coerceIn(0f, 1f)
            val s = b * (1f + 0.25f * outro)
            scaleX = s; scaleY = s
            alpha = 1f - outro
            transformOrigin = TransformOrigin(0f, 0.5f)
        }.drawWithContent {
            val span = (endMs - startMs).coerceAtLeast(1).toFloat()
            val p = ((position.value - startMs) / span).coerceIn(0f, 1f)
            val r = 7.dp.toPx()
            for (k in 0..2) {
                // Each dot fills over its third of the break.
                val lit = ((p * 3f) - k).coerceIn(0f, 1f)
                drawCircle(color.copy(alpha = 0.25f + 0.75f * lit), r, Offset(r + k * (r * 2 + 8.dp.toPx()), size.height / 2))
            }
        },
    )
}

/**
 * Sung part stays white, the rest is dimmed. Position is read in the draw phase, so the sweep
 * redraws every tick without recomposing the list.
 */
private fun Modifier.karaoke(
    position: State<Long>, startMs: Long, endMs: Long, layout: () -> androidx.compose.ui.text.TextLayoutResult?,
): Modifier = drawWithContent {
    drawContent()
    val l = layout() ?: return@drawWithContent
    // Fill the wrapped lines one after another, start to finish, like reading.
    val span = (endMs - startMs).coerceAtLeast(1).toFloat()
    val p = ((position.value - startMs) / span).coerceIn(0f, 1f)
    val widths = FloatArray(l.lineCount) { l.getLineRight(it) - l.getLineLeft(it) }
    var fill = widths.sum() * p
    val feather = 36.dp.toPx()
    for (i in 0 until l.lineCount) {
        val left = l.getLineLeft(i)
        val right = l.getLineRight(i)
        val top = l.getLineTop(i)
        val h = l.getLineBottom(i) - top
        val covered = fill.coerceIn(0f, widths[i])
        fill -= widths[i]
        if (covered >= widths[i]) continue
        val x = left + covered
        if (covered > 0f) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = x - feather, endX = x),
                topLeft = Offset(x - feather, top), size = Size(feather, h), blendMode = BlendMode.DstOut, alpha = 0.6f,
            )
        }
        drawRect(Color.Black, topLeft = Offset(x, top), size = Size(size.width - x, h), blendMode = BlendMode.DstOut, alpha = 0.6f)
    }
}

/**
 * The lyrics-first switch: a pill reading "Aa Focus", so what it does is clear at a glance.
 * Filled with the album's colour while on.
 */
@Composable
private fun FocusToggle(on: Boolean, onClick: () -> Unit) {
    val c = P.colors
    val bg by androidx.compose.animation.animateColorAsState(if (on) c.accent else Glass, tween(200), label = "focusBg")
    val fg = if (on) c.onAccent else Fg
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.height(46.dp).pressScale(press).clip(RoundedCornerShape(23.dp)).background(bg)
            .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), role = Role.Switch, onClickLabel = if (on) "Show controls" else "Big lyrics", onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Aa", style = T.title.copy(fontWeight = FontWeight.ExtraBold, fontSize = 17.sp), color = fg)
        Text(if (on) "Focus on" else "Focus", style = T.small.copy(fontWeight = FontWeight.Bold), color = fg)
    }
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.padding(24.dp), style = T.body, color = Dim)
}

@Composable
private fun SmallPill(text: String, description: String, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .height(40.dp)
            .pressScale(press, 0.94f)
            .clip(RoundedCornerShape(20.dp))
            .background(Glass)
            .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = T.small.copy(fontWeight = FontWeight.Bold), color = Fg)
    }
}
