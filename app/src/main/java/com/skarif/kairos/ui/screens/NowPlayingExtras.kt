package com.skarif.kairos.ui.screens

import com.skarif.kairos.ui.components.Ic
import com.skarif.kairos.ui.components.SheetAction

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.db.contentUri
import com.skarif.kairos.data.db.formatLabel
import com.skarif.kairos.data.normalize
import com.skarif.kairos.data.splitArtists
import com.skarif.kairos.ui.LyricsState
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.Pill
import com.skarif.kairos.ui.components.formatTime
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T

/*
 * Everything on the player beyond the controls, matching what Spotify's Now Playing has:
 * the ⋮ menu (radio, share, speed, credits, hide...), elapsed/remaining time, and the cards
 * under the controls: up next, lyrics preview, about the artist and song credits.
 */

/**
 * Shares the song: the audio file itself, with a "Listening to ..." caption. Apps that take files
 * (WhatsApp, Telegram, Drive, Nearby Share) get the music; apps that only take text get the caption.
 */
fun shareSong(context: android.content.Context, song: SongEntity) {
    val caption = "Listening to ${song.title} by ${song.artist}" + if (song.album.isNotBlank()) " (${song.album})" else ""
    if (song.volume == com.skarif.kairos.data.db.ONLINE_VOLUME) {
        // Online songs have no file to send: share a link instead.
        val link = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$caption\nhttps://music.youtube.com/watch?v=${song.fileName}")
            putExtra(Intent.EXTRA_SUBJECT, song.title)
        }
        context.startActivity(Intent.createChooser(link, "Share ${song.title}"))
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = song.mimeType.ifEmpty { "audio/*" }
        putExtra(Intent.EXTRA_STREAM, song.contentUri())
        putExtra(Intent.EXTRA_TEXT, caption)
        putExtra(Intent.EXTRA_SUBJECT, song.title)
        // Lets the chooser preview the file and pass read access to the app you pick.
        clipData = android.content.ClipData.newUri(context.contentResolver, song.title, song.contentUri())
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ${song.title}"))
}

/** The player's ⋮ menu: the usual song actions plus the player-only ones. */
@Composable
fun PlayerMenu(
    vm: MainViewModel, song: SongEntity, onOpenAlbum: (Long) -> Unit, onOpenArtist: (String) -> Unit,
    onSleep: () -> Unit, onEqualizer: () -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var speed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(false) }
    val onlineNav = LocalOnlineNav.current
    // Always reachable for online songs, from the full player or the mini player's long-press menu.
    val findVideo = if (vm.onlineName != null && song.volume == com.skarif.kairos.data.db.ONLINE_VOLUME) {
        listOf(SheetAction("Find music video", Ic.Video) {
            onlineNav.search("${song.title} ${song.artist}".trim(), com.skarif.kairos.online.OnlineKind.VIDEOS)
        })
    } else emptyList()
    // Audio and video quality for online songs, here as well as in Settings > Streaming.
    val qualityAction = if (vm.onlineName != null && song.volume == com.skarif.kairos.data.db.ONLINE_VOLUME) {
        listOf(SheetAction("Quality", Ic.Wave, keepOpen = true) { quality = true })
    } else emptyList()
    when {
        speed -> SpeedDialog(vm) { speed = false; onDismiss() }
        quality -> StreamQualityDialog(vm) { quality = false; onDismiss() }
        info -> SongInfoDialog(vm, song) { info = false; onDismiss() }
        else -> SongActionsDialog(
            vm, song, onOpenAlbum, onOpenArtist = onOpenArtist,
            extra = listOf(
                SheetAction("Start song radio", Ic.Radio) { vm.startRadio(song) },
            ) + findVideo + qualityAction + listOf(
                // These two swap this menu for their own dialog, so the sheet must not close itself.
                SheetAction("Playback speed", Ic.Speed, keepOpen = true) { speed = true },
                SheetAction("Sleep timer", Ic.Clock) { onSleep() },
                SheetAction("Equalizer", Ic.Sliders) { onEqualizer() },
                SheetAction("Song info and credits", Ic.Info, keepOpen = true) { info = true },
            ),
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun SpeedDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = P.colors
    val now by vm.now.collectAsStateWithLifecycle()
    com.skarif.kairos.ui.components.KairosDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text("Playback speed", style = T.h2, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(listOf(0.5f, 0.75f, 0.9f, 1f), listOf(1.1f, 1.25f, 1.5f, 2f)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { v ->
                            Pill(speedLabel(v), { vm.setSpeed(v) }, selected = kotlin.math.abs(now.speed - v) < 0.01f, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text("Pitch changes with speed, like a record. 1× is normal.", style = T.small, color = c.muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = c.text) } },
    )
}

/** Audio and video quality for online songs, without leaving the player. Same settings as Settings > Streaming. */
@Composable
fun StreamQualityDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = P.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    com.skarif.kairos.ui.components.KairosDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text("Quality", style = T.h2, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Audio", style = T.title, color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.skarif.kairos.data.StreamQuality.entries.forEach { q ->
                        Pill(q.label, { vm.updateSettings { it.copy(streamQuality = q) } }, selected = s.streamQuality == q, modifier = Modifier.weight(1f))
                    }
                }
                Text("Video", style = T.title, color = c.text)
                val vq = com.skarif.kairos.data.VideoQuality.entries
                vq.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { q ->
                            Pill(q.label, { vm.updateSettings { it.copy(videoQuality = q) } }, selected = s.videoQuality == q, modifier = Modifier.weight(1f))
                        }
                        // Keep the last row's pills the same width as the others.
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(
                    "Applies from the next song or video. Data saver on mobile data still caps video at 360p. Higher video quality uses much more data.",
                    style = T.small, color = c.muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = c.text) } },
    )
}

