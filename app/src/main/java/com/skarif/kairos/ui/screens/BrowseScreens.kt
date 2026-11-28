package com.skarif.kairos.ui.screens

import com.skarif.kairos.ui.components.cardSurface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.SheetAction
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.CompactTitleBar
import com.skarif.kairos.ui.components.stretchTitle
import kotlinx.coroutines.launch
import com.skarif.kairos.ui.components.ScreenHeader
import com.skarif.kairos.ui.components.sharedCard
import com.skarif.kairos.ui.components.cardClick
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import com.skarif.kairos.ui.theme.mixedHeadline

@Composable
fun SearchField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    container: Color = P.colors.card, height: androidx.compose.ui.unit.Dp = 60.dp,
) {
    val c = P.colors
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(container)
            .border(1.dp, c.hairlineSoft, RoundedCornerShape(height / 2))
            // Tapping anywhere on the pill (not just the text) starts typing.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { focus.requestFocus() }
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = T.body.copy(fontSize = 16.sp), color = c.muted)
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth().withFocusRequester(focus).semantics { contentDescription = placeholder },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }), textStyle = T.body.copy(fontSize = 16.sp, color = c.text), cursorBrush = SolidColor(c.accent),
            )
        }
        if (value.isNotEmpty()) CircleButton(Ic.Close, "Clear", { onChange("") }, size = height - 16.dp, container = c.tint)
        else Box(Modifier.size(height - 16.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(Ic.Search, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
        }
    }
}

private fun Modifier.withFocusRequester(r: androidx.compose.ui.focus.FocusRequester): Modifier =
    this.then(Modifier.focusRequester(r))

/**
 * Search. Typing shows matching artists, albums, genres and songs together; with nothing typed,
 * browse albums, artists or genres as cover tiles.
 */
