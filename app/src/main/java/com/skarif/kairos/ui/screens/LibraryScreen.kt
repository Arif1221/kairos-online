package com.skarif.kairos.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.SongSort
import com.skarif.kairos.ui.motion.Motion
import com.skarif.kairos.ui.components.headerPanel
import com.skarif.kairos.ui.components.stretchTitle
import com.skarif.kairos.data.matchesQuery
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.ScanState
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.cardClick
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import kotlinx.coroutines.launch
import java.text.NumberFormat

private class LetterRun(val letter: Char?, val start: Int, val songs: List<SongEntity>)

private enum class LibTab(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), GENRES("Genres") }

/** Items above the first song/album/artist in the grid. */
private const val HEADER_ITEMS = 2

private fun letterOf(s: SongEntity, sort: SongSort, ignoreArticles: Boolean = false, keys: Map<String, String> = emptyMap()): Char {
    val raw = when (sort) {
        SongSort.ARTIST -> s.artist
        SongSort.ALBUM -> s.album
        else -> s.title
    }
    // Reuse the key the ViewModel already worked out off the main thread ("0…" = #, "1x…" = X).
    val k = keys[raw] ?: return com.skarif.kairos.data.libraryLetter(raw, ignoreArticles)
    return if (k.startsWith("0") || k.length < 2) '#' else k[1].uppercaseChar().takeIf { it in 'A'..'Z' } ?: '#'
}

/**
 * Design reference: the finance "history" screen, a dark panel with a big number over a list.
 * Tabs for songs / albums / artists, an A-Z scroller for long lists, and a compact bar that
 * slides in once the big header has scrolled away.
 */