fun speedLabel(v: Float): String = (if (v == v.toInt().toFloat()) "${v.toInt()}" else "$v".trimEnd('0')) + "×"

/** Spotify's "Show credits", from the file's tags and what Android knows about it. */
@Composable
fun SongInfoDialog(vm: MainViewModel, song: SongEntity, onDismiss: () -> Unit) {
    val c = P.colors
    com.skarif.kairos.ui.components.KairosDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text(song.title, style = T.h2, color = c.text, maxLines = 2) },
        text = { SongInfoRows(vm, song) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.text) } },
    )
}

@Composable
private fun SongInfoRows(vm: MainViewModel, song: SongEntity) {
    val c = P.colors
    val now by vm.now.collectAsStateWithLifecycle()
    val counts by vm.playCounts.collectAsStateWithLifecycle()
    val album = vm.albumGroup(song.albumId)
    val rows = listOfNotNull(
        "Artist" to song.artist,
        album?.artist?.takeIf { it != song.artist }?.let { "Album artist" to it },
        "Album" to song.album,
        song.track.takeIf { it > 0 }?.let { t -> "Track" to (if (t >= 1000) "Disc ${t / 1000}, track ${t % 1000}" else "$t") },
        song.year.takeIf { it > 0 }?.let { "Year" to "$it" },
        song.genre.takeIf { it.isNotBlank() }?.let { "Genre" to it },
        "Length" to formatTime(song.durationMs),
        "Format" to song.formatLabel() + if (song.bitrate > 0) ", ${song.bitrate / 1000} kbps" else "",
        now.output?.takeIf { now.songId == song.id }?.let { "Output now" to it },
        song.sizeBytes.takeIf { it > 0 }?.let { "Size" to String.format("%.1f MB", it / 1_000_000.0) },
        "Plays" to "${counts[song.id] ?: 0}",
        "Added" to java.time.Instant.ofEpochSecond(song.dateAddedSec).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
        "File" to song.folder + song.fileName,
    )
    // A small memory: when this song first came into your life (from your listening history).
    val first by androidx.compose.runtime.produceState<Long?>(null, song.id) { value = vm.firstPlayedAt(song.id) }
    Column {
        first?.let { Text(firstPlayedLine(it), style = T.body.copy(fontWeight = FontWeight.SemiBold), color = c.accent, modifier = Modifier.padding(bottom = 8.dp)) }
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.line)
            Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(k, Modifier.width(96.dp), style = T.small, color = c.muted)
                Text(v, Modifier.weight(1f), style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "You first played this on a Tuesday evening, 14 November 2026." */
private fun firstPlayedLine(atMs: Long): String {
    val t = java.time.Instant.ofEpochMilli(atMs).atZone(java.time.ZoneId.systemDefault())
    val part = when (t.hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
    val day = t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    val date = t.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.getDefault()))
    return "You first played this on a $day $part, $date."
}

/** Elapsed on the left, time left on the right, under the seek bar. */
@Composable
fun TimeRow(vm: MainViewModel, durationMs: Long) {
    val c = P.colors
    val pos by vm.position.collectAsStateWithLifecycle()
    val p = pos.coerceIn(0, durationMs.coerceAtLeast(0))
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(formatTime(p), Modifier.weight(1f), style = T.num, color = c.muted)
        Text("-" + formatTime((durationMs - p).coerceAtLeast(0)), style = T.num, color = c.muted)
    }
}

@Composable
private fun InfoCard(
    title: String, icon: ImageVector, action: String? = null, onClick: (() -> Unit)? = null,
    tinted: Boolean = false, content: @Composable () -> Unit,
) {
    val c = P.colors
    val shape = RoundedCornerShape(com.skarif.kairos.ui.theme.Radius.card)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (tinted) androidx.compose.ui.graphics.lerp(c.card, c.accent, if (c.dark) 0.12f else 0.09f) else c.card)
            .border(1.dp, c.line.copy(alpha = if (c.dark) 0.30f else 0.20f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(c.tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.accent, modifier = Modifier.size(17.dp))
            }
            Text(title, Modifier.weight(1f), style = T.title.copy(fontSize = 18.sp), color = c.text)
            if (action != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (tinted) c.card.copy(alpha = 0.7f) else c.chip).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(action, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Icon(Ic.Chevron, null, tint = c.muted, modifier = Modifier.size(15.dp))
                }
            }
        }
        content()
    }
}