@Composable
fun SearchScreen(vm: MainViewModel, onMenu: () -> Unit, onOpenCollection: (String) -> Unit, onOpenNowPlaying: () -> Unit) {
    val menu = LocalCollectionMenu.current
    val c = P.colors
    val q by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val hits by vm.searchHits.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var actionsFor by remember { mutableStateOf<SongEntity?>(null) }
    val albumGroups by vm.albums.collectAsStateWithLifecycle()
    val albums = remember(albumGroups) { albumGroups.sortedByDescending { it.songs.size }.take(60) }
    val artistList by vm.artists.collectAsStateWithLifecycle()
    val artists = remember(artistList) { artistList.sortedByDescending { it.songs.size }.take(60) }
    val genres by vm.genres.collectAsStateWithLifecycle()
    // Online flavor: search YouTube Music or your library.
    val onlineName = vm.onlineName
    val searchOnline by vm.onlineUi.searchOnline.collectAsStateWithLifecycle()
    val onlineResults by vm.onlineUi.results.collectAsStateWithLifecycle()
    val suggestions by vm.onlineUi.suggestions.collectAsStateWithLifecycle()
    val onlineKind by vm.onlineUi.kind.collectAsStateWithLifecycle()
    val onlineNav = LocalOnlineNav.current
    val showOnline = onlineName != null && searchOnline && q.isNotEmpty()

    actionsFor?.let { s -> SongActionsDialog(vm, s, { onOpenCollection("album:$it") }) { actionsFor = null } }

    val searchGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val band = com.skarif.kairos.ui.motion.rememberRubberBand()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    com.skarif.kairos.ui.components.OnTabReselected(vm, "search") { searchGrid.animateScrollToItem(0) }
    val pastTitle by remember { androidx.compose.runtime.derivedStateOf { searchGrid.firstVisibleItemIndex > 0 } }

    ScreenBackground {
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.fillMaxSize(),
            state = searchGrid,
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            overscrollEffect = band,
            contentPadding = PaddingValues(bottom = 230.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                // Same header as Home, Library and Statistics.
                com.skarif.kairos.ui.components.TabHeader(
                    "Search",
                    left = { chip -> CircleButton(Ic.Menu, "Menu", onMenu, container = chip, content = c.text) },
                    right = { chip -> Box(Modifier.size(52.dp)) },
                    pull = { band.pull },
                ) { chip ->
                    Text(mixedHeadline("Explore " to false, "your music" to true), Modifier.stretchTitle { band.pull }, style = T.h1.copy(fontSize = 34.sp, lineHeight = 38.sp), color = c.text)
                    SearchField(
                        q, vm::setQuery,
                        if (onlineName != null) "Songs, artists, albums, playlists" else "Songs, artists, albums, genres",
                        container = chip, height = 52.dp,
                    )
                    // Online build: separate Online (Audio/Video/etc.) from Offline (On this phone) clearly.
                    if (onlineName != null && q.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Online · $onlineName", { vm.onlineUi.setSearchOnline(true) }, selected = searchOnline)
                                Pill("Offline · On this phone", { vm.onlineUi.setSearchOnline(false) }, selected = !searchOnline, icon = Ic.Music)
                            }
                            if (searchOnline) {
                                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    com.skarif.kairos.online.OnlineKind.entries.forEach { k ->
                                        val label = when (k) {
                                            com.skarif.kairos.online.OnlineKind.SONGS -> "Audio (Songs)"
                                            com.skarif.kairos.online.OnlineKind.VIDEOS -> "Video"
                                            else -> k.label
                                        }
                                        Pill(label, { vm.onlineUi.setKind(k) }, selected = onlineKind == k)
                                    }
                                }
                            }
                        }
                    }
                    if (q.isEmpty() && onlineName != null) {
                        com.skarif.kairos.ui.components.HintCard(
                            "search", "One search for everything",
                            listOf(
                                "Type to search $onlineName and this phone together; matches on this phone come first.",
                                "Chips under the box filter: Songs, Videos, Artists, Albums & playlists, or only On this phone.",
                                "Tap a song to play it with a radio after it. Hold it for more.",
                            ),
                            inset = 0.dp,
                        )
                    }
                    if (q.isEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Albums", { tab = 0 }, selected = tab == 0, icon = Ic.Music)
                            Pill("Artists", { tab = 1 }, selected = tab == 1, icon = Ic.Home)
                            Pill("Genres", { tab = 2 }, selected = tab == 2, icon = Ic.Wave)
                        }
                    }
                }
            }
            if (showOnline) {
                // Matches on this phone come first, briefly; the chip shows them all.
                if (results.isNotEmpty() || hits.artists.isNotEmpty() || hits.albums.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "phone-h") {
                        Row(Modifier.fillMaxWidth().padding(end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SearchSection("On this phone") }
                            Pill("See all", { vm.onlineUi.setSearchOnline(false) })
                        }
                    }
                    val top = results.take(3)
                    gridItemsIndexed(top, key = { _, s -> "p" + s.id }, span = { _, _ -> GridItemSpan(2) }, contentType = { _, _ -> "song" }) { i, s ->
                        SongRow(s, s.id == now.songId, vm, favourite = s.id in favs, onLongClick = { actionsFor = s }) {
                            vm.play(results, i, "Search"); onOpenNowPlaying()
                        }
                    }
                    item(span = { GridItemSpan(2) }, key = "online-h") { SearchSection(onlineName ?: "Online") }
                }
                onlineSearchItems(
                    vm, onlineResults, suggestions, q, now.songId, favs,
                    onSuggestion = { vm.setQuery(it) },
                    onSong = { actionsFor = it },
                    onOpen = { item ->
                        when (item) {
                            is com.skarif.kairos.online.OnlineItem.Track -> { vm.startRadio(item.song); onOpenNowPlaying() }
                            is com.skarif.kairos.online.OnlineItem.Artist -> onlineNav.artist(item.id, item.name)
                            is com.skarif.kairos.online.OnlineItem.Collection -> onlineNav.collection(item.id)
                        }
                    },
                )
            } else if (q.isNotEmpty()) {
                val nothing = results.isEmpty() && hits.artists.isEmpty() && hits.albums.isEmpty() && hits.genres.isEmpty()
                if (nothing) {
                    item(span = { GridItemSpan(2) }) {
                        EmptyState(Ic.Search, "No results for \"$q\"", "Check the spelling, or try an artist, album or genre instead.")
                    }
                }
                if (hits.artists.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "h-artists") { SearchSection("Artists") }
                    item(span = { GridItemSpan(2) }, key = "artists") {
                        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(hits.artists, key = { "sa" + it.name.lowercase() }, contentType = { "artist" }) { a ->
                                Column(
                                    Modifier.width(92.dp).cardClick("Open ${a.name}", onLongClick = { menu("artist:${a.name}") }) { onOpenCollection("artist:${a.name}") },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SongArt(vm, a.songs.first(), 84.dp, corner = 42.dp)
                                    Text(a.name, style = T.title.copy(fontSize = 14.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${a.songs.size} songs", style = T.small.copy(fontSize = 11.sp), color = c.muted, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (hits.albums.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "h-albums") { SearchSection("Albums") }
                    item(span = { GridItemSpan(2) }, key = "albums") {
                        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(hits.albums, key = { "sal" + it.id }, contentType = { "album" }) { g ->
                                Box(Modifier.size(width = 160.dp, height = 214.dp)) {
                                    CoverTile(vm, g.songs.first(), key = "album:${g.id}", kicker = g.artist, title = g.title) { onOpenCollection("album:${g.id}") }
                                }
                            }
                        }
                    }
                }
                if (hits.genres.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "h-genres") { SearchSection("Genres") }
                    item(span = { GridItemSpan(2) }, key = "genres") {
                        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(hits.genres, key = { "sg" + it.name.lowercase() }, contentType = { "genre" }) { g ->
                                Pill("${g.name}  ${g.songs.size}", { onOpenCollection("genre:${g.name}") })
                            }
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "h-songs") { SearchSection("Songs  ${results.size}") }
                    gridItemsIndexed(results, key = { _, s -> s.id }, span = { _, _ -> GridItemSpan(2) }, contentType = { _, _ -> "song" }) { i, s ->
                        Box {
                        SongRow(s, s.id == now.songId, vm, favourite = s.id in favs, onLongClick = { actionsFor = s }) {
                            vm.play(results, i, "Search")
                            onOpenNowPlaying()
                        }
                        }
                    }
                }
            } else {
                // (cover song, collection key, kicker, title)
                val list = when (tab) {
                    0 -> albums.map { Quad(it.songs.first(), "album:${it.id}", it.artist, it.title) }
                    1 -> artists.map { Quad(it.songs.first(), "artist:${it.name}", "${it.songs.size} songs", it.name) }
                    else -> genres.map { Quad(it.songs.first(), "genre:${it.name}", "${it.songs.size} songs", it.name) }
                }
                if (list.isEmpty() && tab == 2) {
                    item(span = { GridItemSpan(2) }) {
                        EmptyState(Ic.Wave, "No genres yet", "Your songs don't carry genre tags. Albums and artists still work.")
                    }
                }
                gridItemsIndexed(list, key = { _, t -> t.key }, contentType = { _, _ -> "tile" }) { i, t ->
                    // Same edge spacing as the Library's album grid.
                    Box(Modifier.gridGutter(i)) {
                        CoverTile(vm, t.song, key = t.key, kicker = t.kicker, title = t.title) { onOpenCollection(t.key) }
                    }
                }
            }
        }
        CompactTitleBar("Search", pastTitle) { scope.launch { searchGrid.animateScrollToItem(0) } }
    }
}

