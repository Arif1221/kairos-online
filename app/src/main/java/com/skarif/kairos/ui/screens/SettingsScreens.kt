package com.skarif.kairos.ui.screens

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.LyricsPreference
import com.skarif.kairos.data.OutputMode
import com.skarif.kairos.data.ReplayGainMode
import com.skarif.kairos.data.ThemeMode
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.ScanState
import com.skarif.kairos.ui.components.Card
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.RadioCard
import com.skarif.kairos.ui.components.ScreenBackground
import com.skarif.kairos.ui.components.Segmented
import com.skarif.kairos.ui.components.ToggleRow
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T
import com.skarif.kairos.ui.theme.mixedHeadline

private val SWATCHES = listOf(
    0xFFBD3657.toInt() to "Raspberry", 0xFF288278.toInt() to "Teal", 0xFF5B85B4.toInt() to "Blue",
    0xFFEF75AD.toInt() to "Pink", 0xFFF8C630.toInt() to "Yellow",
)

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircleButton(Ic.Back, "Back", onBack)
        Text(title, Modifier.weight(1f), style = T.title, color = P.colors.text, textAlign = TextAlign.Center)
        Box(Modifier.size(52.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onFolders: () -> Unit, onEqualizer: () -> Unit) {
    val c = P.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.addLrcFolder(uri)
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) vm.backupTo(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.restoreFrom(uri)
    }
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirmClear by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (confirmClear) ConfirmDialog(
        "Clear listening history?",
        "Stats, Most played, On repeat and your mixes start over. Favourites and playlists stay. Back up first if you might want it back.",
        confirm = "Clear", dismiss = "Keep",
        onConfirm = { vm.clearHistory(); confirmClear = false }, onDismiss = { confirmClear = false },
    )

    PageColumn {
            Header("Settings", onBack)
            Text(mixedHeadline("Make it " to false, "yours" to true), style = T.h1, color = c.text)

            Card(Modifier) {
                Text("Theme", style = T.h2, color = c.text)
                Segmented(listOf("Light", "Dark", "System"), s.themeMode.ordinal, { i ->
                    vm.updateSettings { it.copy(themeMode = ThemeMode.entries[i]) }
                })
                Text("Colour", style = T.small, color = c.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("From album art", { vm.updateSettings { it.copy(colorFromArt = true) } }, selected = s.colorFromArt, icon = Ic.Palette)
                    SWATCHES.forEach { (argb, name) ->
                        val on = !s.colorFromArt && s.fixedColor == argb
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .border(3.dp, if (on) c.text else Color.Transparent, CircleShape)
                                .padding(5.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .selectable(selected = on, role = Role.RadioButton) {
                                    vm.updateSettings { it.copy(colorFromArt = false, fixedColor = argb) }
                                }
                                .semantics { contentDescription = "Use $name everywhere" },
                        )
                    }
                }
                ToggleRow("Blurred album background", s.blurBackground, { v -> vm.updateSettings { it.copy(blurBackground = v) } })
                ToggleRow("Pure black in dark mode", s.pureBlack, { v -> vm.updateSettings { it.copy(pureBlack = v) } }, "Saves battery on AMOLED screens")
            }

            Card(Modifier) {
                Text("Lyrics", style = T.h2, color = c.text)
                ToggleRow(
                    "Read embedded lyrics", s.readEmbeddedLyrics, { v -> vm.updateSettings { it.copy(readEmbeddedLyrics = v) } },
                    "Synced and plain lyrics inside MP3, FLAC and M4A files",
                )
                ToggleRow(
                    "Use .lrc files", s.readLrcFiles, { v -> vm.updateSettings { it.copy(readLrcFiles = v) } },
                    "Same name as the song, in the same folder",
                )
                Text("When a song has both, use", style = T.small, color = c.muted)
                Segmented(listOf("Embedded", ".lrc file"), s.lyricsPreference.ordinal, { i ->
                    vm.updateSettings { it.copy(lyricsPreference = LyricsPreference.entries[i]) }
                })
                Text(
                    "Android only lets this app see audio files. Allow the folders that hold your .lrc files:",
                    style = T.small, color = c.muted,
                )
                s.lrcFolderUris.forEach { uri ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(readableTree(uri), Modifier.weight(1f), style = T.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        CircleButton(Ic.Close, "Remove ${readableTree(uri)}", { vm.removeLrcFolder(uri) }, size = 44.dp, container = c.tint)
                    }
                }
                Pill("Allow a folder", { pickFolder.launch(null) }, icon = Ic.Plus, selected = true)

                ToggleRow(
                    "Fetch lyrics online", s.fetchLyricsOnline, { v -> vm.updateSettings { it.copy(fetchLyricsOnline = v) } },
                    "When a song has none, look it up on lrclib.net. Sends only the title, artist, album and length; lyrics are saved so each song is fetched once",
                )
                if (s.fetchLyricsOnline) {
                    Text("Fetch", style = T.small, color = c.muted)
                    Segmented(listOf("Time-synced", "Plain text"), s.onlineLyricsType.ordinal, { i ->
                        vm.updateSettings { it.copy(onlineLyricsType = com.skarif.kairos.data.OnlineLyricsType.entries[i]) }
                    })
                    Text(
                        if (s.onlineLyricsType == com.skarif.kairos.data.OnlineLyricsType.SYNCED)
                            "Synced lyrics when they exist, plain text otherwise. Also replaces plain lyrics from your files with synced ones."
                        else "Just the words, no timing. Your own lyrics always come first.",
                        style = T.small, color = c.muted,
                    )
                    Pill("Clear downloaded lyrics", vm::clearOnlineLyrics, icon = Ic.Close)
                }
            }

            Card(Modifier) {
                Text("Audio quality", style = T.h2, color = c.text)
                now.output?.let { Text("Output now: $it", style = T.title, color = c.text) }
                RadioCard(
                    "Hi-res", "32-bit float output, so 24-bit files keep their detail. EQ works.",
                    s.outputMode == OutputMode.HI_RES, { vm.updateSettings { it.copy(outputMode = OutputMode.HI_RES) } },
                )
                RadioCard(
                    "Bit-perfect (USB DAC)",
                    if (Build.VERSION.SDK_INT >= 34) "Sends the file to a USB DAC untouched. Volume, EQ and ReplayGain are turned off."
                    else "Needs Android 14 or newer.",
                    s.outputMode == OutputMode.BIT_PERFECT,
                    { vm.updateSettings { it.copy(outputMode = OutputMode.BIT_PERFECT) } },
                    enabled = Build.VERSION.SDK_INT >= 34,
                )
                RadioCard(
                    "Standard", "16-bit through Android's mixer. Works with every speaker and headphone.",
                    s.outputMode == OutputMode.STANDARD, { vm.updateSettings { it.copy(outputMode = OutputMode.STANDARD) } },
                )
                if (now.outputNeedsRestart) {
                    Text("Close and reopen the app to switch output mode.", style = T.small, color = c.text)
                }
                Text("Volume levelling (ReplayGain)", style = T.small, color = c.muted)
                Segmented(listOf("Off", "Track", "Album"), s.replayGain.ordinal, { i ->
                    vm.updateSettings { it.copy(replayGain = ReplayGainMode.entries[i]) }
                })
            }

            Card(Modifier) {
                Text("Playback and suggestions", style = T.h2, color = c.text)
                ToggleRow(
                    "Resume where you left off", s.resumeQueue, { v -> vm.updateSettings { it.copy(resumeQueue = v) } },
                    "Opening the app brings back your last queue, song and position",
                )
                ToggleRow(
                    "Keep screen on with lyrics", s.keepScreenOnLyrics, { v -> vm.updateSettings { it.copy(keepScreenOnLyrics = v) } },
                    "So you can read along without the screen turning off",
                )
                ToggleRow(
                    "Autoplay", s.autoplay, { v -> vm.updateSettings { it.copy(autoplay = v) } },
                    "When the queue ends, keep playing similar songs from your library",
                )
                ToggleRow(
                    "Smart Shuffle", s.smartShuffle, { v -> vm.updateSettings { it.copy(smartShuffle = v) } },
                    "Shuffling a playlist or album adds a similar song after every three",
                )
                val hiddenCount = s.hiddenSongs.size + s.snoozedSongs.size
                Text(
                    if (hiddenCount == 0) "No hidden or snoozed songs. Hold a song to hide or snooze it."
                    else "$hiddenCount hidden or snoozed songs are kept out of mixes, shuffle-all and autoplay.",
                    style = T.small, color = c.muted,
                )
                if (hiddenCount > 0) Pill("Show them all again", vm::clearHiddenAndSnoozed, icon = Ic.Refresh)
                Text(
                    if (s.tasteExcluded.isEmpty()) "Every song you play shapes your mixes. Hold a song to leave it out."
                    else "${s.tasteExcluded.size} songs don't shape your mixes (Stats still count them).",
                    style = T.small, color = c.muted,
                )
                if (s.tasteExcluded.isNotEmpty()) Pill("Use them all again", vm::clearTasteExclusions, icon = Ic.Refresh)
            }

            vm.onlineName?.let { source ->
                StreamingCard(vm, source)
                DownloadsCard(vm)
            }

            Card(Modifier) {
                Text("Library", style = T.h2, color = c.text)
                Text(
                    "${songs.size} songs, ${playlists.size} playlists, ${favs.size} favourites",
                    style = T.title, color = c.text,
                )
                ToggleRow(
                    "Ignore \"The\" and \"A\" when sorting", s.ignoreArticles, { v -> vm.updateSettings { it.copy(ignoreArticles = v) } },
                    "The Weeknd sorts under W",
                )
                ToggleRow(
                    "Skip short files", s.skipShortFiles, { v -> vm.updateSettings { it.copy(skipShortFiles = v) } },
                    "Leave out ringtones and voice notes under 30 seconds (scan again to apply)",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (scan == com.skarif.kairos.ui.ScanState.Scanning) "Scanning…" else "Scan again", vm::scanNow, icon = Ic.Refresh)
                    Pill("Music folders", onFolders, icon = Ic.Folder)
                }
            }

            Card(Modifier) {
                Text("Backup and restore", style = T.h2, color = c.text)
                Text(
                    "Saves favourites, playlists, listening history, lyrics timing, hidden songs and settings to one file. " +
                        "Restoring on a new phone finds your songs by file name and tags, and never deletes anything.",
                    style = T.small, color = c.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Back up", { backupLauncher.launch("kairos-backup-${java.time.LocalDate.now()}.json") }, icon = Ic.DownArrow, selected = true)
                    Pill("Restore", { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, icon = Ic.Up)
                }
                Text("Clear listening history", style = T.small, color = c.muted)
                Pill("Clear history…", { confirmClear = true }, icon = Ic.Trash)
            }

            Card(Modifier) {
                // On-device frame timing: what to send when something lags.
                Text("Performance log", style = T.h2, color = c.text)
                var summary by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.skarif.kairos.perf.PerfLog.summary()) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) { summary = com.skarif.kairos.perf.PerfLog.summary(); kotlinx.coroutines.delay(1000) }
                }
                Text(summary, style = T.title, color = c.text)
                Text(
                    "Every frame that took too long since the app started, with what was happening at that moment. " +
                        "If something lags, do it a few times, then share this. It stays on your phone until you share it.",
                    style = T.small, color = c.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Share log", {
                        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
                        val text = com.skarif.kairos.perf.PerfLog.report(version, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
                        runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share performance log")) }
                    }, icon = Ic.Up, selected = true)
                    Pill("Reset", { com.skarif.kairos.perf.PerfLog.clear(); summary = com.skarif.kairos.perf.PerfLog.summary() }, icon = Ic.Refresh)
                }
            }

            if (vm.onlineName != null) OnlineAboutCard(vm) else Card(Modifier) {
                Text("About", style = T.h2, color = c.text)
                val version = androidx.compose.runtime.remember {
                    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
                }
                Text("Kairos $version", style = T.title, color = c.text)
                Text("An offline music player. No account, no ads, no tracking: everything stays on this phone, unless you turn on fetching lyrics online.", style = T.small, color = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Source code", {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://github.com/Arif1221/kairos"))) }
                    }, icon = Ic.Chevron)
                    Pill("Privacy", {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://github.com/Arif1221/kairos/blob/main/PRIVACY.md"))) }
                    }, icon = Ic.Chevron)
                }
            }

            com.skarif.kairos.ui.components.DeveloperCard()
            com.skarif.kairos.ui.components.CreditsCard(online = vm.onlineName != null)

            Pill("Equalizer", onEqualizer, icon = Ic.Sliders, big = true, modifier = Modifier.fillMaxWidth())
    }
}

