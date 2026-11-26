package com.skarif.kairos.ui.screens

import androidx.compose.foundation.basicMarquee
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.ArtworkRepository
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.KairosDialog
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.ActionSheet
import com.skarif.kairos.ui.components.SheetAction
import com.skarif.kairos.ui.components.pressScale
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.formatTime
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T

/** Album art for a song, loaded off the main thread and cached. */
@Composable
fun SongArt(vm: MainViewModel, song: SongEntity?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 16.dp, large: Boolean = false) {
    val c = P.colors
    val bmp by produceState<Bitmap?>(null, song?.id, large) {
        value = song?.let { if (large) vm.cover(it) else vm.thumbnail(it) }
    }
    val img = bmp
    if (img != null) {
        Image(img.asImageBitmap(), null, modifier.size(size).clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(size).clip(RoundedCornerShape(corner)).background(c.tint), contentAlignment = Alignment.Center) {
            Icon(Ic.Music, null, tint = c.muted, modifier = Modifier.size(size / 2.6f))
        }
    }
}

/** Like [rememberCover], but a cached cover shows on the very first frame, with no flash of the old one. */
@Composable
fun rememberCoverNow(vm: MainViewModel, song: SongEntity?): ImageBitmap? {
    val loaded by produceState<Pair<Long, Bitmap?>?>(null, song?.id) { value = song?.let { it.id to vm.cover(it) } }
    val bmp = song?.let { vm.cachedCover(it) ?: loaded?.takeIf { l -> l.first == it.id }?.second }
    return androidx.compose.runtime.remember(bmp) { bmp?.asImageBitmap() }
}

