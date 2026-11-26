package com.skarif.kairos.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.online.OnlineArtist
import com.skarif.kairos.online.OnlineCollection
import com.skarif.kairos.online.OnlineItem
import com.skarif.kairos.online.OnlineKind
import com.skarif.kairos.ui.EXPLORE_MOODS
import com.skarif.kairos.ui.ExploreState
import com.skarif.kairos.ui.Loadable
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.Shelf
import com.skarif.kairos.ui.ShelfStyle
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.CompactTitleBar
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.TabHeader
import com.skarif.kairos.ui.components.pressScale
import com.skarif.kairos.ui.components.stretchTitle
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import com.skarif.kairos.ui.theme.mixedHeadline
import kotlinx.coroutines.launch

// ---- Building blocks ------------------------------------------------------------------------

/** Art from a URL (online albums, playlists, artists), cached with the rest of the art. */
@Composable
fun RemoteArt(vm: MainViewModel, url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 16.dp, circle: Boolean = false) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtMost(720)
    val bmp by produceState<Bitmap?>(url?.let { vm.cachedRemoteArt(it, px) }, url, px) {
        if (value == null && url != null) value = vm.remoteArt(url, px)
    }
    val shape = if (circle) CircleShape else RoundedCornerShape(corner)
    val img = bmp
    if (img != null) {
        Image(remember(img) { img.asImageBitmap() }, null, modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(size).clip(shape).background(P.colors.tint), contentAlignment = Alignment.Center) {
            Icon(if (circle) Ic.Person else Ic.Music, null, tint = P.colors.muted, modifier = Modifier.size(size / 2.6f))
        }
    }
}

@Composable
private fun ItemArt(vm: MainViewModel, item: OnlineItem, size: Dp, corner: Dp = 16.dp) = when (item) {
    is OnlineItem.Track -> SongArt(vm, item.song, size, corner = corner, large = size > 100.dp)
    is OnlineItem.Artist -> RemoteArt(vm, item.art, size, circle = true)
    is OnlineItem.Collection -> RemoteArt(vm, item.art, size, corner = corner)
}

private fun OnlineItem.title() = when (this) {
    is OnlineItem.Track -> song.title
    is OnlineItem.Artist -> name
    is OnlineItem.Collection -> title
}

private fun OnlineItem.subtitle() = when (this) {
    is OnlineItem.Track -> song.artist
    is OnlineItem.Artist -> detail ?: "Artist"
    is OnlineItem.Collection -> listOfNotNull(if (isAlbum) "Album" else "Playlist", owner, count.takeIf { it > 0 }?.let { "$it songs" })
        .joinToString(" · ")
}

/**
 * What a tap does: a song plays with its radio after it (like YouTube Music), an artist,
 * album or playlist opens its page.
 */
private fun open(vm: MainViewModel, item: OnlineItem, onOpenNowPlaying: () -> Unit, nav: com.skarif.kairos.ui.screens.OnlineNav) {
    when (item) {
        is OnlineItem.Track -> { vm.startRadio(item.song); onOpenNowPlaying() }
        is OnlineItem.Artist -> nav.artist(item.id, item.name)
        is OnlineItem.Collection -> nav.collection(item.id)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnlineCard(vm: MainViewModel, item: OnlineItem, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    val c = P.colors
    val round = item is OnlineItem.Artist
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.width(150.dp).pressScale(interaction, 0.96f).clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                interactionSource = interaction, indication = androidx.compose.material3.ripple(),
                role = Role.Button, onClickLabel = "Open ${item.title()}", onClick = onClick,
                onLongClickLabel = if (onLongClick != null) "More options" else null, onLongClick = onLongClick,
            ),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ItemArt(vm, item, 150.dp, corner = 20.dp)
        Text(
            item.title(), Modifier.padding(horizontal = 4.dp), style = T.title.copy(fontSize = 15.sp), color = c.text,
            maxLines = if (round) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start,
        )
        if (!round) Text(item.subtitle(), Modifier.padding(horizontal = 4.dp), style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A list row for artists, albums and playlists (songs use [SongRow]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnlinePageRow(vm: MainViewModel, item: OnlineItem, onClick: () -> Unit) {
    val c = P.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item))
            .combinedClickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ItemArt(vm, item, 56.dp, corner = 14.dp)
        Column(Modifier.weight(1f)) {
            Text(item.title(), style = T.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle(), style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.Next, null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Status(text: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = T.body, color = P.colors.muted)
        action?.let { (label, go) -> Pill(label, go, icon = Ic.Refresh) }
    }
}

/** Grey placeholder cards while a shelf loads; no spinner, no per-frame animation. */
@Composable
private fun SkeletonShelf() {
    val c = P.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(horizontal = 18.dp).size(160.dp, 22.dp).clip(RoundedCornerShape(8.dp)).background(c.tint))
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { Box(Modifier.size(150.dp).clip(RoundedCornerShape(20.dp)).background(c.tint)) }
        }
    }
}