private fun readableTree(uri: String): String = runCatching {
    "/" + DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':')
}.getOrDefault(uri)

@Composable
fun FoldersScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = P.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val art by vm.artwork.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    LaunchedEffect(s.skipShortFiles) { vm.loadFolders() }
    val total = folders.filter { it.key !in s.excludedFolders }.sumOf { it.count }

    PageColumn {
            Header("Music folders", onBack)
            Text(mixedHeadline("Choose " to false, "where" to true, " to look for " to false, "music" to true), style = T.h1, color = c.text)
            Card {
                if (folders.isEmpty()) Text("No audio files found on this phone yet.", style = T.body, color = c.muted)
                folders.forEach { f ->
                    ToggleRow(f.label, f.key !in s.excludedFolders, { include ->
                        vm.updateSettings { st ->
                            st.copy(excludedFolders = if (include) st.excludedFolders - f.key else st.excludedFolders + f.key)
                        }
                    }, "${f.count} files")
                }
            }
            Card {
                ToggleRow(
                    "Skip short files", s.skipShortFiles, { v -> vm.updateSettings { it.copy(skipShortFiles = v) } },
                    "Ignore audio under 30 seconds, like ringtones and voice notes",
                )
            }
            Pill(
                if (scan == ScanState.Scanning) "Scanning…" else "Scan $total files",
                vm::scanNow, accent = true, big = true, modifier = Modifier.fillMaxWidth(),
            )
            (scan as? ScanState.Done)?.let { Text("Library updated: ${it.songs} songs.", style = T.body, color = c.text) }
    }
}