/** Large cover image used by heroes. */
@Composable
fun rememberCover(vm: MainViewModel, song: SongEntity?): ImageBitmap? {
    // Straight from the memory cache when it's there, so cards coming back on screen don't pop in.
    return rememberCoverNow(vm, song)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongEntity,
    playing: Boolean,
    vm: MainViewModel,
    number: Int? = null,
    onLongClick: (() -> Unit)? = null,
    swipe: Boolean = true,
    /** Pass from the list's screen so a long list doesn't collect favourites once per row. */
    favourite: Boolean? = null,
    onClick: () -> Unit,
) {
    if (swipe) {
        val fav = favourite ?: (song.id in vm.favorites.collectAsStateWithLifecycle().value)
        SwipeActions(
            "Play next", Ic.Queue, { vm.playNext(listOf(song)); vm.notify("${song.title} plays next") },
            if (fav) "Unfavourite" else "Favourite", if (fav) Ic.Heart else Ic.HeartFilled, { vm.toggleFavorite(song.id) },
        ) { SongRowContent(song, playing, vm, number, onLongClick, onClick) }
    } else {
        SongRowContent(song, playing, vm, number, onLongClick, onClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRowContent(song: SongEntity, playing: Boolean, vm: MainViewModel, number: Int?, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    val c = P.colors
    val fg = if (playing) c.onAccent else c.text
    // Same press as buttons and cards (down instantly, bounce back), a little smaller for a full-width row.
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item))
            .background(if (playing) c.accent else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction, indication = androidx.compose.material3.ripple(),
                role = Role.Button, onClickLabel = "Play", onClick = onClick,
                onLongClickLabel = "More options", onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (number != null) {
            Text("$number", Modifier.padding(start = 6.dp), style = T.small.copy(fontSize = 14.sp), color = fg)
        } else {
            SongArt(vm, song, 52.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, style = T.title, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = T.small, color = if (playing) fg.copy(alpha = 0.8f) else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(song.durationMs), style = T.num, color = if (playing) fg else c.muted)
    }
}

/**
 * Long-press menu for a song, as an [ActionSheet]: quick actions on top, then what this place
 * allows (playlist or queue), then going places, the player's extras and the taste controls.
 * With [queueIndex] it acts on that queue entry instead (play it now, move it, remove it).
 */
@Composable
fun SongActionsDialog(
    vm: MainViewModel, song: SongEntity, onOpenAlbum: (Long) -> Unit, queueIndex: Int? = null,
    /** Set when opened from a playlist: (playlist id, position of this song in it). */
    inPlaylist: Pair<Long, Int>? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    /** Extra rows, shown as their own group (the player adds radio, speed, sleep, EQ and info). */
    extra: List<SheetAction> = emptyList(),
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val onlineNav = LocalOnlineNav.current
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val hidden by vm.hiddenIds.collectAsStateWithLifecycle()
    val snoozed by vm.snoozedIds.collectAsStateWithLifecycle()
    val excluded by vm.tasteExcluded.collectAsStateWithLifecycle()
    val shuffleOn = vm.now.collectAsStateWithLifecycle().value.shuffle
    val fav = song.id in favs

    val favAction = SheetAction(
        if (fav) "Favourited" else "Favourite", if (fav) Ic.HeartFilled else Ic.Heart, selected = fav,
    ) { vm.toggleFavorite(song.id) }
    val shareAction = SheetAction("Share", Ic.Share) { shareSong(ctx, song) }
    // Online songs: download to keep on the phone (the online build only).
    val online = song.volume == com.skarif.kairos.data.db.ONLINE_VOLUME && vm.onlineName != null
    val downloaded = vm.onlineUi.downloadedIds.collectAsStateWithLifecycle().value
    val downloading = vm.onlineUi.downloading.collectAsStateWithLifecycle().value
    val downloadAction = when {
        !online -> null
        song.id in downloaded -> SheetAction("Downloaded", Ic.Check, selected = true) {
            vm.onlineUi.removeDownload(song)
            vm.notify("Removed the download of ${song.title}")
        }
        song.id in downloading -> SheetAction("${(downloading.getValue(song.id) * 100).toInt()}%  Cancel", Ic.Close) { vm.onlineUi.cancelDownload(song) }
        else -> SheetAction("Download", Ic.DownArrow) { vm.onlineUi.download(listOf(song)) }
    }

    val quick = if (queueIndex != null) listOfNotNull(
        SheetAction("Play now", Ic.Play) { vm.playQueueItem(queueIndex) },
        // Reordering follows the unshuffled order, so it is only offered with shuffle off.
        if (!shuffleOn) SheetAction("Play next", Ic.Queue) { vm.moveToNext(queueIndex) } else null,
        favAction, shareAction,
    ) else listOfNotNull(
        SheetAction("Play next", Ic.Queue) { vm.playNext(listOf(song)); vm.notify("Plays next") },
        SheetAction("Add to queue", Ic.AddQueue) { vm.addToQueue(listOf(song)) },
        favAction, downloadAction ?: shareAction,
    )

    val here = buildList {
        if (inPlaylist != null) {
            val (pid, pos) = inPlaylist
            val size = playlists.firstOrNull { it.id == pid }?.songs?.size ?: 0
            if (pos > 0) add(SheetAction("Move up", Ic.Up) { vm.moveInPlaylist(pid, pos, pos - 1) })
            if (pos < size - 1) add(SheetAction("Move down", Ic.DownArrow) { vm.moveInPlaylist(pid, pos, pos + 1) })
            add(SheetAction("Remove from this playlist", Ic.Trash, destructive = true) { vm.removeFromPlaylist(pid, pos) })
        }
        if (queueIndex != null) {
            if (!shuffleOn) add(SheetAction("Move to end of queue", Ic.Queue) { vm.moveToEnd(queueIndex) })
            add(SheetAction("Remove from queue", Ic.Trash, destructive = true) { vm.removeQueueItem(queueIndex) })
        }
    }

    val go = buildList {
        add(addToPlaylistAction(vm, playlists, listOf(song.id)))
        // Online songs: Download takes Share's quick slot (in the queue menu it goes here instead).
        if (downloadAction != null) add(if (queueIndex == null) shareAction else downloadAction)
        if (song.volume == com.skarif.kairos.data.db.ONLINE_VOLUME) {
            // Online songs have no album or artist page in the library; find the artist online instead.
            com.skarif.kairos.data.splitArtists(song.artist).take(3).forEach { a ->
                add(SheetAction("Find $a", Ic.Person) { onlineNav.search(a, com.skarif.kairos.online.OnlineKind.ARTISTS) })
            }
        } else {
            add(SheetAction("Go to album", Ic.Disc) { onOpenAlbum(song.albumId) })
            if (onOpenArtist != null) com.skarif.kairos.data.splitArtists(song.artist).forEach { a ->
                add(SheetAction("Go to $a", Ic.Person) { onOpenArtist(a) })
            }
        }
    }

    // Like Spotify's hide and snooze: keeps the song out of mixes, shuffle-all and autoplay.
    val taste = buildList {
        if (song.id in hidden) add(SheetAction("Show in mixes again", Ic.EyeOff, selected = true) { vm.setHidden(song.id, false) })
        else add(SheetAction("Hide from mixes and autoplay", Ic.EyeOff) { vm.setHidden(song.id, true) })
        if (song.id in snoozed) add(SheetAction("Stop snoozing", Ic.Clock, selected = true) { vm.unsnooze(song.id) })
        else if (song.id !in hidden) add(SheetAction("Snooze for 30 days", Ic.Clock) { vm.snooze(song.id) })
        if (song.id in excluded) add(SheetAction("Use for recommendations again", Ic.Wave, selected = true) { vm.setTasteExcluded(song.id, false) })
        else add(SheetAction("Don't use for recommendations", Ic.Wave) { vm.setTasteExcluded(song.id, true) })
    }

    ActionSheet(
        title = song.title,
        subtitle = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
        art = { SongArt(vm, song, 52.dp, corner = 12.dp) },
        quick = quick,
        groups = listOf(here, extra, go, taste),
        onDismiss = onDismiss,
    )
}

/** "Add to playlist" with every playlist as a second page; the songs go to the end. */
fun addToPlaylistAction(
    vm: MainViewModel,
    playlists: List<com.skarif.kairos.ui.PlaylistUi>,
    songIds: List<Long>,
    exceptId: Long? = null,
) = SheetAction(
    "Add to playlist", Ic.PlaylistAdd,
    submenu = playlists.filter { it.id != exceptId }.map { p ->
        SheetAction(p.name, Ic.Music) {
            vm.addToPlaylistNamed(p.id, songIds)
        }
    },
)

@Composable
fun DialogItem(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.fillMaxWidth(), style = T.title, color = P.colors.text)
    }
}

