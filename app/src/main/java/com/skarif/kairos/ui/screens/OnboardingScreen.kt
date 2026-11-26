package com.skarif.kairos.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.ThemeMode
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.ScanState
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.Segmented
import com.skarif.kairos.ui.components.ToggleRow
import com.skarif.kairos.ui.motion.Motion
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import com.skarif.kairos.ui.theme.mixedHeadline

val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

private const val STEPS = 4

/**
 * First run: four short steps you can swipe back through, then finding your songs.
 * 1. Welcome, over a slowly turning record.
 * 2. What Kairos does, three cards rising in one by one.
 * 3. Make it yours: theme, pure black, album colours, and the online lyrics opt-in (off).
 * 4. Your music: why it needs to read audio files, then the permission.
 * Then the scan, counting up, and into the library.
 */
@Composable
fun OnboardingScreen(vm: MainViewModel, onDone: () -> Unit) {
    val c = P.colors
    val context = LocalContext.current
    val scan by vm.scan.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf(false) }
    var step by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[audioPermission] == true || hasAudioPermission(context)) {
            denied = false
            vm.scanNow()
        } else {
            denied = true
        }
    }
    val scanning = scan != ScanState.Idle
    BackHandler(enabled = step > 0 && !scanning) { step-- }

    ScreenBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            // Progress dots and Skip (to the music step: the choices can wait, the permission can't).
            if (!scanning) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(STEPS) { i ->
                            val w by animateDpAsState(if (i == step) 26.dp else 8.dp, Motion.spatial(), label = "dot")
                            val col by animateColorAsState(if (i <= step) c.accent else c.line, tween(250), label = "dotC")
                            Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(col))
                        }
                    }
                    if (step < STEPS - 1) Text(
                        "Skip", style = T.title, color = c.muted,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Skip to music access") { step = STEPS - 1 }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            AnimatedContent(
                if (scanning) STEPS else step,
                Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(Motion.sheet()) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(Motion.sheet()) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(150)))
                },
                label = "onboarding",
            ) { page ->
                when (page) {
                    0 -> WelcomeStep()
                    1 -> TourStep()
                    2 -> ChoicesStep(vm)
                    3 -> MusicStep(denied)
                    else -> ScanStep(scan)
                }
            }

            // The one main button, always in the same place.
            when {
                scanning && scan is ScanState.Done -> Pill("Open my library", {
                    vm.updateSettings { it.copy(onboarded = true) }
                    onDone()
                }, selected = true, big = true, modifier = Modifier.fillMaxWidth())
                scanning -> Spacer(Modifier.height(56.dp))
                step < STEPS - 1 -> Pill(if (step == 0) "Get started" else "Continue", { step++ }, selected = true, big = true, modifier = Modifier.fillMaxWidth())
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Allow access to music", {
                        val perms = buildList {
                            add(audioPermission)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        request.launch(perms.toTypedArray())
                    }, selected = true, big = true, icon = Ic.Music, modifier = Modifier.fillMaxWidth())
                    // Online build: streaming works without reading the phone's music.
                    vm.onlineName?.let { source ->
                        Pill("Just stream from $source", {
                            vm.updateSettings { it.copy(onboarded = true) }
                            onDone()
                        }, big = true, icon = Ic.Discover, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Each part of a step rises in after the one before: [k] is its place in the order. */
@Composable
private fun rememberEntrance(): Animatable<Float, *> {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    return enter
}

private fun Modifier.rise(enter: Animatable<Float, *>, k: Int): Modifier = graphicsLayer {
    val t = ((enter.value - k * 0.12f) / 0.55f).coerceIn(0f, 1f)
    alpha = t
    translationY = (1f - t) * 24.dp.toPx()
}

@Composable
private fun WelcomeStep() {
    val c = P.colors
    val enter = rememberEntrance()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.weight(0.25f))
        SpinningRecord(Modifier.align(Alignment.CenterHorizontally).size(250.dp).rise(enter, 0))
        Spacer(Modifier.weight(0.2f))
        Text(
            mixedHeadline("Welcome to\n" to false, "Kairos" to true), Modifier.rise(enter, 1),
            style = T.h1.copy(fontSize = 46.sp, lineHeight = 50.sp), color = c.text,
        )
        Text(
            "Your music, on your phone, played beautifully. No account, no ads.",
            Modifier.rise(enter, 2), style = T.body.copy(fontSize = 17.sp, lineHeight = 24.sp), color = c.muted,
        )
        Spacer(Modifier.weight(0.15f))
    }
}