@Composable
private fun ShelfTitle(title: String, subtitle: String?) {
    Column(Modifier.padding(horizontal = 18.dp)) {
        subtitle?.let { Text(it.uppercase(), style = T.small.copy(fontSize = 11.sp, letterSpacing = 1.sp), color = P.colors.muted) }
        Text(title, style = T.h2, color = P.colors.text)
    }
}

/**
 * Quick picks: songs in columns of four rows, swiped sideways, like YouTube Music.
 * Tapping one plays the picks from there.
 */
@Composable
private fun PicksShelf(vm: MainViewModel, shelf: Shelf, nowId: Long?, onSong: (SongEntity) -> Unit, onPlay: (List<SongEntity>, Int) -> Unit) {
    val songs = remember(shelf) { shelf.items.filterIsInstance<OnlineItem.Track>().map { it.song } }
    val columns = remember(songs) { songs.chunked(4) }
    val c = P.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = 18.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { ShelfTitle(shelf.title, shelf.subtitle) }
            Pill("Play all", { onPlay(songs, 0) }, icon = Ic.Play)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
            itemsIndexed(columns, key = { i, _ -> "pc$i" }) { col, group ->
                Column(Modifier.width(310.dp)) {
                    group.forEachIndexed { row, s ->
                        val interaction = remember { MutableInteractionSource() }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).pressScale(interaction, 0.97f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (s.id == nowId) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                                .combinedClickableCompat(interaction, "Play ${s.title}", { onSong(s) }) { onPlay(songs, col * 4 + row) }
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SongArt(vm, s, 50.dp, corner = 12.dp)
                            Column(Modifier.weight(1f)) {
                                val fg = if (s.id == nowId) c.onAccent else c.text
                                Text(s.title, style = T.title.copy(fontSize = 15.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist, style = T.small, color = if (s.id == nowId) fg.copy(alpha = 0.8f) else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(interaction: MutableInteractionSource, label: String, onLongClick: () -> Unit, onClick: () -> Unit) =
    combinedClickable(
        interactionSource = interaction, indication = null, role = Role.Button,
        onClickLabel = label, onClick = onClick, onLongClickLabel = "More options", onLongClick = onLongClick,
    )

@Composable
private fun CardShelf(vm: MainViewModel, title: String, subtitle: String?, items: List<OnlineItem>, onSong: (SongEntity) -> Unit, onOpen: (OnlineItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShelfTitle(title, subtitle)
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { item ->
                OnlineCard(vm, item, onLongClick = (item as? OnlineItem.Track)?.let { t -> { onSong(t.song) } }) { onOpen(item) }
            }
        }
    }
}

// ---- Explore --------------------------------------------------------------------------------

@Composable
fun ExploreScreen(vm: MainViewModel, onMenu: () -> Unit, onOpenNowPlaying: () -> Unit) {
    val o = vm.onlineUi
    val c = P.colors
    val nav = LocalOnlineNav.current
    val uri = LocalUriHandler.current
    val state by o.explore.collectAsStateWithLifecycle()
    val up by o.networkUp.collectAsStateWithLifecycle()
    val recent by o.recentlyStreamed.collectAsStateWithLifecycle()
    val offline by o.availableOffline.collectAsStateWithLifecycle()
    val update by o.update.collectAsStateWithLifecycle()
    val moodIndex by o.moodIndex.collectAsStateWithLifecycle()
    val mood by o.mood.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<SongEntity?>(null) }
    val list = rememberLazyListState()
    val band = com.skarif.kairos.ui.motion.rememberRubberBand()
    val scope = rememberCoroutineScope()
    val pastTitle by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }

    LaunchedEffect(Unit) { o.loadExplore() }
    LaunchedEffect(Unit) { vm.tabReselected.collect { if (it == "discover") list.animateScrollToItem(0) } }

    fun play(songs: List<SongEntity>, i: Int, from: String) { vm.play(songs, i, from); onOpenNowPlaying() }
    val openItem: (OnlineItem) -> Unit = { open(vm, it, onOpenNowPlaying, nav) }

    ScreenBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            overscrollEffect = band,
            contentPadding = PaddingValues(bottom = 230.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            item(key = "header") {
                TabHeader(
                    "Explore",
                    left = { chip -> CircleButton(Ic.Menu, "Menu", onMenu, container = chip, content = c.text) },
                    right = { chip -> CircleButton(Ic.Search, "Search", { nav.search("", OnlineKind.SONGS) }, container = chip, content = c.text) },
                    pull = { band.pull },
                ) {
                    Text(mixedHeadline("Explore " to false, "something new" to true), Modifier.stretchTitle { band.pull }, style = T.h1.copy(fontSize = 34.sp, lineHeight = 38.sp), color = c.text)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("For you", { if (moodIndex >= 0) o.selectMood(moodIndex) }, selected = moodIndex < 0, icon = Ic.Discover)
                        EXPLORE_MOODS.forEachIndexed { i, (label, _) -> Pill(label, { o.selectMood(i) }, selected = moodIndex == i) }
                    }
                }
            }
            (update as? com.skarif.kairos.ui.UpdateState.Available)?.let { u ->
                item(key = "update") {
                    Banner("Kairos Online ${u.version} is out", "Fixes and new features. Installs over this version.") {
                        Pill("Get it", { uri.openUri(u.url) }, selected = true)
                    }
                }
            }
            if (!up) item(key = "offline") {
                Banner(
                    "You're offline",
                    if (offline.isEmpty()) "Songs you stream are saved on this phone and play here without a connection."
                    else "${offline.size} streamed ${if (offline.size == 1) "song plays" else "songs play"} without a connection.",
                )
            }
            if (!up && offline.isNotEmpty()) item(key = "saved") {
                CardShelf(vm, "Saved on this phone", null, offline.map(OnlineItem::Track), { actionsFor = it }) { item ->
                    (item as? OnlineItem.Track)?.let { t -> play(offline, offline.indexOf(t.song), "Saved on this phone") }
                }
            }
            if (moodIndex >= 0) {
                when (val m = mood) {
                    is Loadable.Ready -> {
                        if (m.value.playlists.isNotEmpty()) item(key = "mood-lists") {
                            CardShelf(vm, "${m.value.label} playlists", null, m.value.playlists, { }, openItem)
                        }
                        if (m.value.songs.isNotEmpty()) {
                            item(key = "mood-songs-h") {
                                Row(Modifier.fillMaxWidth().padding(end = 18.dp), verticalAlignment = Alignment.Bottom) {
                                    Box(Modifier.weight(1f)) { ShelfTitle("${m.value.label} songs", null) }
                                    Pill("Shuffle", { vm.shuffle(m.value.songs, smart = false, from = m.value.label); onOpenNowPlaying() }, icon = Ic.Shuffle)
                                }
                            }
                            itemsIndexed(m.value.songs, key = { _, s -> "ms${s.id}" }) { i, s ->
                                SongRow(s, s.id == now.songId, vm, onLongClick = { actionsFor = s }) { play(m.value.songs, i, m.value.label) }
                            }
                        }
                    }
                    is Loadable.Failed -> item(key = "mood-fail") { Status(m.message, "Try again" to { o.retryMood() }) }
                    else -> items(2, key = { "mood-sk$it" }) { SkeletonShelf() }
                }
            } else {
                when (val st = state) {
                    ExploreState.Loading -> items(3, key = { "sk$it" }) { SkeletonShelf() }
                    is ExploreState.Failed -> if (up || offline.isEmpty()) item(key = "fail") { Status(st.message, "Try again" to { o.loadExplore(force = true) }) }
                    is ExploreState.Ready -> {
                        if (st.refreshing) item(key = "refreshing") { Status("Updating for you...") }
                        st.shelves.forEachIndexed { i, shelf ->
                            item(key = "shelf:${shelf.key}") {
                                when (shelf.style) {
                                    ShelfStyle.PICKS -> PicksShelf(vm, shelf, now.songId, { actionsFor = it }) { songs, at -> play(songs, at, shelf.title) }
                                    else -> CardShelf(vm, shelf.title, shelf.subtitle, shelf.items, { actionsFor = it }, openItem)
                                }
                            }
                            // Your recent streams sit right after the first shelf; they need no connection.
                            if (i == 0 && recent.isNotEmpty() && up) item(key = "recent") {
                                CardShelf(vm, "Jump back in", null, recent.take(20).map(OnlineItem::Track), { actionsFor = it }) { item ->
                                    (item as? OnlineItem.Track)?.let { t -> play(recent, recent.indexOf(t.song), "Recently streamed") }
                                }
                            }
                        }
                        item(key = "refresh") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Pill("Refresh Explore", { o.loadExplore(force = true) }, icon = Ic.Refresh)
                            }
                        }
                    }
                }
            }
        }
        CompactTitleBar("Explore", pastTitle) { scope.launch { list.animateScrollToItem(0) } }
    }
    actionsFor?.let { s -> SongActionsDialog(vm, s, onOpenAlbum = {}) { actionsFor = null } }
}