/** Online build: stream quality, the offline cache, and a check of what works on this network. */
@Composable
private fun StreamingCard(vm: MainViewModel, source: String) {
    val c = P.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val checks by vm.onlineUi.checks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onlineUi.refreshCacheSize() }
    Card(Modifier) {
        Text("Streaming", style = T.h2, color = c.text)
        Text("$source songs and videos play as audio only.", style = T.small, color = c.muted)
        Segmented(com.skarif.kairos.data.StreamQuality.entries.map { it.label }, s.streamQuality.ordinal, { i ->
            vm.updateSettings { it.copy(streamQuality = com.skarif.kairos.data.StreamQuality.entries[i]) }
        })
        Text(
            "About ${s.streamQuality.kbps} kbps, roughly ${"%.1f".format(s.streamQuality.kbps * 60 / 8 / 1000.0)} MB a minute. Applies from the next song.",
            style = T.small, color = c.muted,
        )
        ToggleRow(
            "Data saver on mobile data", s.dataSaverOnMobile, { v -> vm.updateSettings { it.copy(dataSaverOnMobile = v) } },
            "Uses ${com.skarif.kairos.data.StreamQuality.LOW.label} quality when you're not on Wi-Fi",
        )
        Text("Online check", style = T.title, color = c.text)
        Text("Tries search, radio and playback once, to see what works on your network.", style = T.small, color = c.muted)
        when (val st = checks) {
            com.skarif.kairos.ui.Loadable.Loading -> Text("Checking...", style = T.small, color = c.muted)
            is com.skarif.kairos.ui.Loadable.Failed -> Text(st.message, style = T.small, color = c.muted)
            is com.skarif.kairos.ui.Loadable.Ready -> st.value.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.ok) "✓" else "✕", style = T.title, color = if (r.ok) c.text else c.accent)
                    Column {
                        Text(r.name, style = T.body, color = c.text)
                        Text(r.detail, style = T.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            null -> {}
        }
        Pill(if (checks == null) "Run check" else "Run again", { vm.onlineUi.runChecks() }, icon = Ic.Refresh)
    }
}

