package com.skarif.kairos.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.StatsUi
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.ShareCard
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A row in a ranked card: cover, name, detail. */
private data class Ranked(val song: SongEntity?, val name: String, val detail: String)

/** One card of the story. [rows] makes it a top-5 list; [countMs] makes the headline count up. */
private data class StoryPage(
    val kicker: String,
    val headline: String,
    val detail: String,
    val cover: SongEntity?,
    val rows: List<Ranked> = emptyList(),
    val countMs: Long? = null,
)

private const val CARD_MS = 6_000

/**
 * "Wrapped", any time: the range Stats is showing, as a story. Cards advance on their own
 * (hold to pause), tap right or left to skip, swipe too. Each card enters with a short staggered
 * rise, the background takes the colour of the card's cover, and any card can be shared as an
 * image. Built only from the numbers Stats already has.
 */
@Composable
fun WrappedStory(vm: MainViewModel, st: StatsUi, rangeName: String, onDismiss: () -> Unit) {
    val c = P.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val top = st.top.firstOrNull()
    val pages = remember(st, rangeName) {
        buildList {
            add(StoryPage("Your $rangeName", hoursText(st.totalMs), "${st.plays} plays · ${st.uniqueSongs} different songs", top?.song, countMs = st.totalMs))
            if (top != null) add(StoryPage("Your top song", top.song.title, "${top.song.artist} · ${top.plays} plays", top.song))
            if (st.top.size >= 3) add(StoryPage(
                "Your top songs", "On repeat", "The five you played most", top?.song,
                rows = st.top.take(5).map { Ranked(it.song, it.song.title, "${it.plays} plays") },
            ))
            st.topArtists.firstOrNull()?.let { (name, ms) ->
                val cover = st.top.firstOrNull { s -> com.skarif.kairos.data.splitArtists(s.song.artist).any { it.equals(name, true) } }?.song
                add(StoryPage("Your top artist", name, "${hoursText(ms)} together", cover ?: top?.song))
            }
            if (st.topArtists.size >= 3) add(StoryPage(
                "Your top artists", "Your people", "Who you spent the most time with", top?.song,
                rows = st.topArtists.take(5).map { (name, ms) ->
                    Ranked(st.top.firstOrNull { s -> com.skarif.kairos.data.splitArtists(s.song.artist).any { it.equals(name, true) } }?.song, name, hoursText(ms))
                },
            ))
            val habit = buildList {
                if (st.peakHour.isNotBlank()) add("Most often around ${st.peakHour}")
                if (st.streakDays >= 2) add("${st.streakDays} days in a row")
                if (st.completedPct > 0) add("${st.completedPct}% of songs heard to the end")
            }
            if (habit.isNotEmpty()) add(StoryPage(
                "How you listen", if (st.peakHour.isNotBlank()) "A ${st.peakHour} listener" else "Every day", habit.joinToString(" · "),
                st.top.getOrNull(1)?.song ?: top?.song,
            ))
            add(StoryPage("Your colours", "This is how your $rangeName looked", "Mixed from the covers you played most", null))
        }
    }

    // Each card's colour: the average of its cover (a 1x1 scale), worked out once.
    val coverColours by produceState(emptyMap<Long, Color>(), pages) {
        value = withContext(Dispatchers.Default) {
            (pages.mapNotNull { it.cover } + st.top.take(5).map { it.song }).distinctBy { it.id }.mapNotNull { s ->
                vm.thumbnail(s)?.let { s.id to Color(Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0)) }
            }.toMap()
        }
    }
    val palette = st.top.take(5).mapNotNull { coverColours[it.song.id] }

    val pager = rememberPagerState { pages.size }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    // Auto-advance: the current card's bar fills over 6 s; holding pauses it where it is.
    val shownPage = remember { intArrayOf(-1) }
    LaunchedEffect(pager.currentPage, paused) {
        // A new card starts its bar from empty; a pause resumes where it stopped.
        if (pager.currentPage != shownPage[0]) { shownPage[0] = pager.currentPage; progress.snapTo(0f) }
        if (paused) return@LaunchedEffect
        val left = ((1f - progress.value) * CARD_MS).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        if (pager.currentPage < pages.lastIndex) pager.animateScrollToPage(pager.currentPage + 1)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onDismiss)
        val page = pages[pager.currentPage]
        val tint = page.cover?.let { coverColours[it.id] } ?: palette.firstOrNull() ?: c.accent
        val bg by animateColorAsState(tint, tween(700), label = "storyBg")
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.verticalGradient(listOf(lerp(bg, Color.Black, 0.15f), lerp(bg, Color.Black, 0.72f))))
            },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                StoryCard(vm, pages[i], i, active = pager.currentPage == i, palette = palette, lastIndex = pages.lastIndex,
                    modifier = Modifier.fillMaxSize().pointerInput(i) {
                        detectTapGestures(
                            onPress = { paused = true; tryAwaitRelease(); paused = false },
                            onTap = { o ->
                                scope.launch {
                                    val next = if (o.x > size.width / 2) i + 1 else i - 1
                                    when {
                                        next in pages.indices -> pager.animateScrollToPage(next)
                                        next > pages.lastIndex -> onDismiss()
                                    }
                                }
                            },
                        )
                    })
            }
            // Progress: done cards full, the current one filling, the rest empty.
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pages.indices.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))
                            .drawBehind {
                                val f = when { k < pager.currentPage -> 1f; k == pager.currentPage -> progress.value; else -> 0f }
                                drawRect(Color.White, size = size.copy(width = size.width * f))
                            },
                    )
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CircleButton(Ic.Close, "Close", onDismiss, container = Color(0x40000000), content = Color.White)
                CircleButton(Ic.Share, "Share this card", {
                    val p = pages[pager.currentPage]
                    scope.launch {
                        val cover = p.cover?.let { vm.cover(it) }
                        val headline = if (p.rows.isNotEmpty()) p.rows.mapIndexed { n, r -> "${n + 1}. ${r.name}" }.joinToString("\n") else p.headline
                        val bmp = withContext(Dispatchers.Default) {
                            ShareCard.render(ctx, cover, tint.toArgb(), p.kicker, headline, p.detail, "My Kairos $rangeName")
                        }
                        ShareCard.share(ctx, bmp, "kairos-$rangeName")
                    }
                }, container = Color.White, content = Color.Black)
            }
        }
    }
}

