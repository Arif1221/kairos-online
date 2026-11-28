package com.skarif.kairos.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.DownloadMode
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
import com.skarif.kairos.ui.components.cardClick
import com.skarif.kairos.ui.components.KairosDialog
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.PillMeter
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
        Box {
            ItemArt(vm, item, 150.dp, corner = 20.dp)
            if (item is OnlineItem.Track) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                DownloadButton(vm, item.song, 34.dp, container = c.card.copy(alpha = 0.92f), content = c.text)
            }
        }
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
private fun OnlinePageRow(vm: MainViewModel, item: OnlineItem, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = P.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item))
            .combinedClickable(
                interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button,
                onLongClickLabel = if (onLongClick != null) "Remove" else null, onLongClick = onLongClick, onClick = onClick,
            )
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

/** A shelf's title, with "See all" on the right when the shelf has a page of its own. */
@Composable
private fun ShelfHeader(title: String, subtitle: String?, onSeeAll: (() -> Unit)?, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(end = 18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { ShelfTitle(title, subtitle) }
        extra?.invoke()
        onSeeAll?.let { Pill("See all", it, icon = Ic.Next) }
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
private fun PicksShelf(vm: MainViewModel, shelf: Shelf, nowId: Long?, onSong: (SongEntity) -> Unit, onSeeAll: () -> Unit, onPlay: (List<SongEntity>, Int) -> Unit) {
    val songs = remember(shelf) { shelf.items.filterIsInstance<OnlineItem.Track>().map { it.song } }
    val columns = remember(songs) { songs.chunked(4) }
    val c = P.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShelfHeader(shelf.title, shelf.subtitle, onSeeAll) { Pill("Play", { onPlay(songs, 0) }, icon = Ic.Play) }
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
                            DownloadButton(vm, s, 36.dp, content = if (s.id == nowId) c.onAccent else c.muted)
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
private fun CardShelf(
    vm: MainViewModel, title: String, subtitle: String?, items: List<OnlineItem>,
    onSong: (SongEntity) -> Unit, onSeeAll: (() -> Unit)? = null, onOpen: (OnlineItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShelfHeader(title, subtitle, onSeeAll)
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { item ->
                OnlineCard(vm, item, onLongClick = (item as? OnlineItem.Track)?.let { t -> { onSong(t.song) } }) { onOpen(item) }
            }
        }
    }
}

// ---- Explore --------------------------------------------------------------------------------

@Composable
fun ExploreScreen(
    vm: MainViewModel,
    onMenu: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onDownloads: () -> Unit,
    onOpenCollection: (String) -> Unit,
    onSearch: () -> Unit,
    onShelf: (String) -> Unit = {},
) {
    val o = vm.onlineUi
    val c = P.colors
    val nav = LocalOnlineNav.current
    val uri = LocalUriHandler.current
    val state by o.explore.collectAsStateWithLifecycle()
    val up by o.networkUp.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val offline by o.availableOffline.collectAsStateWithLifecycle()
    val downloads by o.downloads.collectAsStateWithLifecycle()
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
    LaunchedEffect(Unit) { vm.tabReselected.collect { if (it == "home") list.animateScrollToItem(0) } }

    fun play(songs: List<SongEntity>, i: Int, from: String) { vm.play(songs, i, from); onOpenNowPlaying() }
    val openItem: (OnlineItem) -> Unit = { open(vm, it, onOpenNowPlaying, nav) }

    // The big card at the top of Home: a mix for this time of day, else what you played, else the Quick picks.
    val heroPart = remember { com.skarif.kairos.data.dayPart(java.time.LocalTime.now().hour) }
    val heroMix = remember(mixes, state) {
        listOf("daylist", "forgotten", "never", "onrepeat", "most", "favourites", "lastplayed", "recent", "quick")
            .firstNotNullOfOrNull { k -> mixes.firstOrNull { it.key == k }?.takeIf { it.songs.isNotEmpty() } }
            ?: (state as? ExploreState.Ready)?.shelves?.firstOrNull { it.style == ShelfStyle.PICKS }
                ?.items?.filterIsInstance<OnlineItem.Track>()?.map { it.song }?.takeIf { it.isNotEmpty() }
                ?.let { com.skarif.kairos.ui.Collection("picks", "Quick picks", "", it) }
    }
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
                    "Home",
                    left = { chip -> CircleButton(Ic.Menu, "Menu", onMenu, container = chip, content = c.text) },
                    right = { chip ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircleButton(Ic.Refresh, "Refresh Home", { o.loadExplore(force = true); vm.notify("Refreshing Home") }, container = chip, content = c.text)
                            CircleButton(Ic.Search, "Search", onSearch, container = chip, content = c.text)
                        }
                    },
                    pull = { band.pull },
                ) {
                    val part = remember { when (java.time.LocalTime.now().hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" } }
                    Text(mixedHeadline("Good " to false, part to true), Modifier.stretchTitle { band.pull }, style = T.h1.copy(fontSize = 34.sp, lineHeight = 38.sp), color = c.text)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("For you", { if (moodIndex >= 0) o.selectMood(moodIndex) }, selected = moodIndex < 0, icon = Ic.Discover)
                        EXPLORE_MOODS.forEachIndexed { i, (label, _) -> Pill(label, { o.selectMood(i) }, selected = moodIndex == i) }
                    }
                }
            }
            if (moodIndex < 0 && heroMix != null) item(key = "hero") {
                RightNowCard(
                    vm, heroMix, heroPart, Modifier.padding(horizontal = 18.dp),
                    onOpen = { if (heroMix.key == "picks") onShelf("picks") else onOpenCollection(heroMix.key) },
                    onPlay = { play(heroMix.songs, 0, heroMix.title) },
                )
            }
            item(key = "hint") {
                com.skarif.kairos.ui.components.HintCard(
                    "home", "This is Home",
                    listOf(
                        "Quick picks, Jump back in and Made for you come from what you play, streamed or on this phone.",
                        "The chips under the greeting switch Home to playlists and songs for one mood. For you brings it back.",
                        "Hold any song for more: play next, download, save to phone, go to the artist.",
                        "Bottom bar: Home · Library · ▶ Now playing · Stats · Search.",
                    ),
                )
            }
            (update as? com.skarif.kairos.ui.UpdateState.Available)?.let { u ->
                item(key = "update") {
                    Banner("Kairos Online ${u.version} is out", "Fixes and new features. Installs over this version.") {
                        Pill("Update", { o.showUpdate() }, selected = true)
                    }
                }
            }
            if (!up) item(key = "offline") {
                Banner(
                    "You're offline",
                    if (offline.isEmpty()) "Download songs (hold one, then Download) to play them without a connection."
                    else "${offline.size} ${if (offline.size == 1) "song plays" else "songs play"} without a connection.",
                )
            }
            // Streamed songs still in the cache also play offline.
            val cachedOnly = offline.filter { s -> downloads.none { it.id == s.id } }
            if (!up && cachedOnly.isNotEmpty()) item(key = "saved") {
                CardShelf(vm, "Also saved on this phone", null, cachedOnly.map(OnlineItem::Track), { actionsFor = it }) { item ->
                    (item as? OnlineItem.Track)?.let { t -> play(cachedOnly, cachedOnly.indexOf(t.song), "Saved on this phone") }
                }
            }
            // What's yours, streamed or on this phone: works offline, so it never waits for the network.
            fun yours() {
                val lastPlayed = mixes.firstOrNull { it.key == "lastplayed" }?.songs.orEmpty().take(20)
                if (lastPlayed.isNotEmpty()) item(key = "recent") {
                    CardShelf(vm, "Jump back in", null, lastPlayed.map(OnlineItem::Track), { actionsFor = it }, { onOpenCollection("lastplayed") }) { item ->
                        (item as? OnlineItem.Track)?.let { t -> play(lastPlayed, lastPlayed.indexOf(t.song), "Recently played") }
                    }
                }
                if (downloads.isNotEmpty()) item(key = "downloads") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(end = 18.dp), verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.weight(1f)) { ShelfTitle("Your downloads", "${downloads.size} songs · play offline") }
                            Pill("See all", onDownloads, icon = Ic.Next)
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(downloads.take(20), key = { _, s -> "dl${s.id}" }) { i, s ->
                                OnlineCard(vm, OnlineItem.Track(s), onLongClick = { actionsFor = s }) { play(downloads, i, "Downloads") }
                            }
                        }
                    }
                }
                val own = mixes.filter { it.songs.isNotEmpty() && it.key != "lastplayed" }.take(10)
                if (own.isNotEmpty()) item(key = "mixes") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ShelfTitle("Made for you", "From what you play")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(own, key = { "mx" + it.key }) { m ->
                                Box(Modifier.width(160.dp)) {
                                    CoverTile(vm, m.songs.first(), key = m.key, kicker = "${m.songs.size} songs", title = m.title) { onOpenCollection(m.key) }
                                }
                            }
                        }
                    }
                }
            }
            if (moodIndex >= 0) {
                when (val m = mood) {
                    is Loadable.Ready -> {
                        if (m.value.playlists.isNotEmpty()) item(key = "mood-lists") {
                            CardShelf(vm, "${m.value.label} playlists", null, m.value.playlists, { }, onOpen = openItem)
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
                    ExploreState.Loading -> { yours(); items(3, key = { "sk$it" }) { SkeletonShelf() } }
                    is ExploreState.Failed -> {
                        yours()
                        if (up || offline.isEmpty()) item(key = "fail") { Status(st.message, "Try again" to { o.loadExplore(force = true) }) }
                    }
                    is ExploreState.Ready -> {
                        if (st.refreshing) item(key = "refreshing") { Status("Updating for you...") }
                        st.shelves.forEachIndexed { i, shelf ->
                            item(key = "shelf:${shelf.key}") {
                                when (shelf.style) {
                                    ShelfStyle.PICKS -> PicksShelf(vm, shelf, now.songId, { actionsFor = it }, { onShelf(shelf.key) }) { songs, at -> play(songs, at, shelf.title) }
                                    else -> CardShelf(vm, shelf.title, shelf.subtitle, shelf.items, { actionsFor = it }, { onShelf(shelf.key) }, openItem)
                                }
                            }
                            // Your own music sits right after Quick picks.
                            if (i == 0) yours()
                        }
                    }
                }
            }
        }
        CompactTitleBar("Home", pastTitle) { scope.launch { list.animateScrollToItem(0) } }
    }
    actionsFor?.let { s -> SongActionsDialog(vm, s, onOpenAlbum = {}) { actionsFor = null } }
}