/** Online build: what Download does, the downloads, and how much room streaming may use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadsCard(vm: MainViewModel) {
    val c = P.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val cacheBytes by vm.onlineUi.cacheBytes.collectAsStateWithLifecycle()
    val dlBytes by vm.onlineUi.downloadBytes.collectAsStateWithLifecycle()
    val dlCount = vm.onlineUi.downloadedIds.collectAsStateWithLifecycle().value.size
    val onPhone = vm.onlineUi.savedToPhoneIds.collectAsStateWithLifecycle().value.size
    var confirmRemove by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.onlineUi.refreshCacheSize() }
    Card(Modifier) {
        Text("Downloads & storage", style = T.h2, color = c.text)

        Text("Download button saves", style = T.title, color = c.text)
        Segmented(listOf("In the app", "To the phone"), if (s.downloadToPhone) 1 else 0, { i ->
            vm.updateSettings { it.copy(downloadToPhone = i == 1) }
        })
        Text(
            if (s.downloadToPhone) "Saves an M4A file with title, artist and cover in Music/${com.skarif.kairos.data.PhoneSaver.FOLDER}. " +
                "Other apps can play it, and it shows in your Library. Delete it from a file manager."
            else "Kept inside Kairos at your streaming quality; only Kairos plays it. Hold a song for Save to phone when you want a file.",
            style = T.small, color = c.muted,
        )

        Text("In the app", style = T.title, color = c.text)
        Text(
            if (dlCount == 0) "Nothing downloaded yet."
            else "$dlCount songs, ${dlBytes / 1_048_576} MB. Kept until you remove them.",
            style = T.small, color = c.muted,
        )
        if (dlCount > 0) Pill("Remove all downloads", { confirmRemove = true }, icon = Ic.Trash)
        if (onPhone > 0) Text("Saved to the phone: $onPhone songs in Music/${com.skarif.kairos.data.PhoneSaver.FOLDER}.", style = T.small, color = c.muted)

        Text("Recently streamed", style = T.title, color = c.text)
        val sizes = listOf(256, 512, 1024, 2048, 4096)
        // Five sizes don't fit one segmented row on narrow phones; pills wrap.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sizes.forEach { mb ->
                Pill(if (mb < 1024) "$mb MB" else "${mb / 1024} GB", { vm.updateSettings { it.copy(streamCacheMb = mb) } }, selected = s.streamCacheMb == mb)
            }
        }
        Text(
            "Songs you stream are kept so they replay without data; the oldest go first. Using ${cacheBytes / 1_048_576} MB now.",
            style = T.small, color = c.muted,
        )
        Pill("Clear recently streamed", { vm.onlineUi.clearCache() }, icon = Ic.Trash)
    }
    if (confirmRemove) ConfirmDialog(
        "Remove all downloads?",
        "$dlCount songs will need a connection again. Favourites and playlists keep them. Files saved to the phone stay.",
        confirm = "Remove", dismiss = "Keep",
        onConfirm = { vm.onlineUi.removeAllDownloads(); confirmRemove = false }, onDismiss = { confirmRemove = false },
    )
}

/** Online build: version, update check (it ships only on GitHub, so nothing else tells users), source and privacy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnlineAboutCard(vm: MainViewModel) {
    val c = P.colors
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val update by vm.onlineUi.update.collectAsStateWithLifecycle()
    val repo = "https://github.com/${com.skarif.kairos.ui.REPO}"
    Card(Modifier) {
        Text("About Kairos Online", style = T.h2, color = c.text)
        Text(
            "Version ${vm.onlineUi.appVersion}. Free and open source (GPL-3.0). No account, no ads, no tracking. Not affiliated with YouTube or Google.",
            style = T.small, color = c.muted,
        )
        when (val u = update) {
            is com.skarif.kairos.ui.UpdateState.Available -> {
                Text("Version ${u.version} is available.", style = T.body, color = c.text)
                Pill("Update to ${u.version}", { vm.onlineUi.showUpdate() }, selected = true, icon = Ic.DownArrow)
            }
            com.skarif.kairos.ui.UpdateState.Checking -> Text("Checking for updates...", style = T.small, color = c.muted)
            com.skarif.kairos.ui.UpdateState.UpToDate -> Text("You have the latest version.", style = T.small, color = c.muted)
            com.skarif.kairos.ui.UpdateState.Failed -> Text("Couldn't check for updates.", style = T.small, color = c.muted)
            com.skarif.kairos.ui.UpdateState.Idle -> {}
        }
        if (update !is com.skarif.kairos.ui.UpdateState.Available) {
            Pill("Check for updates", { vm.onlineUi.checkForUpdate() }, icon = Ic.Refresh)
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Pill("Show tips again", {
            com.skarif.kairos.ui.components.Hints.resetAll(ctx)
            vm.notify("Tips will show on each screen again")
        }, icon = Ic.Info)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Source code", { uri.openUri(repo) }, icon = Ic.Chevron)
            Pill("Privacy", { uri.openUri("$repo/blob/main/PRIVACY.md") }, icon = Ic.Chevron)
            Pill("Report a bug", { uri.openUri("$repo/issues") }, icon = Ic.Chevron)
        }
    }
}