@Composable
fun LibraryScreen(
    vm: MainViewModel,
    onOpenNowPlaying: () -> Unit,
    onSettings: () -> Unit,
    onFolders: () -> Unit,
    onSort: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenCollection: (String) -> Unit,
    onPlaylists: () -> Unit = {},
    onDownloads: () -> Unit = {},
) {
    val c = P.colors
    val menu = LocalCollectionMenu.current
    val songs by vm.sortedSongs.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    // Collected once here, not once per row.
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val sortKeys by vm.sortKeys.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibTab.SONGS) }
    var actionsFor by remember { mutableStateOf<SongEntity?>(null) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val fmt = remember { NumberFormat.getIntegerInstance() }

    var filter by rememberSaveable { mutableStateOf("") }
    val allAlbums by vm.albums.collectAsStateWithLifecycle()
    val allArtists by vm.artists.collectAsStateWithLifecycle()
    val allGenres by vm.genres.collectAsStateWithLifecycle()
    val allSongs = songs
    // The filter box filters whichever tab is open; every word must match, accents ignored.
    // Matching runs in the ViewModel off the main thread, on a pre-built index, after a short pause.
    val hits by vm.libraryHits.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(filter) { vm.setLibraryFilter(filter) }
    val h = hits?.takeIf { filter.isNotBlank() }
    androidx.compose.runtime.LaunchedEffect(h) { if (h != null && grid.firstVisibleItemIndex > 1) grid.scrollToItem(1) }
    val shownSongs = remember(allSongs, h) { h?.let { x -> allSongs.filter { it.id in x.songs } } ?: allSongs }
    val albums = remember(allAlbums, h) { h?.let { x -> allAlbums.filter { it.id in x.albums } } ?: allAlbums }
    val artists = remember(allArtists, h) { h?.let { x -> allArtists.filter { it.name in x.artists } } ?: allArtists }
    val genres = remember(allGenres, h) { h?.let { x -> allGenres.filter { it.name in x.genres } } ?: allGenres }
    val count = when (tab) { LibTab.SONGS -> shownSongs.size; LibTab.ALBUMS -> albums.size; LibTab.ARTISTS -> artists.size; LibTab.GENRES -> genres.size }
    val noun = when (tab) { LibTab.SONGS -> "songs"; LibTab.ALBUMS -> "albums"; LibTab.ARTISTS -> "artists"; LibTab.GENRES -> "genres" }
    val summary = remember(songs) {
        val gb = songs.sumOf { it.sizeBytes } / 1_000_000_000.0
        val mins = songs.sumOf { it.durationMs } / 60_000
        "${mins / 60} hours of music, " + String.format("%.1f", gb) + " GB on this phone"
    }
    val collapsed by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    val letterSorted = settings.songSort in setOf(SongSort.TITLE, SongSort.ARTIST, SongSort.ALBUM)
    // Songs sorted by title/artist/album are shown in runs under a letter header ("A", "B", ...).
    val runs = remember(shownSongs, settings.songSort, settings.ignoreArticles, filter, sortKeys) {
        if (!letterSorted || filter.isNotBlank()) listOf(LetterRun(null, 0, shownSongs))
        else {
            val out = ArrayList<LetterRun>()
            var start = 0
            while (start < shownSongs.size) {
                val l = letterOf(shownSongs[start], settings.songSort, settings.ignoreArticles, sortKeys)
                var end = start + 1
                while (end < shownSongs.size && letterOf(shownSongs[end], settings.songSort, settings.ignoreArticles, sortKeys) == l) end++
                out += LetterRun(l, start, shownSongs.subList(start, end))
                start = end
            }
            out
        }
    }
    // A-Z order with # first, each pointing at the grid row of its first header.
    val letters = remember(runs) {
        var row = HEADER_ITEMS
        runs.map { r -> val at = row; row += r.songs.size + (if (r.letter != null) 1 else 0); r.letter to at }
            .mapNotNull { (l, at) -> l?.let { it to at } }.distinctBy { it.first }
            .sortedBy { if (it.first == '#') ' ' else it.first }
    }

    actionsFor?.let { s -> SongActionsDialog(vm, s, onOpenAlbum) { actionsFor = null } }
    // Header panel follows the theme: frosted light glass by day, dark glass by night.
    val panel = c.panel
    val chip = c.chip
    val fg = c.text
    val soft = c.muted

    val band = com.skarif.kairos.ui.motion.rememberRubberBand()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.tabReselected.collect { if (it == "library") grid.animateScrollToItem(0) }
    }

    ScreenBackground {
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.fillMaxSize(),
            state = grid,
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            overscrollEffect = band,
            contentPadding = PaddingValues(bottom = 230.dp),
            // Build rows a screen ahead while idle (pausable composition spreads it over frames).
            cacheWindow = androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(ahead = 480.dp, behind = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(if (tab == LibTab.ALBUMS || tab == LibTab.GENRES) 10.dp else 0.dp),
        ) {
            item(span = { GridItemSpan(2) }, key = "header") {
                Column(
                    Modifier.headerPanel { band.pull },
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircleButton(Ic.Folder, "Music folders", onFolders, container = chip, content = fg)
                        Text("Library", Modifier.weight(1f), color = fg, style = T.title, textAlign = TextAlign.Center)
                        CircleButton(Ic.Settings, "Settings", onSettings, container = chip, content = fg)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Your collection", color = soft, style = T.body)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${fmt.format(count)} $noun", Modifier.weight(1f).stretchTitle { band.pull }, color = fg, style = T.big)
                            CircleButton(Ic.Refresh, "Scan again", vm::scanNow, size = 44.dp, container = Color.Transparent, content = fg)
                        }
                        Text(
                            if (scan == ScanState.Scanning) "Scanning your music folders…" else summary,
                            color = soft, style = T.small,
                        )
                    }
                    SearchField(filter, { filter = it }, "Filter ${tab.label.lowercase()}", container = chip, height = 52.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(chip).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LibTab.entries.forEach { t ->
                            val on = t == tab
                            Box(
                                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp))
                                    .background(if (on) c.ink else Color.Transparent)
                                    .selectable(on, role = Role.Tab) {
                                        tab = t
                                        scope.launch { grid.scrollToItem(0) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(t.label, style = T.label.copy(fontSize = 13.sp), maxLines = 1, color = if (on) c.inkFg else fg)
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(2) }, key = "actions") {
                // A grid item lays its children out in a Box: without this Column the tiles and the
                // Shuffle row were drawn on top of each other.
                Column {
                // Online build: everything that's yours, streamed or not, one tap away. Inside this
                // item so the A-Z scroller's item count (HEADER_ITEMS) stays the same.
                if (vm.onlineName != null) Column {
                    com.skarif.kairos.ui.components.HintCard(
                        "library", "Everything that's yours",
                        listOf(
                            "The tiles hold your favourites, playlists, downloads, what you played lately, and the artists and albums you liked online.",
                            "Songs, Albums, Artists and Genres below are the music files on this phone, including songs you saved to the phone.",
                        ),
                        Modifier.padding(top = 18.dp),
                    )
                    YoursTiles(vm, onOpenCollection, onPlaylists, onDownloads)
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("Shuffle", { vm.shuffleAll(shownSongs); onOpenNowPlaying() }, accent = true, icon = Ic.Shuffle)
                    if (tab == LibTab.SONGS) {
                        Pill(settings.songSort.label, onSort, icon = Ic.Sort)
                    }
                    Spacer(Modifier.weight(1f))
                }
                }
            }
            // Mood by colour: shuffle songs whose cover is mostly this colour.
            if (tab == LibTab.SONGS) item(span = { GridItemSpan(2) }, key = "colours") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Play by colour", Modifier.weight(1f), style = T.small, color = c.muted)
                    com.skarif.kairos.data.CoverColour.entries.forEach { col ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(Color(col.swatch))
                                .clickable(role = Role.Button, onClickLabel = "Shuffle ${col.label.lowercase()} covers") {
                                    // The player stays closed: it may take a moment, or there may be too few.
                                    vm.playByColour(col)
                                },
                        )
                    }
                }
            }

            when (tab) {
                LibTab.SONGS -> {
                    if (shownSongs.isEmpty() && filter.isNotBlank()) {
                        item(span = { GridItemSpan(2) }) { Text("Nothing matches \"$filter\".", Modifier.padding(18.dp), style = T.body, color = c.muted) }
                    } else if (shownSongs.isEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                val canRead = hasAudioPermission(ctx)
                                Text(
                                    when {
                                        scan == ScanState.Scanning -> "Looking for songs…"
                                        !canRead -> "Kairos can't see the music on this phone yet. Allow access in Android settings to play your own songs here too."
                                        else -> "No songs found. Check which folders are included, or copy music to this phone and scan again."
                                    },
                                    style = T.body, color = c.muted,
                                )
                                if (!canRead) Pill("Open settings", {
                                    runCatching {
                                        ctx.startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                                .setData(android.net.Uri.fromParts("package", ctx.packageName, null)),
                                        )
                                    }
                                }, selected = true, icon = Ic.Settings)
                                else if (scan != ScanState.Scanning) Pill("Choose folders", onFolders, selected = true, icon = Ic.Folder)
                            }
                        }
                    }
                    runs.forEach { run ->
                        if (run.letter != null) {
                            item(span = { GridItemSpan(2) }, key = "h${run.start}", contentType = "letter") {
                                Text(
                                    "${run.letter}", Modifier.padding(start = 22.dp, top = 14.dp, bottom = 4.dp),
                                    style = T.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = c.accent,
                                )
                            }
                        }
                        itemsIndexed(run.songs, key = { _, s -> s.id }, span = { _, _ -> GridItemSpan(2) }, contentType = { _, _ -> "song" }) { i, s ->
                            // Rows glide into place when the filter or sort changes, instead of jumping.
                            Box(Modifier.padding(end = if (letters.isNotEmpty()) 22.dp else 0.dp)) {
                                SongRow(s, playing = s.id == now.songId, vm, favourite = s.id in favs, onLongClick = { actionsFor = s }) {
                                    vm.play(shownSongs, run.start + i, "Your library")
                                    onOpenNowPlaying()
                                }
                            }
                        }
                    }
                }
                LibTab.ALBUMS -> {
                    itemsIndexed(albums, key = { _, g -> "al" + g.id }, contentType = { _, _ -> "album" }) { i, g ->
                        Box(Modifier.gridGutter(i)) {
                            CoverTile(vm, g.songs.first(), key = "album:${g.id}", kicker = g.artist, title = g.title) { onOpenCollection("album:${g.id}") }
                        }
                    }
                }
                LibTab.ARTISTS -> {
                    items(artists, key = { "ar" + it.name.lowercase() }, span = { GridItemSpan(2) }, contentType = { "artist" }) { a ->
                        val name = a.name
                        val list = a.songs
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                                .cardClick("Open $name", onLongClick = { menu("artist:$name") }) { onOpenCollection("artist:$name") }
                                .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            SongArt(vm, list.first(), 52.dp, corner = 26.dp)
                            Column(Modifier.weight(1f)) {
                                Text(name, style = T.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${list.size} songs, ${a.albums} albums", style = T.small, color = c.muted)
                            }
                            CircleButton(Ic.Play, "Play $name", { vm.play(list, 0, name); onOpenNowPlaying() }, size = 44.dp, container = c.card)
                        }
                    }
                }
                LibTab.GENRES -> {
                    if (genres.isEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Text(
                                "No genre tags found in your music. Genres come from the files' tags.",
                                Modifier.padding(18.dp), style = T.body, color = c.muted,
                            )
                        }
                    }
                    itemsIndexed(genres, key = { _, g -> "ge" + g.name.lowercase() }, contentType = { _, _ -> "genre" }) { i, g ->
                        Box(Modifier.gridGutter(i)) {
                            CoverTile(vm, g.songs.first(), key = "genre:${g.name}", kicker = "${g.songs.size} songs", title = g.name) {
                                onOpenCollection("genre:${g.name}")
                            }
                        }
                    }
                }
            }
        }

        // Compact bar once the big header is gone: count, sort, and back to top.
        AnimatedVisibility(
            collapsed,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(tween(260)) { -it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().background(panel).statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Library", style = T.title, color = fg)
                    Text("${fmt.format(count)} $noun", style = T.small, color = soft)
                }
                if (tab == LibTab.SONGS) CircleButton(Ic.Sort, "Sort songs", onSort, size = 44.dp, container = chip, content = fg)
                CircleButton(Ic.Up, "Back to top", { scope.launch { grid.animateScrollToItem(0) } }, size = 44.dp, container = c.ink, content = c.inkFg)
            }
        }

        if (tab == LibTab.SONGS && letters.size > 3 && shownSongs.size > 40) {
            FastScroller(
                letters = letters.map { it.first },
                onPick = { idx -> scope.launch { grid.scrollToItem(letters[idx].second) } },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp, top = 120.dp, bottom = 200.dp),
            )
        }
    }
}