/**
 * The welcome's record: a black disc with grooves, a label in the theme's accent, and two light
 * reflections that stay still while it turns (that's what makes it look like it spins).
 * One draw per frame, only while this step is on screen.
 */
@Composable
private fun SpinningRecord(modifier: Modifier) {
    val c = P.colors
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val t = withFrameNanos { it }
            angle = (angle + (t - last) / 1e9f * 60f) % 360f // a slow, calm turn
            last = t
        }
    }
    Box(
        modifier.drawBehind {
            val r = size.minDimension / 2
            val ctr = center
            drawCircle(Color.Black.copy(alpha = 0.18f), r, ctr + Offset(0f, 14f))
            drawCircle(Color(0xFF0C0C0E), r, ctr)
            var g = r * 0.42f
            var k = 0
            while (g < r - 3f) {
                drawCircle(Color.White.copy(alpha = if (k % 7 == 0) 0.09f else 0.04f), g, ctr, style = Stroke(1f))
                g += 5f; k++
            }
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent, 0.08f to Color.White.copy(alpha = 0.16f), 0.16f to Color.Transparent,
                    0.5f to Color.Transparent, 0.58f to Color.White.copy(alpha = 0.12f), 0.66f to Color.Transparent, 1f to Color.Transparent,
                    center = ctr,
                ),
                r, ctr,
            )
            // The label turns, so give it something to see turning: a lighter arc.
            rotate(angle, ctr) {
                drawCircle(c.accent, r * 0.36f, ctr)
                drawArc(
                    c.onAccent.copy(alpha = 0.35f), -30f, 70f, false,
                    topLeft = ctr - Offset(r * 0.26f, r * 0.26f), size = androidx.compose.ui.geometry.Size(r * 0.52f, r * 0.52f),
                    style = Stroke(6f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
            drawCircle(Color(0xFFE9E9E4), r * 0.04f, ctr)
            drawCircle(Color(0xFF0C0C0E), r * 0.02f, ctr)
        },
    )
}

@Composable
private fun TourStep() {
    val c = P.colors
    val enter = rememberEntrance()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(18.dp))
        Text(mixedHeadline("What " to false, "Kairos" to true, " does" to false), Modifier.rise(enter, 0), style = T.h1, color = c.text)
        Spacer(Modifier.height(4.dp))
        TourCard(
            Ic.Lyrics, "Lyrics that sing along",
            "Time-synced from your files, or fetched for you. Words light up as they're sung; share your favourite lines as cards.",
            Modifier.rise(enter, 1),
        )
        TourCard(
            Ic.Radio, "Music that knows you",
            "Song radio, Smart Shuffle and daily mixes, made from what you actually play together. Nothing leaves your phone.",
            Modifier.rise(enter, 2),
        )
        TourCard(
            Ic.Disc, "Made to feel good",
            "Colours from the album art, a background that breathes with the music, a vinyl mode, and your listening as a story.",
            Modifier.rise(enter, 3),
        )
        TourCard(
            Ic.Sliders, "Sounds the way it should",
            "Hi-res and bit-perfect output to a USB DAC, a 10-band equalizer and volume levelling.",
            Modifier.rise(enter, 4),
        )
    }
}

@Composable
private fun TourCard(icon: ImageVector, title: String, body: String, modifier: Modifier) {
    val c = P.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(c.card).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = T.title.copy(fontWeight = FontWeight.Bold), color = c.text)
            Text(body, style = T.small.copy(lineHeight = 19.sp), color = c.muted)
        }
    }
}

@Composable
private fun ChoicesStep(vm: MainViewModel) {
    val c = P.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val enter = rememberEntrance()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(18.dp))
        Text(mixedHeadline("Make it " to false, "yours" to true), Modifier.rise(enter, 0), style = T.h1, color = c.text)
        Text("All of this can be changed later in Settings.", Modifier.rise(enter, 1), style = T.body, color = c.muted)
        ChoiceCard(Modifier.rise(enter, 2)) {
            Text("Theme", style = T.title.copy(fontWeight = FontWeight.Bold), color = c.text)
            Segmented(listOf("Light", "Dark", "System"), s.themeMode.ordinal, { i -> vm.updateSettings { it.copy(themeMode = ThemeMode.entries[i]) } })
            ToggleRow("Pure black in dark mode", s.pureBlack, { v -> vm.updateSettings { it.copy(pureBlack = v) } }, "Saves battery on AMOLED screens")
            ToggleRow("Colours from the album art", s.colorFromArt, { v -> vm.updateSettings { it.copy(colorFromArt = v) } }, "The whole app takes on each song's colours")
        }
        ChoiceCard(Modifier.rise(enter, 3)) {
            ToggleRow(
                "Fetch lyrics online", s.fetchLyricsOnline, { v -> vm.updateSettings { it.copy(fetchLyricsOnline = v) } },
                "When a song has none, look them up on lrclib.net. Only the title, artist, album and length are sent. Off means Kairos never goes online.",
            )
        }
    }
}