@Composable
private fun Banner(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    val c = P.colors
    val hairline = c.line.copy(alpha = if (c.dark) 0.45f else 0.30f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.card.copy(alpha = if (c.dark) 0.85f else 0.92f))
            .border(1.dp, hairline, RoundedCornerShape(24.dp))
            .padding(16.dp),
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
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { actions() }
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
                val liked by vm.onlineUi.likedCollections.collectAsStateWithLifecycle()
                val isLiked = liked.any { it.id == id }
                Pill(if (isLiked) "Liked" else "Like", {
                    vm.onlineUi.toggleLike(OnlineItem.Collection(id, col.title, col.owner, col.art, col.isAlbum, col.songs.size))
                }, selected = isLiked, icon = if (isLiked) Ic.HeartFilled else Ic.Heart)
                Pill("Copy to playlists", {
                    vm.createPlaylist(col.title, col.songs.map { it.id })
                }, icon = Ic.PlaylistAdd)
            }
        }
        item(key = "dl") { DownloadAllRow(vm, col.songs) }
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
                val liked by vm.onlineUi.likedArtists.collectAsStateWithLifecycle()
                val isLiked = liked.any { it.id == id || it.name.equals(a.name, true) }
                Pill(if (isLiked) "Liked" else "Like", {
                    // Found by name (from the player): keep the name as the id until a real one is known.
                    vm.onlineUi.toggleLike(OnlineItem.Artist(if (id == "_") "_:${a.name}" else id, a.name, a.art, "${a.songs.size} songs"))
                }, selected = isLiked, icon = if (isLiked) Ic.HeartFilled else Ic.Heart)
                if (a.songs.isNotEmpty()) {
                    Pill("Shuffle", { vm.shuffle(a.songs, smart = false, from = a.name); onOpenNowPlaying() }, selected = true, icon = Ic.Shuffle)
                    Pill("Radio", { vm.startRadio(a.songs.first()); onOpenNowPlaying() }, icon = Ic.Radio)
                }
            }
        }
        if (a.songs.isNotEmpty()) item(key = "dl") { DownloadAllRow(vm, a.songs) }
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