/** A-Z strip: tap or drag to jump; a bubble shows the letter under the finger. */
@Composable
private fun FastScroller(letters: List<Char>, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = P.colors
    val haptics = LocalHapticFeedback.current
    var active by remember { mutableStateOf(-1) }
    var heightPx by remember { mutableStateOf(1) }
    fun pick(y: Float) {
        val i = ((y / heightPx) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (i != active) {
            active = i
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPick(i)
        }
    }
    Box(modifier) {
        Column(
            Modifier
                .width(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (active >= 0) c.card.copy(alpha = 0.9f) else Color.Transparent)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { pick(it.y) },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                    ) { change, _ -> change.consume(); pick(change.position.y) }
                }
                .pointerInput(letters) { detectTapGestures(onPress = { pick(it.y); tryAwaitRelease(); active = -1 }) }
                .semantics { contentDescription = "Jump to letter" }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEachIndexed { i, l ->
                Text(
                    "$l", style = T.small.copy(fontSize = 10.sp, fontWeight = if (i == active) FontWeight.ExtraBold else FontWeight.SemiBold, lineHeight = 13.sp),
                    color = if (i == active) c.accent else c.muted,
                )
            }
        }
        AnimatedVisibility(
            active >= 0,
            modifier = Modifier.align(Alignment.CenterStart).offset { IntOffset(-90.dp.roundToPx(), 0) },
            enter = scaleIn(tween(120)) + fadeIn(), exit = scaleOut(tween(120)) + fadeOut(),
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Text("${letters.getOrElse(active.coerceAtLeast(0)) { ' ' }}", style = T.big.copy(fontSize = 30.sp), color = c.onAccent)
            }
        }
    }
}