@Composable
private fun SearchSection(title: String) {
    com.skarif.kairos.ui.components.SectionTitle(title, Modifier.padding(top = 8.dp))
}

/** Cover card that grows into the album/artist page when tapped. */
@Composable
fun CoverTile(vm: MainViewModel, song: SongEntity, key: String, kicker: String, title: String, onClick: () -> Unit) {
    val img = rememberCover(vm, song)
    val c = P.colors
    Box(
        Modifier.fillMaxWidth().height(214.dp)
            .sharedCard("col-$key", RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card))
            .let { m ->
                // Long-press: the shared menu for this album, genre or mix (see LocalCollectionMenu).
                val menu = LocalCollectionMenu.current
                m.cardClick("Open $title", onLongClick = { menu(key) }, onClick = onClick)
            }
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(c.tint),
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxWidth().height(110.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(kicker.uppercase(), style = T.small.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = T.title.copy(fontSize = 17.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PlaylistsScreen(vm: MainViewModel, onBack: () -> Unit, onNew: () -> Unit, onOpenCollection: (String) -> Unit, onImport: () -> Unit = {}) {
    val menu = LocalCollectionMenu.current
    val c = P.colors
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var smart by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val importM3u = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importPlaylist(uri) }
    data class Tile(val key: String, val name: String, val sub: String, val songs: List<SongEntity>)
    val tiles = if (smart) mixes.map { Tile(it.key, it.title, "Smart, ${it.songs.size} songs", it.songs) }
    else {
        // Favourites always comes first among your own playlists.
        val favs = mixes.firstOrNull { it.key == "favourites" }
        listOfNotNull(favs?.let { Tile("favourites", "Favourites", "${it.songs.size} songs you love", it.songs) }) +
            playlists.map { Tile("playlist:${it.id}", it.name, "${it.songs.size} songs", it.songs) }
    }

    val band = com.skarif.kairos.ui.motion.rememberRubberBand()

    ScreenBackground {
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.fillMaxSize().statusBarsPadding(),
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            overscrollEffect = band,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 40.dp + navBarBottom() + LocalBottomClearance.current),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircleButton(Ic.Back, "Back", onBack)
                        Box(Modifier.weight(1f))
                        Pill("Import", { importM3u.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "text/plain", "*/*")) }, icon = Ic.Down)
                        Box(Modifier.size(8.dp))
                        Pill("New", onNew, icon = Ic.Plus)
                        if (vm.onlineName != null) { Box(Modifier.size(8.dp)); Pill("Spotify", onImport, icon = Ic.DownArrow) }
                    }
                    Text(mixedHeadline("Your " to false, "playlists" to true, ", made " to false, "your way" to true), Modifier.stretchTitle { band.pull }, style = T.h1, color = c.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Made by you  ${tiles.size.takeIf { !smart } ?: (playlists.size + 1)}", { smart = false }, selected = !smart)
                        Pill("Smart  ${mixes.size}", { smart = true }, selected = smart)
                    }
                    if (tiles.isEmpty()) EmptyState(Ic.PlaylistAdd, "No playlists yet", "Tap New to make one, or import an .m3u file.", inset = 0.dp, action = "New playlist", onAction = onNew)
                }
            }
            items(tiles, key = { it.key }, contentType = { "playlist" }) { t ->
                Column(
                    Modifier.sharedCard("col-${t.key}", RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card))
                        .cardClick("Open ${t.name}", onLongClick = { menu(t.key) }) { onOpenCollection(t.key) }
                        .cardSurface(com.skarif.kairos.ui.theme.Radius.card, c.card)
                        .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlaylistArt(vm, t.key, t.songs, Modifier.fillMaxWidth().aspectRatio(1f))
                    Column(Modifier.padding(horizontal = 6.dp)) {
                        Text(t.name, style = T.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.sub, style = T.small, color = c.muted)
                    }
                }
            }
        }
    }
}