/** One card. When it becomes [active], its parts rise in one after another. */
@Composable
private fun StoryCard(
    vm: MainViewModel, page: StoryPage, index: Int, active: Boolean, palette: List<Color>, lastIndex: Int, modifier: Modifier,
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) { enter.snapTo(0f); enter.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    }
    // Part k of the card appears over its slice of the entrance.
    fun Modifier.rise(k: Int) = graphicsLayer {
        val t = ((enter.value - k * 0.12f) / 0.6f).coerceIn(0f, 1f)
        alpha = t
        translationY = (1f - t) * 28.dp.toPx()
    }
    Box(modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 76.dp)) {
        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                page.rows.isNotEmpty() -> Unit
                index == lastIndex -> Row(Modifier.rise(0), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    palette.forEachIndexed { n, col ->
                        Box(Modifier.size(56.dp).graphicsLayer { translationY = if (n % 2 == 0) 0f else 14.dp.toPx() }.clip(RoundedCornerShape(28.dp)).background(col))
                    }
                }
                page.cover != null -> SongArt(
                    vm, page.cover, 210.dp, corner = 28.dp, large = true,
                    modifier = Modifier.rise(0).graphicsLayer {
                        rotationZ = if (index % 2 == 0) -4f else 4f
                        shadowElevation = 24.dp.toPx(); shape = RoundedCornerShape(28.dp)
                    },
                )
            }
            Text(page.kicker.uppercase(), Modifier.rise(1), style = T.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp), color = Color.White.copy(alpha = 0.8f))
            val headline = page.countMs?.let { ms -> hoursText((ms * ((enter.value - 0.1f) / 0.8f).coerceIn(0f, 1f)).toLong()) } ?: page.headline
            Text(headline, Modifier.rise(2), style = T.big.copy(fontSize = 46.sp, lineHeight = 50.sp), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (page.rows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    page.rows.forEachIndexed { n, r ->
                        Row(Modifier.rise(3 + n), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("${n + 1}", Modifier.width(26.dp), style = T.h2, color = Color.White.copy(alpha = 0.7f))
                            SongArt(vm, r.song, 52.dp, corner = 14.dp)
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = T.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.detail, style = T.small, color = Color.White.copy(alpha = 0.75f))
                            }
                        }
                    }
                }
            }
            Text(page.detail, Modifier.rise(3 + page.rows.size), style = T.body, color = Color.White.copy(alpha = 0.85f))
        }
    }
}