@Composable
private fun Banner(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    val c = P.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(24.dp)).background(c.card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = T.title, color = c.text)
            Text(text, style = T.small, color = c.muted)
        }
        action?.invoke()
    }
}

// ---- Search ---------------------------------------------------------------------------------

/**
 * The online part of Search: kinds, suggestions and paged results. Drawn inside Search's grid,
 * each item spanning both columns.
 */
fun LazyGridScope.onlineSearchItems(
    vm: MainViewModel,
    results: com.skarif.kairos.ui.OnlineResults,
    suggestions: List<String>,
    query: String,
    nowId: Long?,
    favs: Set<Long>,
    onSuggestion: (String) -> Unit,
    onSong: (SongEntity) -> Unit,
    onOpen: (OnlineItem) -> Unit,
) {
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    val fresh = suggestions.filterNot { it.equals(query.trim(), true) }
    if (fresh.isNotEmpty()) item(span = full, key = "o-sugg") {
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(fresh, key = { "sg$it" }) { s -> Pill(s, { onSuggestion(s) }, icon = Ic.Search) }
        }
    }
    when {
        results.error != null -> item(span = full, key = "o-err") { Status(results.error, "Try again" to { vm.onlineUi.retrySearch() }) }
        results.items.isEmpty() && results.loading -> items(4, span = { full() }, key = { "o-sk$it" }) { SkeletonRow() }
        results.items.isEmpty() && results.query.isNotBlank() -> item(span = full, key = "o-none") { Status("Nothing found for \"${results.query}\".") }
        else -> {
            val tracks = results.items.filterIsInstance<OnlineItem.Track>().map { it.song }
            results.items.forEachIndexed { i, item ->
                item(span = full, key = "o-${item.key}", contentType = if (item is OnlineItem.Track) "song" else "page") {
                    if (i >= results.items.size - 6) LaunchedEffect(results.items.size) { vm.onlineUi.loadMore() }
                    when (item) {
                        is OnlineItem.Track -> SongRow(item.song, item.song.id == nowId, vm, favourite = item.song.id in favs, onLongClick = { onSong(item.song) }) {
                            onOpen(item)
                        }
                        else -> OnlinePageRow(vm, item) { onOpen(item) }
                    }
                }
            }
            if (results.loading) item(span = full, key = "o-more") { SkeletonRow() }
            if (tracks.size > 1 && results.kind == OnlineKind.SONGS) item(span = full, key = "o-hint") {
                Text("Tap a song to play it with its radio.", Modifier.padding(horizontal = 18.dp), style = T.small, color = P.colors.muted)
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    val c = P.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.tint))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(180.dp, 14.dp).clip(RoundedCornerShape(6.dp)).background(c.tint))
            Box(Modifier.size(110.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(c.tint))
        }
    }
}