/** "Download all" for a page of songs, with how far along it is. */
@Composable
private fun DownloadAllRow(vm: MainViewModel, songs: List<SongEntity>) {
    val done by vm.onlineUi.downloadedIds.collectAsStateWithLifecycle()
    val videoDone by vm.onlineUi.downloadedVideoIds.collectAsStateWithLifecycle()
    val busy by vm.onlineUi.downloading.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mode = if (settings.rememberDownloadChoice) settings.downloadMode else DownloadMode.AUDIO_ONLY
    val phone by vm.onlineUi.savedToPhoneIds.collectAsStateWithLifecycle()
    val have = when (mode) {
        DownloadMode.SAVE_TO_PHONE -> songs.count { it.id in phone }
        DownloadMode.AUDIO_ONLY -> songs.count { it.id in done }
        DownloadMode.AUDIO_VIDEO -> songs.count { it.id in done && it.id in videoDone }
    }
    val going = songs.count { it.id in busy }
    val label = when {
        have == songs.size -> "All ${songs.size} downloaded"
        going > 0 -> "Downloading: $have of ${songs.size} done"
        have > 0 -> "Download the other ${songs.size - have}"
        else -> "Download all ${songs.size}"
    }
    val onPhone = songs.count { it.id in phone }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (going == 0) Pill(
            if (onPhone == songs.size) "On phone" else "Save to phone",
            { vm.onlineUi.saveToPhone(songs) }, selected = onPhone == songs.size, icon = Ic.Folder,
        )
        Pill(label, {
            when {
                going > 0 -> songs.filter { it.id in busy }.forEach(vm.onlineUi::cancelDownload)
                have < songs.size -> vm.onlineUi.download(songs)
            }
        }, selected = have == songs.size, icon = if (have == songs.size) Ic.Check else if (going > 0) Ic.Close else Ic.DownArrow)
    }
}