/**
 * Room to leave at the bottom of a pushed page (not a tab) so its last item can scroll clear of
 * the mini player and its fade. 0 when no song is loaded. Provided by MainActivity.
 */
val LocalBottomClearance = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** The navigation bar's height, for lists that draw edge to edge instead of padding the screen. */
@Composable
fun navBarBottom(): Dp =
    androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Whether the full player is on screen (false while it is built but hidden). */
val LocalPlayerVisible = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> { androidx.compose.runtime.mutableStateOf(true) }

/**
 * Scrolls a long title, but only while the player is on screen. A marquee asks for a frame every
 * vsync; running it on the hidden, pre-built player kept the whole app drawing nonstop.
 */
@Composable
fun Modifier.liveMarquee(): Modifier =
    if (LocalPlayerVisible.current.value) basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2500) else this

/**
 * Playback position that moves every frame while playing. The player reports it every 200 ms;
 * in between it is extrapolated from the frame clock, so anything driven by it glides.
 */
@Composable
fun rememberLivePosition(vm: MainViewModel): androidx.compose.runtime.State<Long> {
    val reported = vm.position.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    // When the player last reported, in the frame clock's time base (System.nanoTime).
    val reportedAt = androidx.compose.runtime.remember { longArrayOf(System.nanoTime()) }
    LaunchedEffect(reported.value) { reportedAt[0] = System.nanoTime() }
    // The player is built ahead of time while hidden; don't run a per-frame clock for it then.
    val visible = LocalPlayerVisible.current.value
    return produceState(reported.value, now.isPlaying, visible) {
        if (!now.isPlaying || !visible) {
            androidx.compose.runtime.snapshotFlow { reported.value }.collect { value = it }
            return@produceState
        }
        // A steady clock that runs at real speed and is nudged gently towards the player's
        // reports, so it never stalls or jumps between them. Big gaps (a seek) snap.
        var shown = value.toDouble()
        var last: Long = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { t: Long ->
                shown += (t - last) / 1_000_000.0
                last = t
                val truth = reported.value + (t - reportedAt[0]) / 1_000_000.0
                val err = truth - shown
                shown = if (kotlin.math.abs(err) > 500) truth else shown + err * 0.06
                value = shown.toLong()
            }
        }
    }
}