/** A playlist's own cover if you picked one, otherwise a grid of its album covers. */
@Composable
fun PlaylistArt(vm: MainViewModel, key: String, songs: List<SongEntity>, modifier: Modifier, corner: androidx.compose.ui.unit.Dp = 22.dp) {
    val covers by vm.playlistCovers.collectAsStateWithLifecycle()
    val file = key.removePrefix("playlist:").toLongOrNull()?.takeIf { key.startsWith("playlist:") }?.let { covers[it] }
    val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = file?.let { f ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        }
    }
    val i = img
    if (file != null && i != null) Image(i, null, modifier.clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
    else Mosaic(vm, songs, modifier)
}

@Composable
fun Mosaic(vm: MainViewModel, songs: List<SongEntity>, modifier: Modifier) {
    val c = P.colors
    val four = songs.distinctBy { it.albumId }.take(4)
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(c.tint), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in 0..1) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (col in 0..1) {
                    val s = four.getOrNull(row * 2 + col)
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        if (s != null) {
                            val img = rememberCover(vm, s)
                            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NewPlaylistScreen(vm: MainViewModel, onDone: () -> Unit, initialSongs: List<Long> = emptyList()) {
    val c = P.colors
    val songs by vm.songs.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var find by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>().apply { addAll(initialSongs) } }
    val shown = remember(songs, find) {
        val t = find.trim().lowercase()
        if (t.isEmpty()) songs.take(300) else songs.filter { it.title.lowercase().contains(t) || it.artist.lowercase().contains(t) }.take(300)
    }
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    // Online build: songs from YouTube Music too, searched as you type (after a short pause).
    var online by remember { mutableStateOf<List<com.skarif.kairos.data.db.SongEntity>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    val onlineSeen = remember { androidx.compose.runtime.mutableStateMapOf<Long, com.skarif.kairos.data.db.SongEntity>() }
    if (vm.onlineName != null) androidx.compose.runtime.LaunchedEffect(find) {
        online = emptyList()
        if (find.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        onlineLoading = true
        online = vm.onlineUi.searchSongs(find).filter { o -> songs.none { it.id == o.id } }
        online.forEach { onlineSeen[it.id] = it }
        onlineLoading = false
    }
    val pickedSongs = picked.mapNotNull { songMap[it] ?: onlineSeen[it] }

    ScreenBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp + LocalBottomClearance.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScreenHeader("New playlist", onDone)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Mosaic(vm, pickedSongs, Modifier.size(104.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name", style = T.small, color = c.muted)
                    SearchFieldPlain(name, { name = it }, "Late drives")
                }
            }
            SearchField(find, { find = it }, "Find songs to add")
            LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(c.card.copy(alpha = 0.7f))) {
                items(shown, key = { it.id }, contentType = { "pick" }) { s -> PickRow(vm, s, s.id in picked) { if (s.id in picked) picked.remove(s.id) else picked.add(s.id) } }
                if (vm.onlineName != null && find.isNotBlank()) {
                    item(key = "online-h") {
                        Text(
                            if (onlineLoading) "Searching ${vm.onlineName}…" else if (online.isEmpty()) "Nothing on ${vm.onlineName}" else "From ${vm.onlineName}",
                            Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp), style = T.title, color = c.muted,
                        )
                    }
                    items(online, key = { "o" + it.id }, contentType = { "pick" }) { s -> PickRow(vm, s, s.id in picked) { if (s.id in picked) picked.remove(s.id) else picked.add(s.id) } }
                }
            }
            Pill(
                if (picked.isEmpty()) "Pick some songs" else "Create with ${picked.size} songs",
                { if (picked.isNotEmpty()) { vm.createPlaylist(name, picked.toList()); onDone() } },
                accent = true, big = true, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One song in the new-playlist picker, with a tick box. */
@Composable
private fun PickRow(vm: MainViewModel, s: com.skarif.kairos.data.db.SongEntity, on: Boolean, onToggle: () -> Unit) {
    val c = P.colors
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onToggle)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SongArt(vm, s, 46.dp, corner = 14.dp)
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = T.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, style = T.small, color = c.muted, maxLines = 1)
                        }
                        Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.muted))
                    }
}