/** Online build: Favourites, Playlists, Downloads and Recently played as tiles at the top of Library. */
@Composable
private fun YoursTiles(vm: MainViewModel, onOpenCollection: (String) -> Unit, onPlaylists: () -> Unit, onDownloads: () -> Unit) {
    val c = P.colors
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val downloads by vm.onlineUi.downloadedIds.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val recent = mixes.firstOrNull { it.key == "lastplayed" }?.songs?.size ?: 0
    val artists by vm.onlineUi.likedArtists.collectAsStateWithLifecycle()
    val albums by vm.onlineUi.likedCollections.collectAsStateWithLifecycle()
    val tiles = listOf(
        Triple(Ic.HeartFilled, "Favourites", "${favs.size} songs") to { onOpenCollection("favourites") },
        Triple(Ic.Queue, "Playlists", "${playlists.size}") to onPlaylists,
        Triple(Ic.Person, "Artists", "${artists.size} liked") to { onOpenCollection("online:artists") },
        Triple(Ic.Disc, "Albums", "${albums.size} liked") to { onOpenCollection("online:albums") },
        Triple(Ic.DownArrow, "Downloads", "${downloads.size} songs") to onDownloads,
        Triple(Ic.Clock, "Recently played", "$recent songs") to { onOpenCollection("lastplayed") },
    )
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (t, go) ->
                    val (icon, label, count) = t
                    Row(
                        Modifier.weight(1f).cardClick("Open $label", onClick = go)
                            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(c.card).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(label, style = T.title.copy(fontSize = 15.sp), color = c.text, maxLines = 1)
                            Text(count, style = T.small, color = c.muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