/** Seek bar that reads the playback position itself, so only it redraws 5 times a second. */
@Composable
fun LiveWaveformSeek(vm: MainViewModel, durationMs: Long, seed: Long, modifier: Modifier = Modifier, barsHeight: Dp = 66.dp, bars: Int = 26) {
    val pos = rememberLivePosition(vm)
    val now by vm.now.collectAsStateWithLifecycle()
    // The bars move only while playing *and* on screen; otherwise their frame clock stops.
    val visible = LocalPlayerVisible.current.value
    com.skarif.kairos.ui.components.WaveformSeek(pos, durationMs, seed, vm::seekTo, modifier, barsHeight, bars, playing = now.isPlaying && visible)
}

@Composable
fun LiveTime(vm: MainViewModel, durationMs: Long, color: Color) {
    val pos by vm.position.collectAsStateWithLifecycle()
    Text("${formatTime(pos)} / ${formatTime(durationMs)}", style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = color)
}

/**
 * Swipe a row right or left to run an action; the row springs back afterwards. Uses the shared
 * swipe rule ([com.skarif.kairos.ui.motion.Swipe]): 72 dp or a quick flick, the same as closing
 * the player or swiping the cover. (Material's SwipeToDismissBox acted on the lightest flick.)
 * Also exposed as accessibility actions, since a swipe can't be done with TalkBack.
 */