/** Every downloaded song: plays with no connection. */
@Composable
fun DownloadsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenNowPlaying: () -> Unit) {
    val c = P.colors
    val downloads by vm.onlineUi.downloads.collectAsStateWithLifecycle()
    val busy by vm.onlineUi.downloading.collectAsStateWithLifecycle()
    val bytes by vm.onlineUi.downloadBytes.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<SongEntity?>(null) }
    ScreenBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            contentPadding = PaddingValues(bottom = 40.dp + navBarBottom() + LocalBottomClearance.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Column(Modifier.statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircleButton(Ic.Back, "Back", onBack)
                    Text(mixedHeadline("Your " to false, "downloads" to true), style = T.h1, color = c.text)
                    com.skarif.kairos.ui.components.HintCard(
                        "downloads", "Download or Save to phone?",
                        listOf(
                            "Audio only keeps the song inside Kairos for offline music. Audio + video also saves the matched music video for offline video playback.",
                            "Save to phone makes an M4A audio file in Music/Kairos that any app can play. It appears in your phone library.",
                            "On a song: ↓ download · ring = downloading (tap to cancel) · ✓ = saved for the selected option.",
                            "Choose an option from the ↓ button. Turn on Remember my choice in the dialog or Settings > Downloads & storage to skip the prompt next time.",
                        ),
                        inset = 0.dp,
                    )
                    Text(
                        if (downloads.isEmpty()) "Nothing downloaded yet. Hold an online song and tap Download, or use Download all on an album or playlist."
                        else "${downloads.size} songs, ${bytes / 1_048_576} MB. They play without a connection and stay until you remove them.",
                        style = T.body, color = c.muted,
                    )
                    if (busy.isNotEmpty()) Text("Downloading ${busy.size} more...", style = T.small, color = c.muted)
                    if (downloads.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Play", { vm.play(downloads, 0, "Downloads"); onOpenNowPlaying() }, selected = true, icon = Ic.Play)
                        Pill("Shuffle", { vm.shuffle(downloads, smart = false, from = "Downloads"); onOpenNowPlaying() }, icon = Ic.Shuffle)
                    }
                }
            }
            itemsIndexed(downloads, key = { _, s -> "d${s.id}" }) { i, s ->
                SongRow(s, s.id == now.songId, vm, favourite = s.id in favs, onLongClick = { actionsFor = s }) {
                    vm.play(downloads, i, "Downloads"); onOpenNowPlaying()
                }
            }
        }
    }
    actionsFor?.let { s -> SongActionsDialog(vm, s, onOpenAlbum = {}) { actionsFor = null } }
}