@Composable
private fun SearchFieldPlain(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = P.colors
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(c.card).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = T.body.copy(fontSize = 16.sp), color = c.muted)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = "Playlist name" }, singleLine = true,
            textStyle = T.body.copy(fontSize = 16.sp, color = c.text), cursorBrush = SolidColor(c.accent))
    }
}

/** Album, playlist, artist and mix screen. Design reference: the hotel screen hero. */
@Composable
fun CollectionScreen(
    vm: MainViewModel, key: String, onBack: () -> Unit, onOpenNowPlaying: () -> Unit, onOpenAlbum: (Long) -> Unit,
    onOpenCollection: (String) -> Unit = { k -> k.removePrefix("album:").toLongOrNull()?.let(onOpenAlbum) },
) {
    val c = P.colors
    // Re-read when the library, playlists or mixes change.
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val col = remember(key, songs, playlists, mixes) { vm.collection(key) }
    var actionsFor by remember { mutableStateOf<SongEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Genre filter for favourites, playlists and mixes ("Liked songs by genre").
    var genreFilter by androidx.compose.runtime.saveable.rememberSaveable(key) { mutableStateOf<String?>(null) }
    val first = col?.songs?.firstOrNull()
    val cover = rememberCover(vm, first)
    val isAlbum = key.startsWith("album:")
    var actionIndex by remember { mutableStateOf(-1) }
    val genreChoices = remember(col) {
        if (isAlbum || key.startsWith("genre:")) emptyList()
        else col?.songs.orEmpty().flatMap { com.skarif.kairos.data.splitGenres(it.genre) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
    }
    val shown = remember(col, genreFilter) {
        val g = genreFilter
        val all = col?.songs.orEmpty()
        if (g == null) all
        else all.filter { s -> com.skarif.kairos.data.splitGenres(s.genre).any { com.skarif.kairos.data.genreKey(it) == com.skarif.kairos.data.genreKey(g) } }
    }
    val isArtist = key.startsWith("artist:")
    val isGenre = key.startsWith("genre:")
    val counts by vm.playCounts.collectAsStateWithLifecycle()
    val albumList by vm.albums.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    // Your most played songs by this artist (or in this genre); only songs you have played.
    val popular = remember(col, counts) {
        if (!isArtist && !isGenre) emptyList()
        else col?.songs.orEmpty().filter { (counts[it.id] ?: 0) > 0 }.sortedByDescending { counts[it.id] ?: 0 }.take(5)
    }
    val pageAlbums = remember(col, albumList) {
        if (!isArtist && !isGenre) emptyList()
        else col?.songs.orEmpty().mapNotNull { vm.albumGroup(it.albumId) }.distinctBy { it.id }
            .sortedWith(compareByDescending<com.skarif.kairos.data.AlbumGroup> { it.year }.thenBy { it.title.lowercase() })
    }
    val pageArtists = remember(col) {
        if (!isGenre) emptyList()
        else col?.songs.orEmpty().flatMap { com.skarif.kairos.data.splitArtists(it.artist) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
    }

    val pid = col?.playlistId
    val colTitle = col?.title ?: ""
    actionsFor?.let { s ->
        val pos = if (pid != null && genreFilter == null) actionIndex else -1
        SongActionsDialog(vm, s, onOpenAlbum, inPlaylist = if (pos >= 0) pid!! to pos else null) { actionsFor = null }
    }
    val pickCover = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null && pid != null) vm.setPlaylistCover(pid, uri) }
    val exportM3u = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri -> if (uri != null && pid != null) vm.exportPlaylist(pid, uri) }
    if (menu) {
        CollectionActionsSheet(
            vm, key, onOpenCollection, onDismiss = { menu = false }, showOpen = false,
            extra = if (pid == null) emptyList() else listOf(
                SheetAction("Change cover", Ic.Palette) {
                    pickCover.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                SheetAction("Use album covers", Ic.Disc) { vm.removePlaylistCover(pid) },
                SheetAction("Export as .m3u", Ic.Share) { exportM3u.launch("$colTitle.m3u8") },
            ),
            onDeleted = onBack,
        )
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val band = com.skarif.kairos.ui.motion.rememberRubberBand()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(), state = listState,
            flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling(),
            overscrollEffect = band,
            contentPadding = PaddingValues(bottom = 40.dp + navBarBottom() + LocalBottomClearance.current),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(380.dp)
                            .sharedCard("col-$key", RoundedCornerShape(bottomStart = 46.dp, bottomEnd = 46.dp))
                            // Apple Music header: scrolls up slower than the list (parallax), and when
                            // pulled down at the top it stretches to fill the gap instead of leaving one.
                            .graphicsLayer {
                                val pull = band.pull
                                if (pull > 0f) {
                                    val s = (size.height + pull) / size.height
                                    scaleX = s; scaleY = s
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                                } else if (listState.firstVisibleItemIndex == 0) {
                                    translationY = listState.firstVisibleItemScrollOffset * 0.45f
                                }
                            }
                            .clip(RoundedCornerShape(bottomStart = 46.dp, bottomEnd = 46.dp)).background(c.accent),
                    ) {
                        if (cover != null || pid != null) {
                            if (isAlbum && cover != null) Image(cover, "${col?.title} cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else PlaylistArt(vm, key, col?.songs.orEmpty(), Modifier.fillMaxSize(), corner = 0.dp)
                        }
                        Box(Modifier.fillMaxWidth().height(240.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x73000000), Color(0xB3000000)))))
                        Row(
                            Modifier.fillMaxWidth()
                                // Undo the stretch for the buttons, so they keep their size and place.
                                .graphicsLayer {
                                    val pull = band.pull
                                    if (pull > 0f) {
                                        val s = 380.dp.toPx() / (380.dp.toPx() + pull)
                                        scaleX = s; scaleY = s
                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                                    }
                                }
                                .statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                        ) {
                            CircleButton(Ic.Back, "Back", onBack, container = Color(0xE6FFFFFF), content = Color(0xFF151515))
                            Box(Modifier.weight(1f))
                            CircleButton(Ic.More, if (pid != null) "Playlist options" else "More options", { menu = true }, container = Color(0x38FFFFFF), content = Color.White)
                        }
                        Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, end = 110.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isAlbum && first != null) {
                                val albumArtist = vm.albumGroup(first.albumId)?.artist ?: first.artist
                                Text(
                                    albumArtist,
                                    Modifier.clickable(role = Role.Button, onClickLabel = "Open artist") {
                                        onOpenCollection("artist:${com.skarif.kairos.data.splitArtists(albumArtist).first()}")
                                    },
                                    style = T.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                                )
                            }
                            Text(col?.title ?: "Not found", style = T.big.copy(fontSize = 38.sp, lineHeight = 40.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(col?.subtitle ?: "", style = T.small, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                    CircleButton(
                        Ic.Play, "Play all", { col?.songs?.let { if (it.isNotEmpty()) { vm.play(it, 0); onOpenNowPlaying() } } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp), size = 76.dp,
                        container = c.accent, content = c.onAccent, iconSize = 26.dp,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Play", { if (shown.isNotEmpty()) { vm.play(shown, 0, colTitle); onOpenNowPlaying() } }, selected = true, big = true, icon = Ic.Play, modifier = Modifier.weight(1f))
                    Pill(
                        if (settings.smartShuffle) "Smart shuffle" else "Shuffle", { vm.shuffle(shown, from = colTitle); onOpenNowPlaying() },
                        big = true, icon = Ic.Shuffle, modifier = Modifier.weight(1f),
                    )
                    // Smart Shuffle: mixes in a suggestion from your library every few songs.
                    CircleButton(
                        Ic.Wave, if (settings.smartShuffle) "Smart shuffle, on" else "Smart shuffle, off",
                        { vm.updateSettings { it.copy(smartShuffle = !it.smartShuffle) } }, size = 60.dp,
                        container = if (settings.smartShuffle) c.accent else c.card, content = if (settings.smartShuffle) c.onAccent else c.text,
                    )
                }
            }
            if (genreChoices.size >= 2) {
                item {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { Pill("All", { genreFilter = null }, selected = genreFilter == null) }
                        items(genreChoices, key = { "gf$it" }, contentType = { "chip" }) { g -> Pill(g, { genreFilter = if (genreFilter == g) null else g }, selected = genreFilter == g) }
                    }
                }
                val g = genreFilter
                if (g != null) {
                    item {
                        Pill(
                            "Save ${shown.size} songs as a playlist", { vm.createPlaylist("$colTitle: $g", shown.map { it.id }) },
                            icon = Ic.PlaylistAdd, modifier = Modifier.padding(start = 18.dp, top = 10.dp),
                        )
                    }
                }
            }
            val list = shown
            // Artist and genre pages: popular songs and albums first, then everything.
            if (isArtist || isGenre) {
                if (popular.isNotEmpty()) {
                    item { PageSection("Popular") }
                    itemsIndexed(popular, key = { i, s -> "pop$i-${s.id}" }, contentType = { _, _ -> "song" }) { i, s ->
                        SongRow(s, s.id == now.songId, vm, favourite = s.id in favs, onLongClick = { actionsFor = s }) { vm.play(popular, i, colTitle); onOpenNowPlaying() }
                    }
                }
                if (pageAlbums.size > 1 || (pageAlbums.size == 1 && isGenre)) {
                    item { PageSection(if (isArtist) "Albums" else "Albums in ${col?.title}") }
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(pageAlbums, key = { "pa" + it.id }, contentType = { "album" }) { g ->
                                Box(Modifier.size(width = 160.dp, height = 214.dp)) {
                                    CoverTile(vm, g.songs.first(), key = "album:${g.id}", kicker = if (g.year > 0) "${g.year}" else "${g.songs.size} songs", title = g.title) {
                                        onOpenCollection("album:${g.id}")
                                    }
                                }
                            }
                        }
                    }
                }
                if (isGenre && pageArtists.isNotEmpty()) {
                    item { PageSection("Artists") }
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(pageArtists, key = { "ga$it" }, contentType = { "chip" }) { name -> Pill(name, { onOpenCollection("artist:$name") }) }
                        }
                    }
                }
                if (list.isNotEmpty()) item { PageSection("All songs") }
            }
            if (list.isEmpty() && col != null) item {
                EmptyState(
                    if (pid != null) Ic.PlaylistAdd else Ic.Music,
                    if (genreFilter != null) "No songs in $genreFilter" else if (pid != null) "This playlist is empty" else "No songs here",
                    if (pid != null) "Hold any song in your library and choose Add to playlist." else "Songs will appear here once they're in your library.",
                )
            }
            if (list.isNotEmpty()) {
                itemsIndexed(list, key = { i, s -> "$i-${s.id}" }, contentType = { _, _ -> "song" }) { i, s ->
                    // The first screenful of songs rises in one after another when the page opens.
                    Box {
                    SongRow(s, s.id == now.songId, vm, favourite = s.id in favs, number = if (isAlbum) (s.track.takeIf { it > 0 } ?: (i + 1)) else null, onLongClick = { actionIndex = i; actionsFor = s }) {
                        vm.play(list, i, colTitle)
                        onOpenNowPlaying()
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageSection(title: String) {
    com.skarif.kairos.ui.components.SectionTitle(title, Modifier.padding(top = 18.dp, bottom = 8.dp))
}

private data class Quad(val song: SongEntity, val key: String, val kicker: String, val title: String)