/** The cards under the player controls. */
@Composable
fun NowPlayingCards(
    vm: MainViewModel, song: SongEntity, onQueue: () -> Unit, onLyrics: () -> Unit,
    onOpenCollection: (String) -> Unit,
) {
    val c = P.colors
    val now by vm.now.collectAsStateWithLifecycle()
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val offset by vm.lyricsOffset.collectAsStateWithLifecycle()
    val artists by vm.artistByKey.collectAsStateWithLifecycle()
    val counts by vm.playCounts.collectAsStateWithLifecycle()
    val autoplay = vm.settings.collectAsStateWithLifecycle().value.autoplay

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Lyrics preview: the current line and the two after it, live.
        val l = (lyrics as? LyricsState.Ready)?.lyrics
        if (l != null && !l.isEmpty()) {
            // Read through derivedStateOf: this block recomposes when the current line changes,
            // not on every position tick (the whole Column used to recompose about every 100 ms).
            val pos = vm.position.collectAsStateWithLifecycle()
            val at by remember(l, offset) { androidx.compose.runtime.derivedStateOf { if (l.synced) l.indexAt(pos.value - offset).coerceAtLeast(0) else 0 } }
            InfoCard("Lyrics", Ic.Lyrics, "Show all", onLyrics, tinted = true) {
                // The block rolls up as the song moves to the next line (and down if you seek back),
                // and the lines fade with distance from the one being sung.
                AnimatedContent(
                    targetState = at,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        val dir = if (forward) 1 else -1
                        (slideInVertically(tween(420, easing = FastOutSlowInEasing)) { dir * (it / 3) } + fadeIn(tween(320, delayMillis = 60)))
                            .togetherWith(slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { -dir * (it / 3) } + fadeOut(tween(200)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "lyricsPreview",
                ) { idx ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        l.lines.drop(idx).take(if (l.synced) 3 else 4).forEachIndexed { i, line ->
                            Text(
                                line.text.ifBlank { "♪" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = T.title.copy(
                                    fontSize = if (i == 0 && l.synced) 23.sp else 17.sp,
                                    lineHeight = if (i == 0 && l.synced) 28.sp else 22.sp,
                                    fontWeight = if (i == 0 && l.synced) FontWeight.ExtraBold else FontWeight.SemiBold,
                                ),
                                color = when {
                                    !l.synced -> c.text
                                    i == 0 -> c.text
                                    i == 1 -> c.text.copy(alpha = 0.55f)
                                    else -> c.text.copy(alpha = 0.32f)
                                },
                            )
                        }
                    }
                }
            }
        }

        // Up next: the next three songs in the queue.
        val next = now.queue.drop(now.queuePosition + 1).take(3).mapNotNull { songMap[it.songId] }
        InfoCard("Up next", Ic.Queue, "Open queue", onQueue) {
            if (next.isEmpty()) {
                Text(
                    if (autoplay) "End of the queue. Autoplay will add similar songs." else "End of the queue.",
                    style = T.small, color = c.muted,
                )
            }
            next.forEachIndexed { n, s ->
                if (n > 0) HorizontalDivider(Modifier.padding(start = 56.dp, top = 0.dp), thickness = 0.5.dp, color = c.line)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SongArt(vm, s, 44.dp, corner = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = T.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatTime(s.durationMs), style = T.num, color = c.muted)
                }
            }
        }

        // About the artist, with your own numbers, and more of their songs.
        val main = splitArtists(song.artist).first()
        val artist = remember(artists, main) { artists[normalize(main)] }
        if (artist != null) {
            val plays = artist.songs.sumOf { counts[it.id] ?: 0 }
            val albums = artist.songs.distinctBy { vm.albumGroup(it.albumId)?.id ?: it.albumId }.size
            InfoCard("About the artist", Ic.Person, "Open", { onOpenCollection("artist:${artist.name}") }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SongArt(vm, artist.songs.maxByOrNull { counts[it.id] ?: 0 } ?: song, 64.dp, corner = 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(artist.name, style = T.h2, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${artist.songs.size} songs, $albums albums on this phone. You've played them $plays times.",
                            style = T.small, color = c.muted,
                        )
                    }
                }
                val more = artist.songs.filter { it.id != song.id }.sortedByDescending { counts[it.id] ?: 0 }.take(12)
                if (more.isNotEmpty()) {
                    Text("More by ${artist.name}", style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(0.dp)) {
                        items(more, key = { "mb" + it.id }, contentType = { "song" }) { s ->
                            Column(
                                Modifier.width(110.dp).clip(RoundedCornerShape(16.dp))
                                    .clickable(role = Role.Button, onClickLabel = "Play ${s.title}") { vm.play(more, more.indexOf(s), artist.name) },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SongArt(vm, s, 110.dp, corner = 18.dp)
                                Text(s.title, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Credits.
        InfoCard("Song info", Ic.Info) { SongInfoRows(vm, song) }
        Box(Modifier.padding(bottom = 24.dp))
    }
}
