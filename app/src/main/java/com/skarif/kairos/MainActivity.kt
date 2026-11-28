package com.skarif.kairos

import android.app.Activity
import com.skarif.kairos.ui.components.frosted
import androidx.compose.ui.graphics.layer.drawLayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.ProvideNavScope
import com.skarif.kairos.ui.screens.CollectionScreen
import com.skarif.kairos.ui.screens.FoldersScreen
import com.skarif.kairos.ui.screens.HomeScreen
import com.skarif.kairos.ui.screens.LibraryScreen
import com.skarif.kairos.ui.screens.MenuDrawer
import com.skarif.kairos.ui.screens.NewPlaylistScreen
import com.skarif.kairos.ui.screens.OnboardingScreen
import com.skarif.kairos.ui.screens.PlaylistsScreen
import com.skarif.kairos.ui.screens.SearchScreen
import com.skarif.kairos.ui.screens.SettingsScreen
import com.skarif.kairos.ui.screens.StatsScreen
import com.skarif.kairos.ui.screens.hasAudioPermission
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.PlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.skarif.kairos.ui.motion.Motion
import com.skarif.kairos.ui.player.LocalPlayer
import com.skarif.kairos.ui.player.PlayerSheet
import com.skarif.kairos.ui.player.PlayerPage
import com.skarif.kairos.ui.player.PlayerController
import com.skarif.kairos.ui.screens.SortSheet
import com.skarif.kairos.ui.screens.SleepTimerSheet
import com.skarif.kairos.ui.screens.EqualizerSheet

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A first run (or a restore without the music permission) starts on the welcome screen.
        // Load asynchronously so we don't block the main thread on DataStore disk I/O in onCreate.
        setContent {
            var start by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                val onboarded = withContext(Dispatchers.IO) {
                    (application as PlayerApp).container.settings.load().onboarded
                }
                start = when {
                    onboarded && hasAudioPermission(this@MainActivity) -> "home"
                    // Online build: streaming needs no permission, so someone who skipped the library starts on Explore.
                    onboarded && com.skarif.kairos.online.Flavor.online != null -> "home"
                    else -> "onboarding"
                }
            }
            val destination = start
            if (destination == null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else {
                PlayerRoot(destination)
            }
        }
        com.skarif.kairos.perf.PerfLog.install(window)
    }

    override fun onResume() { super.onResume(); com.skarif.kairos.perf.PerfLog.setTracking(true) }
    override fun onPause() { super.onPause(); com.skarif.kairos.perf.PerfLog.setTracking(false) }
}

private val TABS = Motion.TABS

/** Screens whose top edge is dark in every theme. */
private val DARK_TOP = setOf("collection/{key}")