/**
 * The visible download button for an online song: an arrow, then a ring filling while it
 * downloads (tap to cancel), then a tick. Reads progress through derivedStateOf, so a list of
 * these redraws only the row whose number changed.
 */
@Composable
fun DownloadButton(
    vm: MainViewModel, song: SongEntity, size: Dp = 40.dp,
    container: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    content: androidx.compose.ui.graphics.Color = P.colors.muted,
) {
    val o = vm.onlineUi
    val busyMap = o.downloading.collectAsStateWithLifecycle()
    val doneSet = o.downloadedIds.collectAsStateWithLifecycle()
    val phoneSet = o.savedToPhoneIds.collectAsStateWithLifecycle()
    val videoSet = o.downloadedVideoIds.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by remember(song.id) { derivedStateOf { busyMap.value[song.id] } }
    val done by remember(song.id, settings.downloadMode, settings.rememberDownloadChoice) { derivedStateOf {
        when (if (settings.rememberDownloadChoice) settings.downloadMode else DownloadMode.AUDIO_ONLY) {
            DownloadMode.SAVE_TO_PHONE -> song.id in phoneSet.value
            DownloadMode.AUDIO_ONLY -> song.id in doneSet.value || song.id in phoneSet.value
            DownloadMode.AUDIO_VIDEO -> song.id in doneSet.value && song.id in videoSet.value
        }
    } }
    var showChoice by remember(song.id) { mutableStateOf(false) }
    var rememberChoice by remember(song.id) { mutableStateOf(false) }
    val accent = P.colors.accent

    fun choose(mode: DownloadMode) {
        vm.updateSettings { it.copy(downloadMode = mode, rememberDownloadChoice = rememberChoice) }
        o.download(listOf(song), mode)
        showChoice = false
    }

    Box(contentAlignment = Alignment.Center) {
        CircleButton(
            when { progress != null -> Ic.Close; done -> Ic.Check; else -> Ic.DownArrow },
            when { progress != null -> "Cancel download"; done -> "Download options"; else -> "Download" },
            {
                when {
                    progress != null -> o.cancelDownload(song)
                    settings.rememberDownloadChoice -> o.download(listOf(song), settings.downloadMode)
                    else -> { rememberChoice = false; showChoice = true }
                }
            },
            modifier = if (progress != null) Modifier.drawBehind {
                val stroke = 2.5.dp.toPx()
                drawArc(
                    accent, -90f, 360f * progress!!.coerceAtLeast(0.03f), false,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            } else Modifier,
            size = size, container = container, content = if (done && progress == null) accent else content, iconSize = size * 0.45f,
        )
    }

    if (showChoice) {
        KairosDialog(
            onDismissRequest = { showChoice = false },
            containerColor = P.colors.sheet,
            title = { Text("Download ${song.title}", style = T.h2, color = P.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose what Kairos should save.", style = T.body, color = P.colors.muted)
                    Pill("Audio + video · Offline video", { choose(DownloadMode.AUDIO_VIDEO) }, icon = Ic.DownArrow)
                    Pill("Audio only · Offline music", { choose(DownloadMode.AUDIO_ONLY) }, icon = Ic.Music)
                    Pill("Save audio to phone · M4A", { choose(DownloadMode.SAVE_TO_PHONE) }, icon = Ic.Folder)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().cardClick("Remember download choice") { rememberChoice = !rememberChoice }.padding(vertical = 4.dp)) {
                        androidx.compose.material3.Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                        Column(Modifier.weight(1f)) {
                            Text("Remember for next songs", style = T.title, color = P.colors.text)
                            Text("Use this option automatically for future Download taps. Change it in Settings.", style = T.small, color = P.colors.muted)
                        }
                    }
                }
            },
            confirmButton = { Pill("Cancel", { showChoice = false }) },
        )
    }
}

