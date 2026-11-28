package com.skarif.kairos.ui.screens

import com.skarif.kairos.ui.components.cardSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.StatsRange
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.HatchedBars
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.CompactTitleBar
import com.skarif.kairos.ui.components.stretchTitle
import kotlinx.coroutines.launch
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import com.skarif.kairos.ui.theme.mixedHeadline

/** Short duration: "45s", "12m", "3h 05m". */
fun hoursText(ms: Long): String {
    val sec = ms / 1000
    val m = sec / 60
    return when {
        sec < 60 -> "${sec}s"
        m < 60 -> "${m}m"
        else -> "${m / 60}h " + "%02dm".format(m % 60)
    }
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

/** Listening statistics. Follows the theme; the chart card carries the accent. */
@Composable
fun StatsScreen(vm: MainViewModel, onMenu: () -> Unit, onSettings: () -> Unit) {
    val c = P.colors
    val st by vm.stats.collectAsStateWithLifecycle()
    val range by vm.statsRange.collectAsStateWithLifecycle()
    var story by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val rangeName = when (range) {
        com.skarif.kairos.ui.StatsRange.WEEK -> "week"; com.skarif.kairos.ui.StatsRange.MONTH -> "month"
        com.skarif.kairos.ui.StatsRange.YEAR -> "year"; com.skarif.kairos.ui.StatsRange.ALL -> "time with Kairos"
    }
    if (story) WrappedStory(vm, st, rangeName) { story = false }
    val sel by vm.selectedBar.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recap by vm.recap.collectAsStateWithLifecycle()
    val selected = if (sel in st.bars.indices) sel else st.bars.indices.maxByOrNull { st.bars[it].listenedMs } ?: -1
    val max = (st.bars.maxOfOrNull { it.listenedMs } ?: 0L).coerceAtLeast(1L)

    val scroll = rememberScrollState()
    val band = com.skarif.kairos.ui.motion.rememberRubberBand()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    com.skarif.kairos.ui.components.OnTabReselected(vm, "stats") { scroll.animateScrollTo(0, com.skarif.kairos.ui.motion.Motion.sheet()) }
    val pastTitle by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { scroll.value > with(density) { 140.dp.toPx() } } }

    ScreenBackground {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(scroll, overscrollEffect = band, flingBehavior = com.skarif.kairos.ui.motion.rememberIosFling())
                .padding(bottom = 230.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            com.skarif.kairos.ui.components.TabHeader(
                "Statistics",
                left = { chip -> CircleButton(Ic.Menu, "Menu", onMenu, container = chip, content = c.text) },
                right = { chip -> CircleButton(Ic.Settings, "Settings", onSettings, container = chip, content = c.text) },
                pull = { band.pull },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        mixedHeadline(hoursText(st.totalMs) to false, " listened" to true),
                        Modifier.stretchTitle { band.pull },
                        style = T.h1.copy(fontSize = 36.sp, lineHeight = 40.sp),
                        color = c.text,
                    )
                    Text(st.change, style = T.small.copy(fontFeatureSettings = "tnum"), color = c.muted)
                }
                if (st.plays > 0) Row {
                    com.skarif.kairos.ui.components.Pill("Play $rangeName story", { story = true }, icon = Ic.Play, accent = true)
                }
            }
            val hairline = c.hairline
            val cardBg = c.cardSoft
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(c.accent).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ACTIVITY", Modifier.weight(1f), style = T.kicker, color = c.onAccent.copy(alpha = 0.8f))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatsRange.entries.forEach { r ->
                            val on = r == range
                            Box(
                                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) Color.White else Color.Transparent)
                                    .selectable(on, role = Role.Tab) { vm.setStatsRange(r) }.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(r.label, style = T.label.copy(fontSize = 13.sp), color = if (on) Color(0xFF151515) else c.onAccent)
                            }
                        }
                    }
                }
                HatchedBars(
                    values = st.bars.map { it.listenedMs.toFloat() / max },
                    labels = st.bars.map { it.label },
                    selected = selected,
                    tooltip = st.bars.getOrNull(selected)?.let { hoursText(it.listenedMs) },
                    onSelect = vm::selectBar,
                )
            }
            if (st.plays == 0) {
                EmptyState(
                    Ic.Stats, "Nothing played in this $rangeName",
                    "Listen for a while and your plays, streaks and favourites will build up here.",
                    inset = 0.dp,
                )
            }
            if (st.plays > 0) Text("OVERVIEW", Modifier.padding(start = 6.dp, top = 4.dp), style = T.kicker, color = c.muted)
            if (st.plays > 0) Row(Modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("PLAYS", "${st.plays}", Modifier.weight(1f))
                StatTile("SONGS", "${st.uniqueSongs}", Modifier.weight(1f))
            }
            if (st.plays > 0) Row(Modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("DAILY AVG", hoursText(st.avgPerDayMs), Modifier.weight(1f))
                StatTile("STREAK", plural(st.streakDays, "day"), Modifier.weight(1f))
            }
            if (st.plays > 0) Row(Modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("COMPLETED", "${st.completedPct}%", Modifier.weight(1f))
                StatTile("PEAK HOUR", st.peakHour.ifEmpty { "–" }, Modifier.weight(1f))
            }

            if (st.top.isNotEmpty()) Column(
                Modifier.fillMaxWidth().cardSurface(com.skarif.kairos.ui.theme.Radius.card, cardBg)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("TOP SONGS", style = T.kicker, color = c.muted)
                val mostListened = st.top.maxOf { it.listenedMs }.coerceAtLeast(1L)
                st.top.forEachIndexed { i, t ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "${i + 1}",
                            Modifier.width(20.dp),
                            style = T.num.copy(fontWeight = FontWeight.Bold),
                            color = if (i == 0) c.accent else c.muted,
                        )
                        SongArt(vm, t.song, 46.dp, corner = 14.dp)
                        Column(Modifier.weight(1f)) {
                            Text(t.song.title, style = T.title.copy(fontSize = 15.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(plural(t.plays, "play"), style = T.small.copy(fontFeatureSettings = "tnum"), color = c.muted)
                        }
                        Text(hoursText(t.listenedMs), style = T.num, color = c.text)
                    }
                    // Same share bar as Top artists, indented to sit under the title.
                    com.skarif.kairos.ui.components.ShareBar(t.listenedMs.toFloat() / mostListened, if (i == 0) c.accent else c.accent.copy(alpha = 0.55f), Modifier.padding(start = 90.dp), height = 5.dp)
                    }
                }
            }

            if (st.topArtists.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().cardSurface(com.skarif.kairos.ui.theme.Radius.card, cardBg)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("TOP ARTISTS", style = T.kicker, color = c.muted)
                    val most = st.topArtists.first().second.coerceAtLeast(1L)
                    st.topArtists.forEachIndexed { i, (name, ms) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${i + 1}", style = T.num.copy(fontWeight = FontWeight.Bold), color = if (i == 0) c.accent else c.muted)
                                Text(name, Modifier.weight(1f), style = T.title.copy(fontSize = 15.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hoursText(ms), style = T.num, color = c.text)
                            }
                            com.skarif.kairos.ui.components.ShareBar(ms.toFloat() / most, c.accent)
                        }
                    }
                }
            }
            recap?.let { Box(Modifier) { RecapCard(vm, it) } }
            Text("Stats update while you listen.", style = T.small, color = c.muted)
            }
        }
        CompactTitleBar("Statistics", pastTitle) { scope.launch { scroll.animateScrollTo(0, com.skarif.kairos.ui.motion.Motion.sheet()) } }
    }
}