/** Bottom sheets opened over whatever is showing, including the player. */
private enum class Tool { SLEEP, EQ, SORT }


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlayerRoot(start: String, vm: MainViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val albumNow by vm.albumColors.collectAsStateWithLifecycle()
    // A new song's colours recolour the whole app, which is a big redraw. Applying them the moment
    // you tap a song landed on the player's first opening frames; a short delay lets it open first.
    var album by remember { mutableStateOf(albumNow) }
    LaunchedEffect(albumNow) {
        if (album != null && albumNow != null) {
            // Wait until the player has finished opening, not a fixed time that could land mid-animation.
            kotlinx.coroutines.delay(120)
            androidx.compose.runtime.snapshotFlow { com.skarif.kairos.ui.motion.PlayerMotion.animating }.first { !it }
            kotlinx.coroutines.delay(120)
        }
        com.skarif.kairos.perf.PerfLog.state("theme", "recolouring")
        album = albumNow
        kotlinx.coroutines.delay(400)
        com.skarif.kairos.perf.PerfLog.state("theme", null)
    }
    PlayerTheme(settings, album) {
        val context = LocalContext.current
        val nav = rememberNavController()
        val scope = rememberCoroutineScope()
        val player = remember { PlayerController(scope) }
        var menuOpen by remember { mutableStateOf(false) }
        var tool by remember { mutableStateOf<Tool?>(null) }
        val sheetDepth = androidx.compose.animation.core.animateFloatAsState(if (tool != null) 1f else 0f, Motion.sheet(), label = "sheetDepth")
        val song by vm.currentSong.collectAsStateWithLifecycle()
        // MediaStore may have changed since last time; a rescan is cheap.
        LaunchedEffect(Unit) { if (hasAudioPermission(context)) vm.scanNow() }
        // Online build on Android 9: saving a song to Music/Kairos needs the storage permission.
        val askStorage = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted -> if (!granted) vm.notify("Saving to the phone needs storage access") }
        if (vm.onlineName != null) com.skarif.kairos.ui.screens.UpdateDialog(vm)
        LaunchedEffect(Unit) { vm.onlineUi.askStorage.collect { askStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) } }
        fun openCollection(key: String) =
            // Online build: liked artists / albums are online pages, not library collections.
            if (key.startsWith("online:")) nav.push("online/liked/" + key.removePrefix("online:"))
            else nav.push("collection/" + Uri.encode(key))
        fun openAlbum(id: Long) = openCollection("album:$id")
        val openMenu = { menuOpen = true }
        val openScope = androidx.compose.runtime.rememberCoroutineScope()
        // Open only once there is a song to show: the very first play used to start the opening
        // animation before the player existed, so it popped in halfway through.
        val openPlayer: () -> Unit = {
            openScope.launch {
                // Wait briefly for a song that's just been started; with none, don't open. Opening
                // with no song once left the player "open" but not on screen, and the app behind it
                // stops drawing while the player covers it: a black screen.
                val s = kotlinx.coroutines.withTimeoutOrNull(1500) { vm.currentSong.first { it != null } }
                if (s != null) player.expand() else vm.notify("Play a song first")
            }
        }

        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        LaunchedEffect(route) { com.skarif.kairos.perf.PerfLog.state("screen", route) }
        val playerUp by remember { derivedStateOf { player.progress.value > 0.5f } }
        // The player exists only with a song, and not during onboarding.
        val playerShown = route != null && route != "onboarding" && song != null
        // Safety net: never leave the player "open" with nothing on screen to show it.
        // Whatever asks for it (the dock, the menu's Now playing or Queue), while there's no player to
        // show, any opening is undone at once.
        LaunchedEffect(playerShown) {
            if (playerShown) return@LaunchedEffect
            androidx.compose.runtime.snapshotFlow { player.progress.value }.collect { if (it > 0f) player.progress.snapTo(0f) }
        }

        // Bar icons must contrast with what is behind them: the player's cover is dark at the top,
        // album pages too; everything else follows the theme.
        val view = LocalView.current
        val darkUi = P.colors.dark
        val darkTop = if (playerUp) darkUi || player.page == PlayerPage.MAIN else darkUi || route in DARK_TOP
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTop
                    isAppearanceLightNavigationBars = !darkUi
                }
            }
        }

        // Shown once after a crash, so the report can be copied and sent.
        var crash by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            crash = withContext(Dispatchers.IO) {
                java.io.File(context.filesDir, PlayerApp.CRASH_FILE).takeIf { it.exists() }?.readText()
            }
        }
        fun clearCrash() {
            crash = null
            scope.launch(Dispatchers.IO) { java.io.File(context.filesDir, PlayerApp.CRASH_FILE).delete() }
        }
        crash?.let { text ->
            val clipboard = androidx.compose.ui.platform.LocalClipboard.current
            com.skarif.kairos.ui.components.KairosDialog(
                onDismissRequest = ::clearCrash,
                containerColor = P.colors.sheet,
                title = { Text("Kairos stopped last time", color = P.colors.text) },
                text = {
                    Text(
                        text.take(1800), color = P.colors.muted,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton({
                        scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Kairos crash", text))) }
                        clearCrash()
                    }) { Text("Copy report", color = P.colors.text) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(::clearCrash) { Text("Close", color = P.colors.muted) }
                },
            )
        }

        // Every list gets the iOS rubber band instead of Android's stretch (ui/motion/Scroll.kt).
        var collectionMenu by remember { mutableStateOf<String?>(null) }
        val backArt by vm.artwork.collectAsStateWithLifecycle()
        val backPlaying by vm.isPlaying.collectAsStateWithLifecycle()
        val backdrop = com.skarif.kairos.ui.components.rememberBackdrop(backArt, settings.blurBackground, backPlaying, settings.backgroundGlow)
        // The app, recorded once so floating bars (dock, mini player) can blur what is under them.
        // Only while something floats over it, and only where real blur exists (Android 12+).
        val appLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
        val appBlur = android.os.Build.VERSION.SDK_INT >= 31 && (playerShown || route in TABS)
        val onlineNav = remember(nav) {
                com.skarif.kairos.ui.screens.OnlineNav(
                    search = { q, kind ->
                        vm.onlineUi.setSearchOnline(true)
                        vm.onlineUi.setKind(kind)
                        vm.setQuery(q)
                        player.collapse()
                        nav.tab("search")
                    },
                    collection = { id -> player.collapse(); nav.push("online/collection/${Uri.encode(id)}") },
                    artist = { id, name -> player.collapse(); nav.push("online/artist/${Uri.encode(id)}?name=${Uri.encode(name)}") },
                )
            }
        CompositionLocalProvider(
            LocalPlayer provides player, com.skarif.kairos.ui.components.LocalBackdrop provides backdrop,
            com.skarif.kairos.ui.components.LocalAppLayer provides (if (appBlur) appLayer else null),
            // Mini player: 12 dp above the nav bar, 76 dp tall, plus a little air above it.
            com.skarif.kairos.ui.screens.LocalBottomClearance provides (if (song != null) 108.dp else 0.dp),
            // One long-press menu for every album, artist, genre, playlist and mix tile.
            com.skarif.kairos.ui.screens.LocalCollectionMenu provides { k -> collectionMenu = k },
            com.skarif.kairos.ui.screens.LocalOnlineNav provides onlineNav,
            androidx.compose.foundation.LocalOverscrollFactory provides com.skarif.kairos.ui.motion.IosOverscrollFactory,
        ) {
        // Black behind everything: what shows around the app when it sinks back behind the player.
        // nestedScroll(scrollWatch): the living background pauses while any list scrolls.
        Box(Modifier.fillMaxSize().background(Color.Black).nestedScroll(backdrop.scrollWatch)) {
            NavHost(
                nav, startDestination = start,
                // iOS sheet depth: as the player (or a sheet) comes up, the app behind sinks back,
                // rounds its corners and darkens. Darkening is drawn as a scrim, not alpha: alpha
                // on a full-screen layer forces an extra full-screen pass every frame. Once the
                // player covers it completely, the app isn't drawn at all.
                modifier = Modifier
                    .graphicsLayer {
                        val p = maxOf(player.progress.value.coerceIn(0f, 1f), sheetDepth.value * 0.6f)
                        // Skip drawing the app only while the player really covers it.
                        if (p >= 0.999f && playerShown) { alpha = 0f; return@graphicsLayer }
                        if (p > 0f) {
                            val sc = 1f - 0.06f * p
                            scaleX = sc; scaleY = sc
                            shape = androidx.compose.foundation.shape.RoundedCornerShape((24f * p).dp)
                            clip = true
                        }
                    }
                    .drawWithContent {
                        val p = maxOf(player.progress.value.coerceIn(0f, 1f), sheetDepth.value * 0.6f)
                        // Re-recorded only while nothing moves over it. While the player opens the last
                        // recording is simply replayed (a cheap display-list draw, no page redraw), and
                        // switching back to drawing the page directly would be a one-frame hitch.
                        if (appBlur) {
                            if (p == 0f || appLayer.size == androidx.compose.ui.unit.IntSize.Zero) appLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(appLayer)
                        } else drawContent()
                        if (p > 0f) drawRect(Color.Black, alpha = 0.4f * p)
                    },
                enterTransition = Motion.enter,
                exitTransition = Motion.exit,
                popEnterTransition = Motion.popEnter,
                popExitTransition = Motion.popExit,
                // The back *gesture* has its own pair (Navigation 2.10); left unset it falls back to
                // Material's shrink-into-a-card. Use the same iOS slide, following the thumb.
                predictivePopEnterTransition = { _ -> Motion.popEnter(this) },
                predictivePopExitTransition = { _ -> Motion.popExit(this) },
            ) {
                screen("onboarding") {
                    OnboardingScreen(vm) {
                        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                    }
                }
                screen("home") {
                    // Online build: one Home for streaming and the music on this phone.
                    if (vm.onlineName != null) com.skarif.kairos.ui.screens.ExploreScreen(
                        vm, onMenu = openMenu, onOpenNowPlaying = openPlayer, onDownloads = { nav.push("downloads") },
                        onOpenCollection = ::openCollection, onSearch = { nav.tab("search") },
                        onShelf = { k -> nav.push("online/shelf/" + Uri.encode(k)) },
                    ) else HomeScreen(
                        vm, onMenu = openMenu, onSearch = { nav.tab("search") }, onOpenCollection = ::openCollection,
                        onOpenNowPlaying = openPlayer, onPlaylists = { nav.push("playlists") },
                        onLibrary = { nav.tab("library") },
                    )
                }
                screen("library") {
                    LibraryScreen(
                        vm, onOpenNowPlaying = openPlayer, onSettings = { nav.push("settings") },
                        onFolders = { nav.push("folders") }, onSort = { tool = Tool.SORT }, onOpenAlbum = ::openAlbum, onOpenCollection = ::openCollection,
                        onPlaylists = { nav.push("playlists") }, onDownloads = { nav.push("downloads") },
                    )
                }
                screen("stats") { StatsScreen(vm, onMenu = openMenu, onSettings = { nav.push("settings") }) }
                screen("search") {
                    SearchScreen(vm, onMenu = openMenu, onOpenCollection = ::openCollection, onOpenNowPlaying = openPlayer)
                }
                screen("online/shelf/{key}") { e ->
                    com.skarif.kairos.ui.screens.ShelfScreen(vm, Uri.decode(e.arguments?.getString("key") ?: ""), onBack = { nav.back() }, onOpenNowPlaying = openPlayer)
                }
                screen("online/spotify") {
                    com.skarif.kairos.ui.screens.SpotifyImportScreen(vm, onBack = { nav.back() }, onOpenPlaylist = { id -> openCollection("playlist:$id") })
                }
                screen("online/liked/{kind}") { e ->
                    com.skarif.kairos.ui.screens.LikedOnlineScreen(vm, artists = e.arguments?.getString("kind") == "artists", onBack = { nav.back() })
                }
                screen("downloads") { com.skarif.kairos.ui.screens.DownloadsScreen(vm, onBack = { nav.back() }, onOpenNowPlaying = openPlayer) }
                screen("online/collection/{id}") { e ->
                    val id = Uri.decode(e.arguments?.getString("id") ?: "")
                    com.skarif.kairos.ui.screens.OnlineCollectionScreen(vm, id, onBack = { nav.back() }, onOpenNowPlaying = openPlayer)
                }
                screen("online/artist/{id}?name={name}") { e ->
                    val id = Uri.decode(e.arguments?.getString("id") ?: "")
                    val name = Uri.decode(e.arguments?.getString("name") ?: "")
                    com.skarif.kairos.ui.screens.OnlineArtistScreen(vm, id, name, onBack = { nav.back() }, onOpenNowPlaying = openPlayer)
                }
                screen("playlists") {
                    PlaylistsScreen(vm, onBack = { nav.back() }, onNew = { nav.push("newplaylist?fromQueue=false") }, onOpenCollection = ::openCollection, onImport = { nav.push("online/spotify") })
                }
                screen("newplaylist?fromQueue={fromQueue}") { e ->
                    val fromQueue = e.arguments?.getString("fromQueue") == "true"
                    val initial = remember { if (fromQueue) vm.now.value.queue.map { it.songId } /* online songs have negative ids and belong in it too */ else emptyList() }
                    NewPlaylistScreen(vm, onDone = { nav.back() }, initialSongs = initial)
                }
                screen("collection/{key}") { e ->
                    val key = Uri.decode(e.arguments?.getString("key") ?: "")
                    CollectionScreen(vm, key, onBack = { nav.back() }, onOpenNowPlaying = openPlayer, onOpenAlbum = ::openAlbum, onOpenCollection = ::openCollection)
                }
                screen("settings") {
                    SettingsScreen(vm, onBack = { nav.back() }, onFolders = { nav.push("folders") }, onEqualizer = { tool = Tool.EQ })
                }
                screen("folders") { FoldersScreen(vm, onBack = { nav.back() }) }
            }

            // Where the mini player rests: above the dock on tabs, near the bottom edge elsewhere.
            val showPlayer = playerShown
            val density = androidx.compose.ui.platform.LocalDensity.current
            val navBar = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(density).toFloat()
            val targetBottom = navBar + with(density) { (if (route in TABS) 12.dp + 76.dp + 10.dp else 12.dp).toPx() }
            val miniBottom by androidx.compose.animation.core.animateFloatAsState(targetBottom, Motion.spatial(), label = "miniBottom")

            // A soft fade from just above the mini player down to the bottom edge, so content
            // scrolling underneath recedes. It follows the mini player as it moves and fades out
            // as the player opens. Drawn under the dock and the player.
            if (showPlayer) {
                val bg = P.colors.bg
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val p = player.progress.value.coerceIn(0f, 1f)
                        if (p >= 1f) return@drawBehind
                        val top = size.height - miniBottom - 76.dp.toPx() - 48.dp.toPx()
                        drawRect(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to bg.copy(alpha = 0f), 0.35f to bg.copy(alpha = 0.8f), 1f to bg.copy(alpha = 0.95f),
                                startY = top, endY = size.height,
                            ),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                            size = androidx.compose.ui.geometry.Size(size.width, size.height - top),
                            alpha = 1f - p,
                        )
                    },
                )
            }

            // Dock. It sinks away as the player opens, tied to the same progress as the player.
            AnimatedVisibility(
                route in TABS,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(Motion.spatial()) { it } + fadeIn(Motion.fadeIn()),
                exit = slideOutVertically(Motion.spatial()) { it } + fadeOut(Motion.fadeOut()),
            ) {
                val bg = P.colors.bg
                Column(
                    Modifier
                        .graphicsLayer {
                            val p = player.progress.value
                            translationY = p * size.height
                            alpha = 1f - p
                        }
                        // Soft fade behind the dock, so cards scrolling under it recede. With a song
                        // loaded the player layer draws this fade instead, so it moves with the mini player.
                        .drawBehind {
                            if (song == null) drawRect(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to bg.copy(alpha = 0f), 0.3f to bg.copy(alpha = 0.8f), 1f to bg.copy(alpha = 0.95f),
                                    startY = -40.dp.toPx(), endY = size.height,
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(-size.width, -40.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(size.width * 3, size.height + 40.dp.toPx()),
                            )
                        }
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (song != null) Spacer(Modifier.height(74.dp)) // room for the mini player
                    Dock(route = route, onTab = { if (it == route) vm.reselectTab(it) else nav.tab(it) }, onNow = openPlayer, frostOn = { player.progress.value < 0.01f })
                }
            }

            // The player: mini player above the dock, the whole screen when open.
            if (showPlayer) {
                PlayerSheet(
                    vm, player, miniBottom,
                    onAlbum = { id -> if (song?.volume != com.skarif.kairos.data.db.ONLINE_VOLUME) openAlbum(id) },
                    onOpenCollection = { key ->
                        // An online song's artist isn't in the library: look them up online instead.
                        if (song?.volume == com.skarif.kairos.data.db.ONLINE_VOLUME && key.startsWith("artist:") &&
                            vm.artistByKey.value[com.skarif.kairos.data.normalize(key.removePrefix("artist:"))] == null
                        ) onlineNav.artist("_", key.removePrefix("artist:"))
                        else openCollection(key)
                    },
                    onSleep = { tool = Tool.SLEEP },
                    onEqualizer = { tool = Tool.EQ },
                    onSaveQueue = { nav.push("newplaylist?fromQueue=true") },
                )
            }

            // "Removed from queue — Undo" and other short messages, above the mini player and dock.
            com.skarif.kairos.ui.screens.NoticeBar(
                vm,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(bottom = when { playerUp -> 24.dp; song != null && route in TABS -> 182.dp; song != null -> 108.dp; route in TABS -> 96.dp; else -> 24.dp }),
            )

            AnimatedVisibility(menuOpen, enter = fadeIn(Motion.fadeIn()) + slideInHorizontally(Motion.spatial()) { -it / 3 }, exit = fadeOut(Motion.fadeOut()) + slideOutHorizontally(Motion.spatial()) { -it / 3 }) {
                MenuDrawer(vm, onClose = { menuOpen = false }) { r ->
                    menuOpen = false
                    when (r) {
                        "now" -> if (song != null) player.expand() else vm.notify("Play a song first")
                        "queue" -> if (song != null) player.expand(PlayerPage.QUEUE) else vm.notify("Play a song first")
                        "eq" -> tool = Tool.EQ
                        "sleep" -> tool = Tool.SLEEP
                        in TABS -> nav.tab(r)
                        "newplaylist" -> nav.push("newplaylist?fromQueue=false")
                        else -> nav.push(r)
                    }
                }
            }
            BackHandler(enabled = menuOpen) { menuOpen = false }

            collectionMenu?.let { k ->
                com.skarif.kairos.ui.screens.CollectionActionsSheet(vm, k, ::openCollection, onDismiss = { collectionMenu = null })
            }

            when (tool) {
                Tool.SLEEP -> SleepTimerSheet(vm, onBack = { tool = null })
                Tool.EQ -> EqualizerSheet(vm, onBack = { tool = null })
                Tool.SORT -> SortSheet(vm, onBack = { tool = null })
                null -> Unit
            }

            // Edge glow: the album's colour arriving with each new song, and a lighter one at the
            // "Kairos moment" (the chorus first hitting). Over everything, draw-only.
            val glow = com.skarif.kairos.ui.components.rememberEdgeGlow(scope)
            val glowSong = song?.id
            var seenFirst by remember { mutableStateOf(false) }
            LaunchedEffect(glowSong) {
                if (glowSong == null) return@LaunchedEffect
                if (seenFirst) glow.flash(1f) else seenFirst = true // not on app start
            }
            val chorus by vm.chorusMs.collectAsStateWithLifecycle()
            val momentHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
            val momentLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(glowSong, chorus) {
                val at = chorus ?: return@LaunchedEffect
                // Once per song: when playback crosses the chorus (not when you seek far past it).
                vm.position.first { p -> vm.isPlaying.value && p in at..(at + 1_500) }
                // Only when you can see it: no glow queued up or buzz in a pocket.
                if (momentLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    glow.moment()
                    momentHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                }
            }
            com.skarif.kairos.ui.components.EdgeGlowOverlay(glow, P.colors.accent)
        }
        }
    }
}

