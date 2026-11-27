package com.skarif.kairos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.ui.Collection
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.PillMeter
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.CompactTitleBar
import com.skarif.kairos.ui.components.headerPanel
import com.skarif.kairos.ui.components.stretchTitle
import kotlinx.coroutines.launch
import com.skarif.kairos.ui.components.sharedCard
import com.skarif.kairos.ui.components.cardClick
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import com.skarif.kairos.ui.theme.mixedHeadline

/**
 * Home uses the same building blocks as Library and Search: the frosted header panel with round
 * chip buttons, neutral cards, and cover tiles. It used to tint every card with album-art colours
 * and show empty "0 songs" tiles, which made it look like a different app from the other tabs.
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onOpenCollection: (String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlaylists: () -> Unit,
    onLibrary: () -> Unit,
) {
    val c = P.colors
    // Long-press on a "Recently played" card.
    var songMenu by remember { mutableStateOf<com.skarif.kairos.data.db.SongEntity?>(null) }
    songMenu?.let { s -> SongActionsDialog(vm, s, { onOpenCollection("album:$it") }, onOpenArtist = { a -> onOpenCollection("artist:$a") }) { songMenu = null } }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val mixesReady by vm.mixesReady.collectAsStateWithLifecycle()
    val cont by vm.continueAlbum.collectAsStateWithLifecycle()
    val cur by vm.currentSong.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()

    val byKey = remember(mixes) { mixes.associateBy { it.key } }
    val hour = remember { java.time.LocalTime.now().hour }
    val part = when (hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
    // Same chip colour as the Library header.
    val chip = c.chip
    val card = c.card.copy(alpha = if (c.dark) 0.85f else 0.92f)

    val scroll = rememberScrollState()
    val band = com.skarif.kairos.ui.motion.rememberRubberBand()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Tapping Home again in the dock springs back to the top.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.tabReselected.collect { if (it == "home") scroll.animateScrollTo(0, com.skarif.kairos.ui.motion.Motion.sheet()) }
    }
    val pastTitle by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { scroll.value > with(density) { 150.dp.toPx() } } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    ScreenBackground {
        Column(
            Modifier.fillMaxSize()
                
                .verticalScroll(scroll, overscrollEffect = band, flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling())
                .padding(bottom = 230.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(
                Modifier.headerPanel { band.pull },
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleButton(Ic.Menu, "Menu", onMenu, container = chip, content = c.text)
                    Text("Home", Modifier.weight(1f), color = c.text, style = T.title, textAlign = TextAlign.Center)
                    CircleButton(Ic.Search, "Search", onSearch, container = chip, content = c.text)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        mixedHeadline("Good " to false, part to true), Modifier.stretchTitle { band.pull },
                        style = T.h1.copy(fontSize = 40.sp, lineHeight = 44.sp), color = c.text,
                    )
                    Text(
                        "${java.text.NumberFormat.getIntegerInstance().format(songs.size)} songs, ${playlists.size} playlists",
                        style = T.small, color = c.muted,
                    )
                }
                androidx.compose.runtime.CompositionLocalProvider(com.skarif.kairos.ui.components.LocalPillContainer provides chip) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Shuffle all", { vm.shuffleAll(songs); onOpenNowPlaying() }, accent = true, icon = Ic.Shuffle)
                        Pill("Playlists", onPlaylists, icon = Ic.Queue)
                    }
                }
            }

            // Right now: the mix for this time of day, on a card that changes colour with the hour.
            // Falls back to forgotten gems, then something never played, so it's never empty.
            val dayPart = remember(hour) { com.skarif.kairos.data.dayPart(hour) }
            val rightNow = listOf("daylist", "forgotten", "never").firstNotNullOfOrNull { k -> byKey[k]?.takeIf { it.songs.size >= 3 } }
            if (rightNow != null) {
                RightNowCard(
                    vm, rightNow, dayPart, Modifier.padding(horizontal = 18.dp),
                    onOpen = { onOpenCollection(rightNow.key) },
                    onPlay = { vm.play(rightNow.songs, 0, rightNow.title); onOpenNowPlaying() },
                )
            }
            // Rediscover: one song you loved and drifted away from, one tap to hear it again.
            val gem = byKey["forgotten"]?.songs?.firstOrNull()?.takeIf { rightNow?.key != "forgotten" }
            if (gem != null) {
                Row(
                    Modifier.padding(horizontal = 18.dp).fillMaxWidth()
                        .cardClick("Play ${gem.title}", onLongClick = { songMenu = gem }) { vm.play(listOf(gem), 0, "Rediscover"); onOpenNowPlaying() }
                        .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(card).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SongArt(vm, gem, 44.dp, corner = 14.dp)
                    Column(Modifier.weight(1f)) {
                        Text("REDISCOVER", style = T.small.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp), color = c.accent)
                        Text("${gem.title} · ${gem.artist}", style = T.title.copy(fontSize = 15.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ic.Play, null, Modifier.size(20.dp), tint = c.text)
                }
            }

            // Quick picks: only the ones that have songs, so a new install isn't a wall of "0 songs".
            // Recent and last-played already have dedicated sections below; don't repeat them here.
            val picks = listOf("favourites", "most", "onrepeat", "never")
                .mapNotNull { byKey[it] }.filter { it.songs.isNotEmpty() }.take(4)
            if (picks.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    picks.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { m -> QuickPick(vm, m, card, Modifier.weight(1f)) { onOpenCollection(m.key) } }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Where you left off.
            val album = cont
            val song = cur
            if (album != null && song != null) {
                val idx = album.songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                Column(
                    Modifier.padding(horizontal = 18.dp).fillMaxWidth().cardClick("Open player", onClick = onOpenNowPlaying)
                        .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SongArt(vm, song, 80.dp, corner = 22.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Continue listening", style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                            Text(song.title, style = T.title.copy(fontSize = 18.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        CircleButton(Ic.Play, "Resume", onOpenNowPlaying, size = 52.dp, container = c.accent, content = c.onAccent)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${album.title}, song ${idx + 1} of ${album.songs.size}", style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PillMeter(idx + 1, album.songs.size.coerceIn(1, 12), c.accent, height = 14.dp)
                    }
                }
            }

            // Jump back in: what you played lately.
            val recent = byKey["lastplayed"]?.songs.orEmpty().take(15)
            if (recent.isNotEmpty()) {
                Section("Jump back in", "See all", 3) { onOpenCollection("lastplayed") }
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(recent.size, key = { recent[it].id }, contentType = { "song" }) { i ->
                        val s2 = recent[i]
                        Column(
                            Modifier.width(150.dp).cardClick("Play ${s2.title}", onLongClick = { songMenu = s2 }) { vm.play(recent, i, "Recently played"); onOpenNowPlaying() },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SongArt(vm, s2, 150.dp, corner = 30.dp)
                            Column(Modifier.padding(horizontal = 4.dp)) {
                                Text(s2.title, style = T.title.copy(fontSize = 15.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s2.artist, style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            // Your mixes, made from what you play.
            val more = mixes.filter { it.songs.isNotEmpty() && it.key !in setOf("favourites", "lastplayed", "recent", "most") }
            if (more.isNotEmpty()) {
                Section("Made for you", "All", 5) { onPlaylists() }
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(more.size, key = { more[it].key }, contentType = { "mix" }) { i ->
                        val m = more[i]
                        MixCard(vm, m, card, Modifier.width(300.dp), onOpen = { onOpenCollection(m.key) }, onPlay = { vm.play(m.songs, 0, m.title); onOpenNowPlaying() })
                    }
                }
            } else if (mixesReady) {
                Text("Play a few songs and mixes made for you will appear here.", Modifier.padding(horizontal = 18.dp), style = T.body, color = c.muted)
            }

            // Fresh in your library: newest albums, as the same cover tiles Library and Search use.
            val fresh = remember(byKey["recent"]) { byKey["recent"]?.songs.orEmpty().distinctBy { it.albumId }.take(12) }
            if (fresh.isNotEmpty()) {
                Section("Fresh in your library", "Library", 7) { onLibrary() }
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(fresh.size, key = { "fa" + fresh[it].albumId }, contentType = { "album" }) { i ->
                        val s2 = fresh[i]
                        Box(Modifier.width(170.dp)) {
                            CoverTile(vm, s2, key = "album:${s2.albumId}", kicker = s2.artist, title = s2.album) { onOpenCollection("album:${s2.albumId}") }
                        }
                    }
                }
            }
        }
        CompactTitleBar("Good $part", pastTitle) { scope.launch { scroll.animateScrollTo(0, com.skarif.kairos.ui.motion.Motion.sheet()) } }
    }
}

@Composable
private fun Section(title: String, action: String? = null, order: Int = 0, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = T.h2.copy(fontSize = 22.sp), color = P.colors.text)
        if (action != null) {
            Text(
                action,
                Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 10.dp, vertical = 6.dp),
                style = T.small.copy(fontWeight = FontWeight.SemiBold), color = P.colors.muted,
            )
        }
    }
}

/** Compact two-up tile: cover, name, count. */
@Composable
private fun QuickPick(vm: MainViewModel, m: Collection, card: androidx.compose.ui.graphics.Color, modifier: Modifier, onOpen: () -> Unit) {
    val c = P.colors
    val menu = LocalCollectionMenu.current
    Row(
        modifier.height(72.dp).cardClick("Open ${m.title}", onLongClick = { menu(m.key) }, onClick = onOpen)
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.item)).background(card).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SongArt(vm, m.songs.firstOrNull(), 56.dp, corner = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(m.title, style = T.title.copy(fontSize = 15.sp, lineHeight = 18.sp), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${m.songs.size} songs", style = T.small, color = c.muted, maxLines = 1)
        }
    }
}