/**
 * "A new version is out": what's new, and Update, which downloads and installs it in place.
 * Shown once per version; "Later" leaves only the banner on Home and the line in Settings.
 */
@Composable
fun UpdateDialog(vm: MainViewModel) {
    val o = vm.onlineUi
    val c = P.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uri = LocalUriHandler.current
    val update by o.update.collectAsStateWithLifecycle()
    val skipped by o.skippedUpdate.collectAsStateWithLifecycle()
    val status by com.skarif.kairos.online.SelfUpdate.status.collectAsStateWithLifecycle()
    val u = update as? com.skarif.kairos.ui.UpdateState.Available ?: return
    val busy = status is com.skarif.kairos.online.SelfUpdate.Status.Downloading || status is com.skarif.kairos.online.SelfUpdate.Status.Installing
    if (!busy && skipped == u.version) return
    // Coming back from "Install unknown apps" settings with the permission granted: carry on.
    var waitingForPermission by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(waitingForPermission) {
        if (!waitingForPermission) return@LaunchedEffect
        lifecycle.currentStateFlow.collect { st ->
            if (st == androidx.lifecycle.Lifecycle.State.RESUMED && com.skarif.kairos.online.SelfUpdate.canInstall(ctx)) {
                waitingForPermission = false
                o.installUpdate()
            }
        }
    }
    KairosDialog(
        onDismissRequest = { if (!busy) o.skipUpdate(u.version) },
        containerColor = c.sheet,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c.tint), contentAlignment = Alignment.Center) {
                    Icon(Ic.DownArrow, null, tint = c.accent, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text("Update available", style = T.kicker, color = c.muted)
                    Text("Kairos Online ${u.version}", style = T.h2, color = c.text)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (u.notes.isNotEmpty()) {
                    Text("What's new", style = T.title, color = c.text)
                    u.notes.forEach { note ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(c.accent))
                            Text(note, Modifier.weight(1f), style = T.body, color = c.muted)
                        }
                    }
                } else Text("Fixes and improvements.", style = T.body, color = c.muted)
                when (val st = status) {
                    is com.skarif.kairos.online.SelfUpdate.Status.Downloading -> {
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Downloading", Modifier.weight(1f), style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                                Text("${(st.progress * 100).toInt()}%", style = T.num, color = c.text)
                            }
                            com.skarif.kairos.ui.components.SmoothProgressBar(st.progress)
                            if (st.total > 0) Text(
                                String.format("%.1f of %.1f MB", st.done / 1_048_576.0, st.total / 1_048_576.0),
                                style = T.small, color = c.muted,
                            )
                        }
                    }
                    com.skarif.kairos.online.SelfUpdate.Status.Installing -> Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Installing", style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        com.skarif.kairos.ui.components.SmoothProgressBar(null)
                        Text("Kairos closes for a moment and the notification opens it again.", style = T.small, color = c.muted)
                    }
                    is com.skarif.kairos.online.SelfUpdate.Status.Failed -> Text(st.message, style = T.small, color = c.accent)
                    else -> Text(
                        if (com.skarif.kairos.online.SelfUpdate.canInstall(ctx)) "Installs right here, over this version. Your music, playlists and settings stay."
                        else "The first time, Android asks you to let Kairos install updates. After that, updates install in one tap.",
                        style = T.small, color = c.muted,
                    )
                }
            }
        },
        confirmButton = {
            if (!busy) Pill(if (status is com.skarif.kairos.online.SelfUpdate.Status.Failed) "Try again" else "Update now", {
                when {
                    u.apkUrl == null -> uri.openUri(u.url)
                    !com.skarif.kairos.online.SelfUpdate.canInstall(ctx) -> {
                        waitingForPermission = true
                        runCatching { ctx.startActivity(com.skarif.kairos.online.SelfUpdate.permissionIntent(ctx)) }
                    }
                    else -> o.installUpdate()
                }
            }, selected = true, icon = Ic.DownArrow)
        },
        dismissButton = { if (!busy) Pill("Later", { o.skipUpdate(u.version) }) },
    )
}