@Composable
private fun ChoiceCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = P.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@Composable
private fun MusicStep(denied: Boolean) {
    val c = P.colors
    val enter = rememberEntrance()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.weight(0.3f))
        // Your songs, gathering: three discs fanned out.
        Box(Modifier.fillMaxWidth().height(200.dp).rise(enter, 0), contentAlignment = Alignment.Center) {
            listOf(-1, 1, 0).forEach { k ->
                Box(
                    Modifier.size(130.dp).graphicsLayer {
                        translationX = k * 62.dp.toPx(); rotationZ = k * 10f
                        shadowElevation = 10.dp.toPx(); shape = RoundedCornerShape(30.dp)
                    }.clip(RoundedCornerShape(30.dp)).background(
                        Brush.linearGradient(listOf(c.accent, androidx.compose.ui.graphics.lerp(c.accent, if (k == 0) Color.White else Color.Black, 0.35f))),
                    ),
                    contentAlignment = Alignment.Center,
                ) { if (k == 0) Icon(Ic.Music, null, tint = c.onAccent, modifier = Modifier.size(40.dp)) }
            }
        }
        Spacer(Modifier.weight(0.15f))
        Text(mixedHeadline("All " to false, "your music" to true, ", right on " to false, "this phone" to true), Modifier.rise(enter, 1), style = T.h1, color = c.text)
        Text(
            "Kairos needs to read the audio files on this phone to build your library. It never uploads them. " +
                "Notifications show playback controls while music plays.",
            Modifier.rise(enter, 2), style = T.body.copy(fontSize = 16.sp, lineHeight = 23.sp), color = c.muted,
        )
        if (denied) {
            Text(
                "Access was not allowed. You can allow it in Android Settings > Apps > Kairos > Permissions.",
                style = T.body.copy(fontWeight = FontWeight.SemiBold), color = c.text,
            )
        }
        Spacer(Modifier.weight(0.1f))
    }
}

@Composable
private fun ScanStep(scan: ScanState) {
    val c = P.colors
    val found = (scan as? ScanState.Done)?.songs ?: 0
    // Count up to the number found, quickly, so it lands while you watch.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(found) { if (found > 0) shown.animateTo(found.toFloat(), tween(1200, easing = FastOutSlowInEasing)) }
    // While reading, a slow breathing ring around the number.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(scan is ScanState.Done) {
        if (scan is ScanState.Done) { pulse.animateTo(0f, tween(300)); return@LaunchedEffect }
        while (true) { pulse.animateTo(1f, tween(900, easing = LinearEasing)); pulse.snapTo(0f) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.weight(0.3f))
        Box(
            Modifier.fillMaxWidth().height(240.dp).drawBehind {
                val r = size.minDimension / 2.4f
                val p = pulse.value
                if (p > 0f) drawCircle(c.accent.copy(alpha = 0.35f * (1f - p)), r * (0.8f + 0.5f * p), center, style = Stroke(6f))
                drawCircle(c.accent, r * 0.8f, center)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (found > 0) "%,d".format(shown.value.toInt()) else "…",
                style = T.big.copy(fontSize = 54.sp, lineHeight = 56.sp), color = c.onAccent,
            )
        }
        Spacer(Modifier.weight(0.1f))
        Text(
            if (scan is ScanState.Done) mixedHeadline("Your " to false, "library" to true, " is ready" to false)
            else mixedHeadline("Finding " to false, "your" to true, " songs" to false),
            style = T.h1, color = c.text,
        )
        Text(
            if (scan is ScanState.Done) "$found songs found. Lyrics are read when you open a song."
            else "Reading your music folders. This takes a few seconds.",
            style = T.body, color = c.muted,
        )
        Spacer(Modifier.weight(0.1f))
    }
}