/** Wrapped-style "your year in music", from your own listening on this phone. */
@Composable
private fun RecapCard(vm: MainViewModel, r: com.skarif.kairos.data.Recap) {
    val c = P.colors
    val month = java.time.Month.of(r.topMonth).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(c.ink).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Your ${r.year} in music", style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.inkFg.copy(alpha = 0.7f))
        Text("${java.text.NumberFormat.getIntegerInstance().format(r.minutes)} minutes", style = T.big.copy(fontSize = 36.sp, lineHeight = 38.sp), color = c.inkFg)
        Text(
            "${plural(r.plays, "play")} of ${plural(r.songs, "song")} by ${plural(r.artists, "artist")}" +
                (if (r.newArtists > 0) ", ${r.newArtists} of them new to you" else "") + ". You listened most in $month.",
            style = T.body, color = c.inkFg.copy(alpha = 0.85f),
        )
        r.topArtists.firstOrNull()?.let { (name, _) -> RecapLine("Top artist", name) }
        r.topGenre?.let { RecapLine("Top genre", it) }
        Text("Top songs", style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.inkFg.copy(alpha = 0.7f))
        r.topSongs.forEachIndexed { i, (song, ms) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${i + 1}", Modifier.width(18.dp), style = T.title, color = c.inkFg.copy(alpha = 0.6f))
                SongArt(vm, song, 44.dp, corner = 14.dp)
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = T.title, color = c.inkFg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = T.small, color = c.inkFg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(hoursText(ms), style = T.small, color = c.inkFg)
            }
        }
    }
}

@Composable
private fun RecapLine(label: String, value: String) {
    val c = P.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = T.body, color = c.inkFg.copy(alpha = 0.7f))
        Text(value, style = T.title.copy(fontSize = 18.sp), color = c.inkFg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    val c = P.colors
    val hairline = c.hairline
    Column(
        modifier.cardSurface(com.skarif.kairos.ui.theme.Radius.item)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = T.kicker, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = T.big.copy(fontSize = 25.sp, lineHeight = 28.sp, fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
    }
}