@Composable
private fun MixCard(vm: MainViewModel, m: Collection, card: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
    val c = P.colors
    val menu = LocalCollectionMenu.current
    Row(
        modifier
            .sharedCard("col-${m.key}", RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card))
            .cardClick("Open ${m.title}", onLongClick = { menu(m.key) }, onClick = onOpen)
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(card).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mosaic(vm, m.songs, Modifier.size(112.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.title, style = T.title.copy(fontSize = 17.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                m.songs.take(3).joinToString(", ") { it.artist }.ifEmpty { m.subtitle },
                style = T.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${m.songs.size} songs", Modifier.weight(1f), style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                CircleButton(Ic.Play, "Play ${m.title}", onPlay, size = 44.dp, container = c.accent, content = c.onAccent)
            }
        }
    }
}

/**
 * "Right now": the mix for this part of the day. The card's light follows the clock (warm morning,
 * bright afternoon, violet evening, deep night), mixed with the album colour; a fan of three
 * covers on the right. Static drawing: one gradient and three thumbnails.
 */
@Composable
private fun RightNowCard(
    vm: MainViewModel, m: Collection, part: com.skarif.kairos.data.DayPart, modifier: Modifier,
    onOpen: () -> Unit, onPlay: () -> Unit,
) {
    val c = P.colors
    val menu = LocalCollectionMenu.current
    val sky = when (part) {
        com.skarif.kairos.data.DayPart.MORNING -> androidx.compose.ui.graphics.Color(0xFFF6A55B)
        com.skarif.kairos.data.DayPart.AFTERNOON -> androidx.compose.ui.graphics.Color(0xFF3FA7E0)
        com.skarif.kairos.data.DayPart.EVENING -> androidx.compose.ui.graphics.Color(0xFF7A45D8)
        com.skarif.kairos.data.DayPart.NIGHT -> androidx.compose.ui.graphics.Color(0xFF14204A)
    }
    val label = if (m.key == "daylist") "RIGHT NOW · ${part.label.uppercase()}" else "FOR YOU"
    Box(
        modifier.fillMaxWidth().height(176.dp)
            .cardClick("Open ${m.title}", onLongClick = { menu(m.key) }, onClick = onOpen)
            .clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(sky, androidx.compose.ui.graphics.lerp(sky, c.accent, 0.6f), c.accent)))
            .padding(18.dp),
    ) {
        // Fan of covers, tilted like cards in a hand.
        Box(Modifier.align(Alignment.CenterEnd).size(width = 130.dp, height = 120.dp)) {
            m.songs.take(3).forEachIndexed { i, s ->
                SongArt(
                    vm, s, 84.dp, corner = 18.dp,
                    modifier = Modifier.align(Alignment.Center).graphicsLayer {
                        rotationZ = (i - 1) * 9f
                        translationX = (i - 1) * 20.dp.toPx()
                        translationY = if (i == 1) -6.dp.toPx() else 0f
                        shadowElevation = 8.dp.toPx()
                        shape = RoundedCornerShape(18.dp)
                    }.zIndex(if (i == 1) 1f else 0f),
                )
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(end = 140.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = T.small.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
            Text(m.title, style = T.h2.copy(fontSize = 24.sp, lineHeight = 28.sp), color = androidx.compose.ui.graphics.Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${m.songs.size} songs", style = T.small, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
        }
        CircleButton(
            Ic.Play, "Play ${m.title}", onPlay, modifier = Modifier.align(Alignment.BottomStart),
            container = androidx.compose.ui.graphics.Color.White, content = androidx.compose.ui.graphics.Color.Black,
        )
    }
}