/** Library > Artists or Albums: what you liked online. Works offline (the list is saved on the phone). */
@Composable
fun LikedOnlineScreen(vm: MainViewModel, artists: Boolean, onBack: () -> Unit) {
    val c = P.colors
    val nav = LocalOnlineNav.current
    val likedArtists by vm.onlineUi.likedArtists.collectAsStateWithLifecycle()
    val likedCollections by vm.onlineUi.likedCollections.collectAsStateWithLifecycle()
    val items: List<OnlineItem> = if (artists) likedArtists else likedCollections
    ScreenBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            contentPadding = PaddingValues(bottom = 40.dp + navBarBottom() + LocalBottomClearance.current),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "head") {
                Column(Modifier.statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircleButton(Ic.Back, "Back", onBack)
                    Text(mixedHeadline("Your " to false, (if (artists) "artists" else "albums") to true), style = T.h1, color = c.text)
                    Text(
                        if (items.isEmpty()) (if (artists) "Open an artist and tap Like to keep them here." else "Open an album or playlist and tap Like to keep it here.")
                        else "${items.size} liked. Hold one to remove it.",
                        style = T.body, color = c.muted,
                    )
                }
            }
            items(items, key = { it.key }) { item ->
                OnlinePageRow(vm, item, onLongClick = {
                    when (item) {
                        is OnlineItem.Artist -> vm.onlineUi.toggleLike(item)
                        is OnlineItem.Collection -> vm.onlineUi.toggleLike(item)
                        else -> {}
                    }
                }) {
                    when (item) {
                        is OnlineItem.Artist -> nav.artist(if (item.id.startsWith("_:")) "_" else item.id, item.name)
                        is OnlineItem.Collection -> nav.collection(item.id)
                        else -> {}
                    }
                }
            }
        }
    }
}

/** "See all" for a Home shelf: everything in it as a list. */
@Composable
fun ShelfScreen(vm: MainViewModel, key: String, onBack: () -> Unit, onOpenNowPlaying: () -> Unit) {
    val c = P.colors
    val nav = LocalOnlineNav.current
    val state by vm.onlineUi.explore.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<SongEntity?>(null) }
    val shelf = (state as? ExploreState.Ready)?.shelves?.firstOrNull { it.key == key }
    val songs = shelf?.items?.filterIsInstance<OnlineItem.Track>()?.map { it.song }.orEmpty()
    ScreenBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            contentPadding = PaddingValues(bottom = 40.dp + navBarBottom() + LocalBottomClearance.current),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "head") {
                Column(Modifier.statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircleButton(Ic.Back, "Back", onBack)
                    shelf?.subtitle?.let { Text(it.uppercase(), style = T.small.copy(fontSize = 11.sp, letterSpacing = 1.sp), color = c.muted) }
                    Text(shelf?.title ?: "Not available", style = T.h1, color = c.text)
                    if (songs.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Play", { vm.play(songs, 0, shelf?.title); onOpenNowPlaying() }, selected = true, icon = Ic.Play)
                        Pill("Shuffle", { vm.shuffle(songs, smart = false, from = shelf?.title); onOpenNowPlaying() }, icon = Ic.Shuffle)
                        Pill("Download all", { vm.onlineUi.download(songs) }, icon = Ic.DownArrow)
                    }
                    if (shelf == null) Text("Go back to Home and let it load first.", style = T.body, color = c.muted)
                }
            }
            shelf?.let { sh ->
                items(sh.items, key = { it.key }) { item ->
                    when (item) {
                        is OnlineItem.Track -> SongRow(item.song, item.song.id == now.songId, vm, favourite = item.song.id in favs, onLongClick = { actionsFor = item.song }) {
                            vm.play(songs, songs.indexOf(item.song), sh.title); onOpenNowPlaying()
                        }
                        else -> OnlinePageRow(vm, item) { open(vm, item, onOpenNowPlaying, nav) }
                    }
                }
            }
        }
    }
    actionsFor?.let { s -> SongActionsDialog(vm, s, onOpenAlbum = {}) { actionsFor = null } }
}