@Composable
fun SwipeActions(
    rightLabel: String?,
    rightIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    onRight: (() -> Unit)?,
    leftLabel: String?,
    leftIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    onLeft: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val c = P.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val offset = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val distancePx = com.skarif.kairos.ui.motion.Swipe.DISTANCE_DP * density.density
    // A tick under the finger the moment the swipe passes the point where it will act.
    val armed = kotlin.math.abs(offset.value) >= distancePx
    LaunchedEffect(armed) { if (armed) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { d ->
        val next = offset.value + d
        // Only towards sides that have an action; resist past the commit point.
        val allowed = when {
            next > 0 && onRight == null -> 0f
            next < 0 && onLeft == null -> 0f
            kotlin.math.abs(next) > distancePx * 1.6f -> offset.value + d * 0.25f
            else -> next
        }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { offset.snapTo(allowed) }
    }
    Box(
        Modifier.semantics {
            customActions = listOfNotNull(
                if (onRight != null && rightLabel != null) androidx.compose.ui.semantics.CustomAccessibilityAction(rightLabel) { onRight(); true } else null,
                if (onLeft != null && leftLabel != null) androidx.compose.ui.semantics.CustomAccessibilityAction(leftLabel) { onLeft(); true } else null,
            )
        },
    ) {
        // The strip the row has uncovered, coloured, with the action's icon.
        val o = offset.value
        if (o != 0f) {
            val right = o > 0
            val strip = with(density) { kotlin.math.abs(o).toDp() }
            Box(Modifier.matchParentSize().padding(horizontal = 12.dp, vertical = 2.dp)) {
                val icon = if (right) rightIcon else leftIcon
                Box(
                    Modifier.align(if (right) Alignment.CenterStart else Alignment.CenterEnd)
                        .width(strip).fillMaxHeight().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item))
                        .background(if (right) c.accent else c.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    val iconScale by androidx.compose.animation.core.animateFloatAsState(
                        if (armed) 1.25f else (0.6f + kotlin.math.abs(o) / distancePx * 0.4f).coerceAtMost(1f),
                        androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "swipeIcon",
                    )
                    // Icon first; the action's name appears once there is room, so you know what a swipe will do.
                    val fg = if (right) c.onAccent else c.inkFg
                    val label = if (right) rightLabel else leftLabel
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (icon != null && strip > 36.dp) {
                            Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                        }
                        if (label != null && strip > 120.dp) Text(label, style = T.label.copy(fontSize = 13.sp), color = fg, maxLines = 1)
                    }
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .draggable(
                    dragState, androidx.compose.foundation.gestures.Orientation.Horizontal,
                    enabled = onRight != null || onLeft != null,
                    onDragStopped = { v ->
                        when (com.skarif.kairos.ui.motion.Swipe.decide(offset.value, v, density.density)) {
                            1 -> onRight?.invoke()
                            -1 -> onLeft?.invoke()
                        }
                        offset.animateTo(0f, com.skarif.kairos.ui.motion.Motion.spatial(), initialVelocity = v)
                    },
                ),
        ) { content() }
    }
}

/**
 * The message bar: what just happened, with Undo when it can be undone. Disappears after
 * 4 seconds (6 with Undo), or when swiped away or tapped.
 */
@Composable
fun NoticeBar(vm: MainViewModel, modifier: Modifier = Modifier) {
    val c = P.colors
    val notice by vm.notice.collectAsStateWithLifecycle()
    val n = notice
    LaunchedEffect(n?.id) {
        if (n != null) { kotlinx.coroutines.delay(if (n.undo != null) 6_000 else 4_000); vm.dismissNotice(n.id) }
    }
    androidx.compose.animation.AnimatedVisibility(
        n != null, modifier,
        enter = androidx.compose.animation.slideInVertically(com.skarif.kairos.ui.motion.Motion.spatial()) { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically(com.skarif.kairos.ui.motion.Motion.spatial()) { it } + androidx.compose.animation.fadeOut(),
    ) {
        // Keep showing the last message while it animates out.
        val shown = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(n) }
        if (n != null) shown.value = n
        val m = shown.value ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 18.dp).fillMaxWidth()
                .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(c.ink)
                .clickable(onClickLabel = "Dismiss") { vm.dismissNotice(m.id) }
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                .androidx_semantics_live(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(m.text, Modifier.weight(1f).padding(vertical = 10.dp), style = T.body, color = c.inkFg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val undo = m.undo
            if (undo != null) {
                TextButton(onClick = { undo(); vm.dismissNotice(m.id) }) {
                    Text("Undo", style = T.label, color = c.accent)
                }
            }
        }
    }
}

/** Screen readers announce the message when it appears. */
private fun Modifier.androidx_semantics_live(): Modifier =
    semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }

/**
 * Opens the menu for a collection key ("album:12", "artist:Name", "genre:Pop", "playlist:3", a mix).
 * Provided once at the app root, so every tile in the app gets a long-press menu without each
 * screen wiring its own.
 */
/** Opens online pages (online flavor); set by MainActivity. */
class OnlineNav(
    val search: (query: String, kind: com.skarif.kairos.online.OnlineKind) -> Unit = { _, _ -> },
    val collection: (id: String) -> Unit = {},
    val artist: (id: String, name: String) -> Unit = { _, _ -> },
)

val LocalOnlineNav = androidx.compose.runtime.staticCompositionLocalOf { OnlineNav() }

val LocalCollectionMenu = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The menu for an album, artist, genre, playlist or mix: play, shuffle, queue, add to a playlist,
 * go to the artist; for playlists also rename and delete (with their own dialogs).
 * [extra] adds rows (the playlist page adds cover and export). [showOpen] adds "Open" for tiles.
 */