/** A NavHost destination that also tells shared-card transitions which animation it is in. */
private fun NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) =
    composable(route) { entry -> ProvideNavScope(this) { content(entry) } }

/**
 * Page pushes and backs are ignored while the current page is still animating: a double tap used
 * to push the same page twice, or pop two pages at once.
 */
private fun NavHostController.settled() =
    currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
private fun NavHostController.push(route: String) { if (settled()) navigate(route) }
private fun NavHostController.back() { if (settled()) popBackStack() }

/** Tab-style navigation: one copy of each tab, Home at the bottom of the stack. */
private fun NavHostController.tab(route: String) = navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Design reference: the frosted circle dock with the coloured centre button. */
@Composable
private fun Dock(route: String?, onTab: (String) -> Unit, onNow: () -> Unit, modifier: Modifier = Modifier, frostOn: () -> Boolean = { true }) {
    val c = P.colors
    val idle = if (c.dark) Color(0x1FFFFFFF) else Color(0xB3FFFFFF)
    val dockBase = if (c.dark) Color(0xFF3C3C3A) else Color(0xFFC4C7BE)
    Row(
        modifier.frosted(RoundedCornerShape(40.dp), dockBase, lift = -0.10f, active = frostOn).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun tab(r: String, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
            val on = route == r
            CircleButton(
                icon, label, { onTab(r) },
                container = if (on) Color.White else idle,
                content = if (on || !c.dark) Color(0xFF151515) else c.text,
            )
        }
        tab("home", Ic.Home, "Home")
        tab("library", Ic.Library, "Library")
        CircleButton(Ic.Play, "Now playing", onNow, size = 60.dp, container = c.accent, content = c.onAccent, iconSize = 22.dp)
        tab("stats", Ic.Stats, "Statistics")
        tab("search", Ic.Search, "Search")
    }
}