/** Paste a public Spotify playlist or album link; Kairos rebuilds it as one of your playlists. */
@Composable
fun SpotifyImportScreen(vm: MainViewModel, onBack: () -> Unit, onOpenPlaylist: (Long) -> Unit) {
    val c = P.colors
    val o = vm.onlineUi
    val state by o.import.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    val nav = LocalOnlineNav.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    ScreenBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            contentPadding = PaddingValues(bottom = 40.dp + navBarBottom() + LocalBottomClearance.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") {
                Column(Modifier.statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircleButton(Ic.Back, "Back", onBack)
                    Text(mixedHeadline("Import from " to false, "Spotify" to true), style = T.h1, color = c.text)
                    Text(
                        "Paste the link of a public playlist or album (Share › Copy link in Spotify). No Spotify login. " +
                            "Each song is looked for on this phone first, then on ${vm.onlineName ?: "YouTube Music"}.",
                        style = T.body, color = c.muted,
                    )
                    SearchField(link, { link = it }, "https://open.spotify.com/playlist/…", height = 52.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Paste", { clipboard.getText()?.text?.let { link = it.trim() } }, icon = Ic.PlaylistAdd)
                        val busy = state is com.skarif.kairos.ui.OnlineFeatures.ImportState.Reading || state is com.skarif.kairos.ui.OnlineFeatures.ImportState.Matching
                        Pill(if (busy) "Stop" else "Import", { if (busy) o.resetImport() else o.importSpotify(link) }, selected = true, icon = if (busy) Ic.Close else Ic.DownArrow)
                    }
                }
            }
            item(key = "state") {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (val st = state) {
                        com.skarif.kairos.ui.OnlineFeatures.ImportState.Idle -> Text(
                            "Spotify shares the first 100 songs of a playlist this way. Bigger ones come in cut short.",
                            style = T.small, color = c.muted,
                        )
                        com.skarif.kairos.ui.OnlineFeatures.ImportState.Reading -> Text("Reading the playlist from Spotify…", style = T.body, color = c.text)
                        is com.skarif.kairos.ui.OnlineFeatures.ImportState.Matching -> {
                            Text(st.name, style = T.h2, color = c.text)
                            Text("Finding songs: ${st.done} of ${st.total} · ${st.found} found", style = T.body, color = c.muted)
                            com.skarif.kairos.ui.components.PillMeter((st.done * 20 / st.total.coerceAtLeast(1)), 20, c.accent, height = 10.dp)
                        }
                        is com.skarif.kairos.ui.OnlineFeatures.ImportState.Done -> {
                            Text(st.name, style = T.h2, color = c.text)
                            Text(
                                "Made a playlist with ${st.found} of ${st.total} songs." +
                                    if (st.maybeCut) " Spotify only shared the first 100." else "",
                                style = T.body, color = c.muted,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Open playlist", { onOpenPlaylist(st.playlistId) }, selected = true, icon = Ic.Play)
                                Pill("Import another", { link = ""; o.resetImport() })
                            }
                            if (st.missing.isNotEmpty()) {
                                Text("Not found (${st.missing.size})", style = T.title, color = c.text)
                                Text("Search for these yourself and add them with Add to playlist.", style = T.small, color = c.muted)
                            }
                        }
                        is com.skarif.kairos.ui.OnlineFeatures.ImportState.Failed -> Text(st.message, style = T.body, color = c.accent)
                    }
                }
            }
            (state as? com.skarif.kairos.ui.OnlineFeatures.ImportState.Done)?.missing?.let { missing ->
                items(missing.size, key = { "m$it" }) { i ->
                    val t = missing[i]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(16.dp)).background(c.card)
                            .cardClick("Search ${t.title}") { nav.search("${t.title} ${com.skarif.kairos.online.SpotifyImport.firstArtist(t.artist)}", OnlineKind.SONGS) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = T.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Ic.Search, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