// ---- Album, playlist and artist pages -------------------------------------------------------

@Composable
private fun PageHeader(
    vm: MainViewModel, art: String?, circle: Boolean, kicker: String, title: String, sub: String?,
    onBack: () -> Unit, actions: @Composable () -> Unit,
) {
    val c = P.colors
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) { CircleButton(Ic.Back, "Back", onBack) }
        RemoteArt(vm, art, 220.dp, corner = 28.dp, circle = circle)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kicker.uppercase(), style = T.small.copy(fontSize = 11.sp, letterSpacing = 1.sp), color = c.muted)
            Text(title, style = T.h1.copy(fontSize = 28.sp, lineHeight = 32.sp), color = c.text, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = T.body, color = c.muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun <T> LoadPage(load: suspend () -> Loadable<T>, onBack: () -> Unit, content: LazyListScope.(T) -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    val state by produceState<Loadable<T>>(Loadable.Loading, attempt) { value = Loadable.Loading; value = load() }
    ScreenBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            contentPadding = PaddingValues(bottom = 40.dp + navBarBottom() + LocalBottomClearance.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val s = state) {
                is Loadable.Ready -> content(s.value)
                is Loadable.Failed -> {
                    item { Row(Modifier.statusBarsPadding().padding(18.dp)) { CircleButton(Ic.Back, "Back", onBack) } }
                    item { Status(s.message, "Try again" to { attempt++ }) }
                }
                Loadable.Loading -> {
                    item { Row(Modifier.statusBarsPadding().padding(18.dp)) { CircleButton(Ic.Back, "Back", onBack) } }
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(220.dp).clip(RoundedCornerShape(28.dp)).background(P.colors.tint))
                        }
                    }
                    items(6) { SkeletonRow() }
                }
            }
        }
    }
}