@Composable
fun CollectionActionsSheet(
    vm: MainViewModel,
    key: String,
    onOpenCollection: (String) -> Unit,
    onDismiss: () -> Unit,
    showOpen: Boolean = true,
    extra: List<SheetAction> = emptyList(),
    onDeleted: () -> Unit = {},
) {
    val c = P.colors
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val col = androidx.compose.runtime.remember(key, playlists) { vm.collection(key) }
    if (col == null || col.songs.isEmpty() && col.playlistId == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }; return }
    val songs = col.songs
    val pid = col.playlistId
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var deleting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    when {
        renaming && pid != null -> {
            var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(col.title) }
            KairosDialog(
                onDismissRequest = onDismiss,
                containerColor = c.sheet,
                title = { Text("Rename playlist", style = T.h2, color = c.text) },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        name, { name = it }, singleLine = true, textStyle = T.body.copy(color = c.text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = { Pill("Save", { if (name.isNotBlank()) vm.renamePlaylist(pid, name.trim()); onDismiss() }, selected = true) },
                dismissButton = { Pill("Cancel", onDismiss) },
            )
        }
        deleting && pid != null -> ConfirmDialog(
            "Delete ${col.title}?", "The songs stay on your phone. Only the playlist is removed.",
            confirm = "Delete", dismiss = "Keep",
            onConfirm = { vm.deletePlaylist(pid); onDismiss(); onDeleted() }, onDismiss = onDismiss,
        )
        else -> {
            val isArtist = key.startsWith("artist:")
            val quick = listOf(
                SheetAction("Play", Ic.Play) { vm.play(songs, 0, col.title) },
                SheetAction("Shuffle", Ic.Shuffle) { vm.shuffle(songs, from = col.title) },
                SheetAction("Play next", Ic.Queue) {
                    vm.playNext(songs); vm.notify(if (songs.size == 1) "Plays next" else "${songs.size} songs play next")
                },
                SheetAction("Add to queue", Ic.AddQueue) { vm.addToQueue(songs) },
            )
            val go = buildList {
                if (showOpen) add(SheetAction("Open", Ic.Chevron) { onOpenCollection(key) })
                add(addToPlaylistAction(vm, playlists, songs.map { it.id }, exceptId = pid))
                if (key.startsWith("album:")) {
                    val first = songs.first()
                    val albumArtist = vm.albumGroup(first.albumId)?.artist ?: first.artist
                    com.skarif.kairos.data.splitArtists(albumArtist).forEach { a ->
                        add(SheetAction("Go to $a", Ic.Person) { onOpenCollection("artist:$a") })
                    }
                }
            }
            val manage = if (pid == null) emptyList() else listOf(
                SheetAction("Rename", Ic.Edit, keepOpen = true) { renaming = true },
                SheetAction("Delete playlist", Ic.Trash, destructive = true, keepOpen = true) { deleting = true },
            )
            ActionSheet(
                title = col.title,
                subtitle = col.subtitle,
                art = { songs.firstOrNull()?.let { SongArt(vm, it, 52.dp, corner = if (isArtist) 26.dp else 12.dp) } },
                quick = if (songs.isEmpty()) emptyList() else quick,
                groups = listOf(go, extra, manage),
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * The skeleton of a pushed, scrolling page (Settings, Music folders): the album-art background,
 * iOS scrolling, status and navigation bar insets, page padding, and room for the mini player.
 */
@Composable
fun PageColumn(spacing: Dp = 18.dp, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ScreenBackground {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(androidx.compose.foundation.rememberScrollState(), flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling())
                .statusBarsPadding().navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 40.dp + LocalBottomClearance.current),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/** Gutters for a two-column grid item: the outer edge of each column gets the page margin. */
fun Modifier.gridGutter(index: Int): Modifier =
    padding(start = if (index % 2 == 0) 18.dp else 0.dp, end = if (index % 2 == 1) 18.dp else 0.dp)

/** A yes/no dialog: a title, an explanation, a confirm pill and a cancel pill. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
) {
    val c = P.colors
    KairosDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text(title, style = T.h2, color = c.text) },
        text = { Text(text, style = T.body, color = c.muted) },
        confirmButton = { Pill(confirm, onConfirm, selected = true) },
        dismissButton = { Pill(dismiss, onDismiss) },
    )
}