@Composable
fun OnlineCollectionScreen(vm: MainViewModel, id: String, onBack: () -> Unit, onOpenNowPlaying: () -> Unit) {
    val now by vm.now.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<SongEntity?>(null) }
    LoadPage<OnlineCollection>({ vm.onlineUi.collection(id) }, onBack) { col ->
        val mins = col.songs.sumOf { it.durationMs } / 60_000
        item(key = "head") {
            PageHeader(
                vm, col.art, false, if (col.isAlbum) "Album" else "Playlist", col.title,
                listOfNotNull(col.owner, "${col.songs.size} songs", "$mins min".takeIf { mins > 0 }).joinToString(" · "), onBack,
            ) {
                Pill("Play", { vm.play(col.songs, 0, col.title); onOpenNowPlaying() }, selected = true, icon = Ic.Play)
                Pill("Shuffle", { vm.shuffle(col.songs, smart = false, from = col.title); onOpenNowPlaying() }, icon = Ic.Shuffle)
                Pill("Save", {
                    vm.createPlaylist(col.title, col.songs.map { it.id })
                    vm.notify("Saved as a playlist")
                }, icon = Ic.PlaylistAdd)
            }
        }
        itemsIndexed(col.songs, key = { _, s -> "cs${s.id}" }) { i, s ->
            SongRow(s, s.id == now.songId, vm, number = if (col.isAlbum) i + 1 else null, favourite = s.id in favs, onLongClick = { actionsFor = s }) {
                vm.play(col.songs, i, col.title); onOpenNowPlaying()
            }
        }
    }
    actionsFor?.let { s -> SongActionsDialog(vm, s, onOpenAlbum = {}) { actionsFor = null } }
}

@Composable
fun OnlineArtistScreen(vm: MainViewModel, id: String, name: String, onBack: () -> Unit, onOpenNowPlaying: () -> Unit) {
    val now by vm.now.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val nav = LocalOnlineNav.current
    var actionsFor by remember { mutableStateOf<SongEntity?>(null) }
    var showAll by remember { mutableStateOf(false) }
    LoadPage<OnlineArtist>({ vm.onlineUi.artist(id, name) }, onBack) { a ->
        item(key = "head") {
            PageHeader(vm, a.art, true, "Artist", a.name, "${a.songs.size} songs", onBack) {
                if (a.songs.isNotEmpty()) {
                    Pill("Shuffle", { vm.shuffle(a.songs, smart = false, from = a.name); onOpenNowPlaying() }, selected = true, icon = Ic.Shuffle)
                    Pill("Radio", { vm.startRadio(a.songs.first()); onOpenNowPlaying() }, icon = Ic.Radio)
                }
            }
        }
        if (a.songs.isNotEmpty()) item(key = "songs-h") { Box(Modifier.padding(top = 12.dp)) { ShelfTitle("Songs", null) } }
        val shown = if (showAll) a.songs else a.songs.take(8)
        itemsIndexed(shown, key = { _, s -> "as${s.id}" }) { i, s ->
            SongRow(s, s.id == now.songId, vm, favourite = s.id in favs, onLongClick = { actionsFor = s }) {
                vm.play(a.songs, i, a.name); onOpenNowPlaying()
            }
        }
        if (!showAll && a.songs.size > 8) item(key = "more") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pill("Show all ${a.songs.size}", { showAll = true }) }
        }
        if (a.collections.isNotEmpty()) item(key = "rel") {
            Box(Modifier.padding(top = 18.dp)) {
                CardShelf(vm, "Albums & playlists", null, a.collections, {}) { nav.collection((it as OnlineItem.Collection).id) }
            }
        }
    }
    actionsFor?.let { s -> SongActionsDialog(vm, s, onOpenAlbum = {}) { actionsFor = null } }
}
