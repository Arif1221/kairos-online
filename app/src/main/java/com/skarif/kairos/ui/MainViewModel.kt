package com.skarif.kairos.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import com.skarif.kairos.data.splitArtists
import com.skarif.kairos.data.AlbumGroup
import com.skarif.kairos.data.Taste
import com.skarif.kairos.data.genreKey
import com.skarif.kairos.data.groupAlbums
import com.skarif.kairos.data.groupGenres
import com.skarif.kairos.data.normalize
import com.skarif.kairos.data.searchSongs
import com.skarif.kairos.data.matchesQuery
import com.skarif.kairos.data.sortKey
import com.skarif.kairos.data.sortTitle
import kotlinx.coroutines.flow.first
import com.skarif.kairos.data.splitGenres
import com.skarif.kairos.data.spreadShuffle
import com.skarif.kairos.data.weightedSample
import com.skarif.kairos.data.Recap
import com.skarif.kairos.data.dayPart
import com.skarif.kairos.data.parseM3u
import com.skarif.kairos.data.similarSongs
import com.skarif.kairos.data.smartShuffle
import com.skarif.kairos.data.toM3u
import com.skarif.kairos.data.yearRecap
import android.app.Application
import com.skarif.kairos.data.CoverColour
import com.skarif.kairos.data.coverHue
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skarif.kairos.PlayerApp
import com.skarif.kairos.data.AlbumColors
import com.skarif.kairos.data.FolderInfo
import com.skarif.kairos.data.Settings
import com.skarif.kairos.data.SongSort
import com.skarif.kairos.data.db.FavoriteEntity
import com.skarif.kairos.data.db.PlayEventEntity
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.db.isOnline
import com.skarif.kairos.online.Flavor
import com.skarif.kairos.online.VideoState
import com.skarif.kairos.lyrics.Lyrics
import com.skarif.kairos.playback.NowPlaying
import com.skarif.kairos.playback.SleepState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
}

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Done(val songs: Int) : ScanState
}

/** How "Sort by" reorders a playlist. */
enum class PlaylistSort(val label: String) {
    TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), NEWEST("Newest first"), OLDEST("Oldest first"), REVERSE("Reverse order"), SHUFFLE("Shuffle order"),
}

/** A list of songs shown with the album-style screen: albums, playlists and mixes. */
data class Collection(val key: String, val title: String, val subtitle: String, val songs: List<SongEntity>, val playlistId: Long? = null)

/** One artist, after splitting "A feat. B" tags, with every song they appear on. */
data class ArtistUi(val name: String, val songs: List<SongEntity>, val albums: Int = songs.distinctBy { it.albumId }.size)

data class GenreUi(val name: String, val songs: List<SongEntity>)

data class LibraryHits(val songs: Set<Long>, val albums: Set<Long>, val artists: Set<String>, val genres: Set<String>)

/** A short message at the bottom of the screen, with Undo when the action can be undone. */
data class Notice(
    val text: String, val undo: (() -> Unit)? = null, val isError: Boolean = false, val id: Long = System.nanoTime(),
    /** How long it stays; null = the default (4 s, 6 s with Undo). */
    val durationMs: Long? = null,
)

data class SearchHits(
    val artists: List<ArtistUi> = emptyList(),
    val albums: List<AlbumGroup> = emptyList(),
    val genres: List<GenreUi> = emptyList(),
)

data class PlaylistUi(val id: Long, val name: String, val songs: List<SongEntity>)

enum class StatsRange(val label: String) { WEEK("W"), MONTH("M"), YEAR("Y"), ALL("All") }
data class StatBar(val label: String, val listenedMs: Long)
data class TopSong(val song: SongEntity, val plays: Int, val listenedMs: Long)
data class StatsUi(
    val totalMs: Long = 0,
    val plays: Int = 0,
    val change: String = "",
    val bars: List<StatBar> = emptyList(),
    val top: List<TopSong> = emptyList(),
    val streakDays: Int = 0,
    val uniqueSongs: Int = 0,
    val avgPerDayMs: Long = 0,
    val completedPct: Int = 0,
    /** e.g. "9 PM", the hour you most often start listening. */
    val peakHour: String = "",
    val topArtists: List<Pair<String, Long>> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ct = (app as PlayerApp).container
    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
    /** For indexes that are read with `.value` (queue rows, album lookups): always kept up to date. */
    private fun <T> Flow<T>.always(initial: T) = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val settings: StateFlow<Settings> = ct.settings.settings
    val now: StateFlow<NowPlaying> = ct.player.state
    val position: StateFlow<Long> = ct.player.position
    val sleep: StateFlow<SleepState> = ct.sleep.state
    /** Every song row: the library plus online songs that were played, saved or added (online flavor). */
    private val allSongs: StateFlow<List<SongEntity>> = ct.db.songs().observeAll().always(emptyList())
    /** Your library: local files only. Albums, artists, mixes and search are built from this. */
    /** Ids of online songs downloaded in the app (fed from [onlineUi] once it exists). */
    private val downloadedOnline = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Your library: files on this phone, plus (online build) songs downloaded in the app, so
     * Songs, Artists and Search show both. Streamed-only songs stay out.
     */
    val songs: StateFlow<List<SongEntity>> = combine(allSongs, downloadedOnline) { l, dl ->
        if (Flavor.online == null) l else l.filter { !it.isOnline || it.id in dl }
    }.flowOn(Dispatchers.Default).always(emptyList())
    private val events: StateFlow<List<PlayEventEntity>> = ct.db.playEvents().observeAll().state(emptyList())
    val favorites: StateFlow<Set<Long>> = ct.db.favorites().observeIds().map { it.toSet() }.always(emptySet())

    /**
     * Song lookup by id. Shared eagerly: the queue reads it for every row, and while it was only
     * started by other screens it could still be empty when the queue opened, so every row showed
     * "Unknown" with no cover. Screens should collect [songMap] so they redraw when it fills.
     */
    private val songById: StateFlow<Map<Long, SongEntity>> = allSongs.map { l -> l.associateBy { it.id } }
        .flowOn(Dispatchers.Default).always(emptyMap())
    val songMap: StateFlow<Map<Long, SongEntity>> get() = songById
    // Everything computed from the whole listening history runs off the main thread: it
    // updates on every song change and every 15 s while playing.
    val playCounts: StateFlow<Map<Long, Int>> = events.map { e -> e.groupingBy { it.songId }.eachCount() }
        .flowOn(Dispatchers.Default).state(emptyMap())

    /** Albums merged across MediaStore ids; see [groupAlbums]. */
    // Downloaded online songs have no real album (id 0): they'd all land in one fake album.
    val albums: StateFlow<List<AlbumGroup>> = songs.map { l -> groupAlbums(l.filterNot { it.isOnline }) }.flowOn(Dispatchers.Default).always(emptyList())
    private val albumIndex: StateFlow<Map<Long, AlbumGroup>> = albums.map { l ->
        HashMap<Long, AlbumGroup>().apply { l.forEach { g -> g.albumIds.forEach { put(it, g) } } }
    }.always(emptyMap())
    fun albumGroup(albumId: Long): AlbumGroup? = albumIndex.value[albumId]

    private val sortKey = settings.map { it.songSort to it.sortDescending }.distinctUntilChanged()
    /** A-Z keys for every song, worked out once per library change (not on every re-sort). */
    val sortKeys: StateFlow<Map<String, String>> = combine(songs, settings.map { it.ignoreArticles }.distinctUntilChanged()) { list, articles ->
        HashMap<String, String>().apply {
            list.forEach { s -> for (t in arrayOf(s.title, s.artist, s.album)) getOrPut(t) { com.skarif.kairos.data.librarySortKey(t, articles) } }
        }
    }.flowOn(Dispatchers.Default).state(emptyMap()) // built when the Library is on screen, not at app start

    /**
     * The Library's order. Off the main thread, and it only listens to play counts when sorted by
     * most played: before, every saved listen re-sorted all songs on the main thread, right as
     * the player was opening.
     */
    val sortedSongs: StateFlow<List<SongEntity>> = sortKey.flatMapLatest { (sort, _) ->
        val counts = if (sort == SongSort.MOST_PLAYED) playCounts else flowOf(emptyMap())
        combine(songs, sortKey, counts, sortKeys) { list, k, c, keys -> Triple(list, k, c) to keys }
    }.map { (t, keys) ->
        val (list, sk, counts) = t
        val (sort, desc) = sk
        // Same rule as the letter headers (see librarySortKey), so the two never disagree.
        val key: (String) -> String = { s -> keys[s] ?: com.skarif.kairos.data.librarySortKey(s) }
        val sorted = when (sort) {
            SongSort.TITLE -> list.sortedBy { key(it.title) }
            SongSort.ARTIST -> list.sortedBy { key(it.artist) }
            SongSort.ALBUM -> list.sortedWith(compareBy({ key(it.album) }, { it.track }))
            SongSort.DATE_ADDED -> list.sortedBy { it.dateAddedSec }
            SongSort.LENGTH -> list.sortedBy { it.durationMs }
            SongSort.MOST_PLAYED -> list.sortedBy { counts[it.id] ?: 0 }
        }
        val naturallyDesc = sort == SongSort.DATE_ADDED || sort == SongSort.MOST_PLAYED
        if (desc != naturallyDesc) sorted.asReversed() else sorted
    }.flowOn(Dispatchers.Default).distinctUntilChanged().state(emptyList())

    /** Just "is music playing", so screens that only need that don't recompose on every queue change. */
    val isPlaying: StateFlow<Boolean> = now.map { it.isPlaying }.distinctUntilChanged().state(false)

    /**
     * The playing song, straight from the in-memory library (falling back to the database for a
     * song not indexed yet). The database lookup alone made the player show the new song
     * about 200 ms after the tap.
     */
    // A null songId while the queue still has items is a stream swap in progress, not "nothing playing".
    private val currentSongRaw: Flow<SongEntity?> = now.filter { it.songId != null || it.queueSize == 0 }.map { it.songId }.distinctUntilChanged()
        .flatMapLatest { id ->
            when {
                id == null -> flowOf(null)
                else -> songById.map { it[id] }.distinctUntilChanged().flatMapLatest { s -> if (s != null) flowOf(s) else ct.db.songs().observe(id) }
            }
        }

    /**
     * [currentSongRaw], but a "no song" that lasts under a few seconds is ignored. Switching a song
     * between its sound and its video swaps the playing item, and for the seconds that takes the
     * player reports no current song; the whole player (and the mini player) used to disappear then.
     */
    val currentSong: StateFlow<SongEntity?> = currentSongRaw
        .transformLatest { s ->
            if (s != null) emit(s) else {
                kotlinx.coroutines.delay(4_000)
                android.util.Log.w("KairosNow", "currentSong -> null after grace: queue=${now.value.queueSize} index=${now.value.queueIndex} playing=${now.value.isPlaying} video=${now.value.videoActive}")
                emit(null)
            }
        }
        .state(null)

    /**
     * The song and its cover, loaded together. Colours must be computed from this pair:
     * combining the current song with "the latest cover" separately gave the new song the
     * previous song's cover for a moment, and that wrong colour was then cached.
     */
    private val songArt: StateFlow<Pair<SongEntity, Bitmap?>?> = currentSong
        .mapLatest { s -> s?.let { it to ct.artwork.load(it) } }
        .state(null)

    val artwork: StateFlow<Bitmap?> = songArt.map { it?.second }.state(null)

    /**
     * The last song's colours are remembered, so the app opens already in them. Starting in the
     * default colours and switching a moment later redrew the whole app (362 ms, measured).
     */
    private val colorCache = app.getSharedPreferences("theme_cache", android.content.Context.MODE_PRIVATE)
    private val cachedColors: AlbumColors? = colorCache.takeIf { it.contains("accent") }
        ?.let { AlbumColors(it.getInt("accent", 0), it.getInt("onAccent", 0)) }

    val albumColors: StateFlow<AlbumColors?> = songArt.transformLatest { pair ->
        // No song loaded yet (app starting): keep the remembered colours instead of resetting.
        val (song, bmp) = pair ?: return@transformLatest
        val colors = bmp?.let { ct.artwork.colorsFor(song, it) }
        emit(colors)
        if (colors != null) colorCache.edit().putInt("accent", colors.accent).putInt("onAccent", colors.onAccent).apply()
    }.state(cachedColors)

    private val lyricsInputs = settings.map {
        listOf(it.readEmbeddedLyrics, it.readLrcFiles, it.lyricsPreference, it.lrcFolderUris, it.fetchLyricsOnline, it.onlineLyricsType)
    }.distinctUntilChanged()

    /** Bumped to load the current song's lyrics again (after clearing downloaded lyrics). */
    private val lyricsReload = MutableStateFlow(0)

    val lyrics: StateFlow<LyricsState> = combine(currentSong, lyricsInputs, lyricsReload) { s, _, _ -> s }
        .transformLatest { s ->
            if (s == null) {
                emit(LyricsState.None)
            } else {
                emit(LyricsState.Loading)
                val l = ct.lyrics.load(s, settings.value)
                emit(if (l == null) LyricsState.None else LyricsState.Ready(l))
            }
        }.state(LyricsState.Loading)

    /**
     * The "Kairos moment": when the chorus first hits. A chorus repeats as a block, so look for
     * two consecutive lines that come back together later in the song; the block that repeats
     * most wins (ties: the earliest), and the moment is its first line. A lone repeated ad-lib
     * ("yeah, yeah") doesn't count. Null without synced lyrics or a clear chorus.
     */
    val chorusMs: StateFlow<Long?> = lyrics.map { st ->
        val l = (st as? LyricsState.Ready)?.lyrics?.takeIf { it.synced } ?: return@map null
        val lines = l.lines.filter { it.text.isNotBlank() }
        val key = lines.map { it.text.lowercase().filter(Char::isLetterOrDigit) }
        var best = -1; var bestCount = 0
        for (i in 0 until lines.size - 1) {
            if (lines[i].timeMs < 15_000 || key[i].length < 6) continue
            var count = 0
            for (j in i + 2 until lines.size - 1) if (key[j] == key[i] && key[j + 1] == key[i + 1]) count++
            if (count > bestCount) { best = i; bestCount = count }
        }
        if (bestCount >= 1) lines[best].timeMs else null
    }.flowOn(Dispatchers.Default).distinctUntilChanged().state(null)

    val lyricsOffset: StateFlow<Long> = currentSong
        .flatMapLatest { s -> if (s == null) flowOf(0L) else ct.lyrics.observeOffset(s.id) }
        .state(0L)

    val playlists: StateFlow<List<PlaylistUi>> = combine(
        ct.db.playlists().observeAll(), ct.db.playlists().observeAllItems(), songById,
    ) { lists, items, byId ->
        val grouped = items.groupBy { it.playlistId }
        lists.map { p -> PlaylistUi(p.id, p.name, grouped[p.id].orEmpty().sortedBy { it.position }.mapNotNull { byId[it.songId] }) }
    }.flowOn(Dispatchers.Default).state(emptyList())

    /** Artists from split tags, most songs first. Computed off the main thread. */
    val artists: StateFlow<List<ArtistUi>> = songs.map { all ->
        val acc = LinkedHashMap<String, Pair<String, MutableList<SongEntity>>>()
        for (song in all) for (a in splitArtists(song.artist)) {
            acc.getOrPut(a.lowercase()) { a to ArrayList() }.second += song
        }
        acc.values.map { (name, l) -> ArtistUi(name, l.sortedBy { it.title.lowercase() }) }.sortedBy { sortKey(it.name) }
    }.flowOn(Dispatchers.Default).always(emptyList())

    /** "Pop; Rock" counts for both, and "Hip-Hop" / "hip hop" are one genre. */
    /** Artist by normalized name, so the player can find "about the artist" without scanning. */
    val artistByKey: StateFlow<Map<String, ArtistUi>> = artists.map { l -> l.associateBy { normalize(it.name) } }
        .flowOn(Dispatchers.Default).state(emptyMap())

    val genres: StateFlow<List<GenreUi>> = songs.map { all -> groupGenres(all).map { GenreUi(it.name, it.songs) } }
        .flowOn(Dispatchers.Default).always(emptyList())

    /** Hidden songs plus songs snoozed until a time still ahead. Re-checked every few minutes. */
    private val clock: Flow<Long> = kotlinx.coroutines.flow.flow {
        while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(5 * 60_000L) }
    }
    val blocked: StateFlow<Set<Long>> = combine(settings, clock) { s, t ->
        s.hiddenSongs.mapNotNullTo(HashSet()) { it.toLongOrNull() } +
            s.snoozedSongs.mapNotNull { e -> e.split(':').takeIf { it.size == 2 && (it[1].toLongOrNull() ?: 0L) > t }?.get(0)?.toLongOrNull() }
    }.distinctUntilChanged().always(emptySet())
    val hiddenIds: StateFlow<Set<Long>> = settings.map { s -> s.hiddenSongs.mapNotNullTo(HashSet()) { it.toLongOrNull() } }.always(emptySet())
    val snoozedIds: StateFlow<Set<Long>> = combine(blocked, hiddenIds) { b, h -> b - h }.always(emptySet())
    val tasteExcluded: StateFlow<Set<Long>> = settings.map { s -> s.tasteExcluded.mapNotNullTo(HashSet()) { it.toLongOrNull() } }
        .distinctUntilChanged().always(emptySet())
    /** The hour, so the daylist changes through the day. */
    private val hour: Flow<Int> = clock.map { java.time.LocalTime.now().hour }.distinctUntilChanged()

    /** Real listens (30 s, or half a short song) and skips, minus songs kept out of your taste. */
    private class History(val plays: List<PlayEventEntity>, val skips: List<PlayEventEntity>, val taste: Taste)
    private data class MixIn(
        val songs: List<SongEntity>, val events: List<PlayEventEntity>, val history: History, val byId: Map<Long, SongEntity>, val genres: List<GenreUi>,
    )

    private fun history(ev: List<PlayEventEntity>, byId: Map<Long, SongEntity>, favs: Set<Long>, excluded: Set<Long>): History {
        val (plays, skips) = ev.filter { it.songId !in excluded }.partition { e ->
            val d = byId[e.songId]?.durationMs ?: 0L
            e.listenedMs >= minOf(30_000L, if (d > 0) d / 2 else 30_000L)
        }
        val artistMs = HashMap<String, Long>()
        plays.forEach { e -> byId[e.songId]?.let { s -> splitArtists(s.artist).forEach { a -> artistMs.merge(normalize(a), e.listenedMs, Long::plus) } } }
        val lastAt = HashMap<Long, Long>()
        plays.forEach { e -> lastAt.merge(e.songId, e.startedAt, ::maxOf) }
        return History(plays, skips, Taste(
            plays.groupingBy { it.songId }.eachCount(), skips.groupingBy { it.songId }.eachCount(), favs, artistMs,
            together = com.skarif.kairos.data.playedTogether(plays.map { it.songId to it.startedAt }),
            lastPlayedAt = lastAt,
        ))
    }

    private val historyFlow: StateFlow<History> = combine(events, songById, favorites, tasteExcluded) { ev, byId, favs, ex -> history(ev, byId, favs, ex) }
        .flowOn(Dispatchers.Default).always(History(emptyList(), emptyList(), Taste(emptyMap(), emptyMap(), emptySet(), emptyMap())))

    /**
     * Auto playlists. The first four show on Home; all of them are under Playlists, Smart.
     *
     * Kept for the life of the app (not only while Home is showing), so going back to Home shows
     * them at once instead of building them again. Rebuilt only when something they depend on
     * really changes: a song added or removed, a new play or skip, a favourite. The play log
     * updates every 15 s while a song plays; that alone doesn't rebuild anything.
     */
    val mixes: StateFlow<List<Collection>> = combine(
        combine(
            songs,
            events.distinctUntilChangedBy { it.size },
            historyFlow.distinctUntilChangedBy { it.plays.size to it.skips.size },
            songById, genres,
        ) { a, b, c, d, e -> MixIn(a, b, c, d, e) },
        blocked, hour, favorites,
    ) { (allSongs, ev, hist, byId, gens), block, hr, favs ->
        val all = allSongs
        // Generated mixes leave out hidden and snoozed songs; your own lists (favourites, most played) don't.
        val open = all.filter { it.id !in block }
        val plays = hist.plays
        val taste = hist.taste
        val counts = taste.plays
        val anyPlay = ev.mapTo(HashSet()) { it.songId }
        val lastAt = plays.groupBy { it.songId }.mapValues { (_, l) -> l.maxOf { it.startedAt } }
        val now = System.currentTimeMillis()
        val monthAgo = now - 30L * 24 * 3600 * 1000
        val artistMs = taste.artistMs
        // New every day, and stable within the day: each mix has its own seed, so a play being
        // saved (every 15 s) only nudges the weights instead of reshuffling every mix.
        val day = LocalDate.now().toEpochDay()
        fun rng(key: String) = kotlin.random.Random(day * 31 + key.hashCode())
        fun pick(key: String, pool: List<SongEntity>, n: Int, w: (SongEntity) -> Double = taste::score): List<SongEntity> {
            val r = rng(key)
            return spreadShuffle(weightedSample(pool, n, r, w), r)
        }

        // Recent plays count more: a play loses half its weight every 30 days.
        val decayed = HashMap<Long, Double>()
        plays.forEach { e -> decayed.merge(e.songId, Math.pow(0.5, (now - e.startedAt) / (30.0 * 24 * 3600 * 1000)), Double::plus) }
        val mostPlayed = counts.keys
            .sortedWith(compareByDescending<Long> { counts[it] ?: 0 }.thenByDescending { decayed[it] ?: 0.0 })
            .take(50).mapNotNull { byId[it] }
        val recent = all.sortedByDescending { it.dateAddedSec }.take(50)
        // Online songs you love sit after the library's, newest saved first.
        val favourites = all.filter { it.id in favs } + byId.values.filter { it.isOnline && it.id in favs }.sortedByDescending { it.dateAddedSec }
        // Loved once (3+ plays) but not played for a month. Empty until that is true.
        val forgotten = counts.filter { (id, n) -> n >= 3 && (lastAt[id] ?: 0L) < monthAgo }
            .keys.sortedByDescending { counts[it] }.take(40).mapNotNull { byId[it] }
        val onRepeat = plays.filter { it.startedAt >= monthAgo }.groupingBy { it.songId }.eachCount()
            .filter { it.value >= 3 }.entries.sortedByDescending { it.value }.take(40).mapNotNull { byId[it.key] }
        val lastPlayed = plays.sortedByDescending { it.startedAt }.map { it.songId }.distinct().take(50).mapNotNull { byId[it] }
        // Never started at all; artists you already like come up first.
        val neverPlayed = pick("never", open.filter { it.id !in anyPlay }, 60, taste::discoveryScore)
        val quick = pick("quick", open.filter { it.durationMs in 60_000..180_000 }, 40)
        val longOnes = open.filter { it.durationMs >= 6 * 60_000 }.sortedByDescending { it.durationMs }.take(40)
        // Your top artists, plus songs that share their genres: like a radio station for each.
        val topArtists = artistMs.entries.sortedByDescending { it.value }.take(3).map { it.key }
        val artistMixes = topArtists.mapNotNull { key ->
            val own = open.filter { s -> splitArtists(s.artist).any { normalize(it) == key } }
            if (own.size < 3) return@mapNotNull null
            val name = splitArtists(own.first().artist).first { normalize(it) == key }
            val genreKeys = own.flatMap { splitGenres(it.genre) }.map(::genreKey).toSet()
            val related = if (genreKeys.isEmpty()) emptyList()
            else open.filter { s -> s !in own && splitGenres(s.genre).any { genreKey(it) in genreKeys } }
            val r = rng("artist:$key")
            val songs = spreadShuffle(weightedSample(own, 20, r, taste::score) + weightedSample(related, 30, r, taste::score), r)
            Collection("mix-artist:$name", "$name radio", "$name and similar songs, new every day", songs)
        }
        val genreMixes = gens.take(6).map { g ->
            val songs = pick("genre:${g.name}", g.songs.filter { it.id !in block }, 50)
            Collection("mix-genre:${g.name}", "${g.name} mix", "${songs.size} songs, new every day", songs)
        }
        // daylist: what you play at this time of day, plus songs like it. Changes through the day.
        val part = dayPart(hr)
        val zone = ZoneId.systemDefault()
        val partCounts = plays.filter { dayPart(Instant.ofEpochMilli(it.startedAt).atZone(zone).hour) == part }
            .groupingBy { it.songId }.eachCount().filterKeys { it !in block }
        val daylist = if (partCounts.size < 3) emptyList() else {
            val r = rng("day:${part.name}")
            val usual = weightedSample(partCounts.keys.mapNotNull { byId[it] }, 25, r) { s -> (partCounts[s.id] ?: 1) * taste.score(s) }
            spreadShuffle(usual + similarSongs(usual.take(15), open, taste, 20, r, usual.mapTo(HashSet()) { it.id }), r)
        }
        listOfNotNull(
            Collection("daylist", "Your ${part.label} mix", "What you play around this time, changes through the day", daylist).takeIf { daylist.isNotEmpty() },
        ) + listOf(
            Collection("most", "Most played", "Your most listened songs", mostPlayed),
            Collection("recent", "Recently added", "${recent.size} songs", recent),
            Collection("favourites", "Favourites", "${favourites.size} songs", favourites),
            Collection("forgotten", "Forgotten gems", "Loved once, not played this month", forgotten),
            Collection("onrepeat", "On repeat", "Played 3+ times this month", onRepeat),
            Collection("lastplayed", "Recently played", "${lastPlayed.size} songs", lastPlayed),
            Collection("never", "Never played", "Something new from your library", neverPlayed),
            Collection("quick", "Quick hits", "Under 3 minutes", quick),
            Collection("long", "Long listens", "6 minutes or more", longOnes),
        ) + artistMixes + genreMixes
    }.flowOn(Dispatchers.Default).distinctUntilChanged().onEach { _mixesReady.value = true }.always(emptyList())

    private val _mixesReady = MutableStateFlow(false)
    /** False until mixes have been worked out once, so Home doesn't flash its empty message. */
    val mixesReady: StateFlow<Boolean> = _mixesReady.asStateFlow()

    /** Songs from the album you were last listening to, for the "Continue" card. */
    val continueAlbum: StateFlow<Collection?> = combine(currentSong, albumIndex) { cur, idx ->
        cur?.let { c -> idx[c.albumId]?.toCollection() }
    }.state(null)

    /** This year's recap; in January, last year's until this year has some listening. */
    val recap: StateFlow<Recap?> = combine(events, songById) { ev, byId ->
        val y = LocalDate.now().year
        yearRecap(y, ev, byId, ZoneId.systemDefault())?.takeIf { it.minutes >= 30 || LocalDate.now().monthValue > 1 }
            ?: yearRecap(y - 1, ev, byId, ZoneId.systemDefault())
    }.flowOn(Dispatchers.Default).state(null)

    /** Custom playlist covers, kept as files: playlist id -> image file. */
    private val coverDir = java.io.File(app.filesDir, "playlist-covers").apply { mkdirs() }
    private val _covers = MutableStateFlow(readCovers())
    val playlistCovers: StateFlow<Map<Long, java.io.File>> = _covers.asStateFlow()
    private fun readCovers(): Map<Long, java.io.File> =
        coverDir.listFiles().orEmpty().mapNotNull { f -> f.nameWithoutExtension.substringBefore('-').toLongOrNull()?.let { it to f } }.toMap()

    private val _statsRange = MutableStateFlow(StatsRange.WEEK)
    val statsRange: StateFlow<StatsRange> = _statsRange.asStateFlow()
    private val _selectedBar = MutableStateFlow(-1)
    val selectedBar: StateFlow<Int> = _selectedBar.asStateFlow()

    val stats: StateFlow<StatsUi> = combine(events, songById, _statsRange) { ev, byId, range -> computeStats(ev, byId, range) }
        .flowOn(Dispatchers.Default).state(StatsUi())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    /** Built once per library change, so typing never re-normalizes the whole library. */
    private val searchIndex: StateFlow<com.skarif.kairos.data.SearchIndex> = combine(songs, albums, artists, genres) { s, al, ar, ge ->
        com.skarif.kairos.data.SearchIndex(s, al, ar.map { it.name }, ge.map { it.name })
    }.flowOn(Dispatchers.Default).state(com.skarif.kairos.data.SearchIndex(emptyList())) // built when Search or the Library filter is on screen

    /** Debounced: results follow typing after a short pause instead of on every key. */
    private val typedQuery = _query.debounce { if (it.isBlank()) 0L else 120L }

    val searchResults: StateFlow<List<SongEntity>> = combine(searchIndex, typedQuery) { idx, q -> idx.songs(q, 200) }
        .flowOn(Dispatchers.Default).state(emptyList())

    /** Artists, albums and genres matching the search, names starting with it first. */
    val searchHits: StateFlow<SearchHits> = combine(typedQuery, searchIndex, artists, genres) { q, idx, ar, ge ->
        if (q.isBlank()) SearchHits() else {
            val arBy = ar.associateBy { it.name }
            val geBy = ge.associateBy { it.name }
            SearchHits(
                artists = idx.artists(q).mapNotNull { arBy[it] }.take(20),
                albums = idx.albums(q).take(20),
                genres = idx.genres(q).mapNotNull { geBy[it] }.take(12),
            )
        }
    }.flowOn(Dispatchers.Default).state(SearchHits())

    // ---------------------------------------------------------------- library filter

    private val _libraryFilter = MutableStateFlow("")
    fun setLibraryFilter(q: String) { _libraryFilter.value = q }

    /** What the Library filter box matches, per tab; null when the box is empty. */
    val libraryHits: StateFlow<LibraryHits?> = combine(_libraryFilter.debounce { if (it.isBlank()) 0L else 120L }, searchIndex) { q, idx ->
        if (q.isBlank()) null
        else LibraryHits(idx.songIds(q), idx.albums(q).mapTo(HashSet()) { it.id }, idx.artists(q).toSet(), idx.genres(q).toSet())
    }.flowOn(Dispatchers.Default).state(null)

    private val _folders = MutableStateFlow<List<FolderInfo>>(emptyList())
    val folders: StateFlow<List<FolderInfo>> = _folders.asStateFlow()

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val backup = com.skarif.kairos.data.Backup(ct.db, ct.settings)

    /** Writes a backup file (favourites, playlists, history, settings) to where you picked. */
    fun backupTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = backup.export(ct.db.songs().observeAll().first(), playlistCovers.value)
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                getApplication<Application>().getSharedPreferences("backup", android.content.Context.MODE_PRIVATE)
                    .edit().putLong("last_backup_at", System.currentTimeMillis()).apply()
            }.onSuccess { toast("Backup saved") }.onFailure { toast("Couldn't save the backup: ${it.message}") }
        }
    }

    fun restoreFrom(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                backup.restore(json, ct.db.songs().observeAll().first()) { id, bytes ->
                    java.io.File(coverDir, "$id-${System.currentTimeMillis()}.jpg").writeBytes(bytes)
                }.also { _covers.value = readCovers() }
            }.onSuccess { r ->
                toast(
                    "Restored ${r.favourites} favourites, ${r.playlists} playlists, ${r.plays} plays" +
                        if (r.missingSongs > 0) ". ${r.missingSongs} songs aren't on this phone" else "",
                )
            }.onFailure { toast("Couldn't restore: ${it.message ?: "not a Kairos backup"}") }
        }
    }

    /** Deletes all listening history (Stats and the mixes built from it start over). */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { ct.db.playEvents().clear(); toast("Listening history cleared") }
    }

    // ---------------------------------------------------------------- resume queue

    private val queueFile = java.io.File(app.filesDir, "last-queue.txt")

    private fun saveQueue() {
        val n = now.value
        if (n.queue.isEmpty() || !settings.value.resumeQueue || ct.player.filling.value) return
        // Play order, so a shuffled queue comes back in the order you were hearing it.
        val ids = n.queue.map { it.songId }
        val text = "${n.queuePosition}\n${position.value}\n${playingFrom.value.orEmpty()}\n${ids.joinToString(",")}"
        viewModelScope.launch(Dispatchers.IO) { runCatching { queueFile.writeText(text) } }
    }

    private suspend fun restoreQueue() {
        if (!ct.settings.load().resumeQueue) return
        val lines = runCatching { queueFile.readLines() }.getOrNull() ?: return
        if (lines.size < 4) return
        val all = songs.first { it.isNotEmpty() }.associateBy { it.id }
        val list = lines[3].split(',').mapNotNull { it.toLongOrNull()?.let(all::get) }
        if (list.isEmpty()) return
        _playingFrom.value = lines[2].ifBlank { null }
        ct.player.restoreIfEmpty(list, lines[0].toIntOrNull() ?: 0, lines[1].toLongOrNull() ?: 0L)
    }

    /** The song autoplay last added songs for, so it adds only once per "end of queue". */
    private var autoplayedAfter: Long? = null

    // ---------------------------------------------------------------- collections

    fun albumOf(albumId: Long): Collection? = albumIndex.value[albumId]?.toCollection()

    private fun AlbumGroup.toCollection(): Collection {
        val mins = songs.sumOf { it.durationMs } / 60_000
        return Collection("album:$id", title, listOfNotNull(year.takeIf { it > 0 }?.toString(), "${songs.size} songs", "$mins min").joinToString(", "), songs)
    }

    fun collection(key: String): Collection? = when {
        key.startsWith("album:") -> key.removePrefix("album:").toLongOrNull()?.let { albumOf(it) }
        key.startsWith("playlist:") -> key.removePrefix("playlist:").toLongOrNull()?.let { id ->
            playlists.value.firstOrNull { it.id == id }?.let { p -> Collection(key, p.name, "${p.songs.size} songs", p.songs, p.id) }
        }
        key.startsWith("artist:") -> key.removePrefix("artist:").let { name ->
            val l = artists.value.firstOrNull { it.name.equals(name, true) }?.songs
                ?: songs.value.filter { s -> splitArtists(s.artist).any { it.equals(name, true) } }.sortedBy { it.title.lowercase() }
            Collection(key, name, "${l.size} songs, ${l.distinctBy { it.albumId }.size} albums", l)
        }
        key.startsWith("genre:") -> key.removePrefix("genre:").let { name ->
            val l = genres.value.firstOrNull { genreKey(it.name) == genreKey(name) }?.songs.orEmpty()
            Collection(key, name, "${l.size} songs", l)
        }
        else -> mixes.value.firstOrNull { it.key == key }
    }

    // ---------------------------------------------------------------- stats

    private fun computeStats(ev: List<PlayEventEntity>, byId: Map<Long, SongEntity>, range: StatsRange): StatsUi {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun dayOf(e: PlayEventEntity) = Instant.ofEpochMilli(e.startedAt).atZone(zone).toLocalDate()
        val (from, prevFrom) = when (range) {
            StatsRange.WEEK -> today.minusDays(6) to today.minusDays(13)
            StatsRange.MONTH -> today.minusDays(29) to today.minusDays(59)
            StatsRange.YEAR -> today.minusMonths(11).withDayOfMonth(1) to today.minusMonths(23).withDayOfMonth(1)
            StatsRange.ALL -> LocalDate.MIN to LocalDate.MIN
        }
        val inRange = ev.filter { !dayOf(it).isBefore(from) }
        val prev = ev.filter { val d = dayOf(it); !d.isBefore(prevFrom) && d.isBefore(from) }
        val total = inRange.sumOf { it.listenedMs }
        val prevTotal = prev.sumOf { it.listenedMs }
        val change = when {
            range == StatsRange.ALL -> "${inRange.size} plays in total"
            prevTotal == 0L -> "${inRange.size} plays"
            else -> {
                val pct = ((total - prevTotal) * 100 / prevTotal).toInt()
                if (pct >= 0) "$pct% more than the period before" else "${-pct}% less than the period before"
            }
        }
        val loc = Locale.getDefault()
        val bars = when (range) {
            StatsRange.WEEK -> (6 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                StatBar(d.dayOfWeek.getDisplayName(TextStyle.SHORT, loc), inRange.filter { dayOf(it) == d }.sumOf { it.listenedMs })
            }
            StatsRange.MONTH -> (3 downTo 0).map { back ->
                val end = today.minusDays(back * 7L)
                val start = end.minusDays(6)
                StatBar("${start.dayOfMonth}/${start.monthValue}", inRange.filter { val d = dayOf(it); !d.isBefore(start) && !d.isAfter(end) }.sumOf { it.listenedMs })
            }
            StatsRange.YEAR -> (5 downTo 0).map { back ->
                val m = today.minusMonths(back.toLong())
                StatBar(m.month.getDisplayName(TextStyle.SHORT, loc), inRange.filter { val d = dayOf(it); d.year == m.year && d.month == m.month }.sumOf { it.listenedMs })
            }
            StatsRange.ALL -> {
                val years = ev.map { dayOf(it).year }.distinct().sorted().takeLast(6).ifEmpty { listOf(today.year) }
                years.map { y -> StatBar("$y", ev.filter { dayOf(it).year == y }.sumOf { it.listenedMs }) }
            }
        }
        val top = inRange.groupBy { it.songId }
            .mapNotNull { (id, l) -> byId[id]?.let { TopSong(it, l.size, l.sumOf { e -> e.listenedMs }) } }
            .sortedByDescending { it.listenedMs }.take(5)
        val days = ev.mapTo(HashSet()) { dayOf(it) }
        var streak = 0
        var d = if (today in days) today else today.minusDays(1)
        while (d in days) { streak++; d = d.minusDays(1) }
        val spanDays = when (range) {
            StatsRange.ALL -> (days.minOrNull()?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) + 1 } ?: 1L)
            else -> java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1
        }.coerceAtLeast(1L)
        val peak = inRange.groupingBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).hour }.eachCount().maxByOrNull { it.value }?.key
        val peakHour = peak?.let { h -> java.time.LocalTime.of(h, 0).format(java.time.format.DateTimeFormatter.ofPattern("h a", loc)) } ?: ""
        val artistTime = HashMap<String, Pair<String, Long>>()
        inRange.forEach { e ->
            byId[e.songId]?.let { s ->
                splitArtists(s.artist).forEach { a ->
                    val k = a.lowercase()
                    artistTime[k] = a to ((artistTime[k]?.second ?: 0L) + e.listenedMs)
                }
            }
        }
        return StatsUi(
            total, inRange.size, change, bars, top, streak,
            uniqueSongs = inRange.mapTo(HashSet()) { it.songId }.size,
            avgPerDayMs = total / spanDays,
            completedPct = if (inRange.isEmpty()) 0 else inRange.count { it.completed } * 100 / inRange.size,
            peakHour = peakHour,
            topArtists = artistTime.values.sortedByDescending { it.second }.take(5),
        )
    }

    fun setStatsRange(r: StatsRange) { _statsRange.value = r; _selectedBar.value = -1 }
    fun selectBar(i: Int) { _selectedBar.value = i }

    // ---------------------------------------------------------------- actions

    fun scanNow() {
        if (_scan.value == ScanState.Scanning) return
        viewModelScope.launch {
            _scan.value = ScanState.Scanning
            val s = settings.value
            val n = runCatching { ct.scanner.scan(s.excludedFolders, s.minDurationMs) }.getOrDefault(0)
            _scan.value = ScanState.Done(n)
        }
    }

    fun loadFolders() {
        viewModelScope.launch { _folders.value = ct.scanner.listFolders(settings.value.minDurationMs) }
    }

    fun setQuery(q: String) { _query.value = q; onlineUi.setQuery(q) }

    // ---------------------------------------------------------------- online (online flavor)

    /** The streaming source's name, or null in the Play Store build. */
    val onlineName: String? = Flavor.online?.name

    /** Online songs seen this session, so they can be saved when favourited or added to a playlist. */
    private val seenOnline = java.util.concurrent.ConcurrentHashMap<Long, SongEntity>()

    /** Explore, online search, network state and the stream cache; inert in the play build. */
    val onlineUi = OnlineFeatures(app, ct, viewModelScope, seen = { l -> l.forEach { seenOnline[it.id] = it } },
        notify = { notify(it) },
        onSavedToPhone = {
            // Android reads a new file's length a moment after it's written; scanning at once found
            // it with no duration, the short-clip filter dropped it, and it never showed in Library.
            if (com.skarif.kairos.ui.screens.hasAudioPermission(app)) viewModelScope.launch {
                kotlinx.coroutines.delay(2_500); scanNow()
                kotlinx.coroutines.delay(8_000); scanNow()
            }
        },
    )

    /** Online songs only exist in Room once kept; do that before they are played or referenced. */
    private fun keepOnline(songs: List<SongEntity>) {
        val online = songs.filter { it.isOnline && it.id !in songById.value }
        if (online.isNotEmpty()) ct.scope.launch { ct.db.songs().upsertAll(online) }
    }

    private suspend fun keepOnlineIds(ids: List<Long>) {
        val rows = ids.filter { it < 0 && it !in songById.value }.mapNotNull { seenOnline[it] }
        if (rows.isNotEmpty()) ct.db.songs().upsertAll(rows)
    }

    /** Online songs aren't in [songById] until kept; this finds them either way. */
    fun anySong(id: Long): SongEntity? = songById.value[id] ?: seenOnline[id]

    // ---------------------------------------------------------------- video (online flavor)

    /**
     * The listener asked for the picture, not just the sound. Per song and per visit: it resets
     * when the song changes, when the player closes and when the app goes to the background
     * (video costs ten times the data of a song, and nothing is looking at it then).
     */
    private fun videoPrefetchQuality(): com.skarif.kairos.data.StreamQuality {
        val s = settings.value
        val metered = getApplication<Application>()
            .getSystemService(android.net.ConnectivityManager::class.java)
            ?.isActiveNetworkMetered == true
        return if (s.dataSaverOnMobile && metered) com.skarif.kairos.data.StreamQuality.LOW else s.streamQuality
    }

    private val videoWanted = MutableStateFlow(false)
    private val _videoFellBack = MutableStateFlow(false)
    /** Video was chosen but this song has none (or the check failed): the player went back to the sound, and says so. */
    val videoFellBack: StateFlow<Boolean> = _videoFellBack.asStateFlow()
    val wantsVideo: StateFlow<Boolean> = videoWanted.asStateFlow()

    // Remember the user's Song/Video choice while the full player is minimized. The active
    // request is still turned off in mini-player mode so video data/decoding is not kept running
    // without a visible video surface. Reopening the player restores this preference.
    private val videoPreferred = MutableStateFlow(false)

    /** The full player is open: only then is the song's video looked for (one search per song). */
    private val playerOpen = MutableStateFlow(false)
    private val videoRetryTick = MutableStateFlow(0)
    private var minimizeVideoGraceJob: kotlinx.coroutines.Job? = null
    /** The song whose video already got its one automatic retry after an error. */
    private var videoRetriedFor: Long? = null
    /** The "slow connection" notice on screen, so it can be removed when playback recovers. */
    private var slowNoticeId: Long? = null

    /** Search again for [song]'s video, also when an earlier search found nothing (that answer is cached for a day). */
    fun retryVideoCheck(song: SongEntity? = null) {
        if (song != null) Flavor.online?.forgetVideoMatch(song)
        videoRetryTick.value++
    }

    /** Videos that came up when searching for [song], for picking one by hand. Throws on network errors. */
    suspend fun videoChoices(song: SongEntity): List<SongEntity> {
        val online = Flavor.online ?: return emptyList()
        val query = "${song.title} ${song.artist.split(',', '&').first().trim()}".trim()
        return online.search(query, com.skarif.kairos.online.OnlineKind.VIDEOS).items
            .filterIsInstance<com.skarif.kairos.online.OnlineItem.Track>()
            .map { it.song }
    }

    /** The listener picked [videoId] as [song]'s video: remember it, look again, and switch to Video once it is ready. */
    fun chooseVideo(song: SongEntity, videoId: String) {
        val online = Flavor.online ?: return
        online.chooseVideo(song, videoId)
        retryVideoCheck()
        viewModelScope.launch {
            val ready = withTimeoutOrNull(8_000) {
                videoState.first { it is VideoState.Available && it.songId == song.id }
            }
            if (ready != null) setVideo(true)
        }
    }

    val videoSize = ct.player.videoSize
    val videoFirstFrame = ct.player.firstFrame

    /** Whether the playing song can be shown as a video; [VideoState.None] hides the Song/Video switch. */
    val videoState: StateFlow<VideoState> = combine(
        currentSong.distinctUntilChangedBy { it?.id },
        combine(playerOpen, videoPreferred) { open, pref -> open || pref }.distinctUntilChanged(),
        videoRetryTick,
    ) { s, active, tick -> Triple(s, active, tick) }
        .distinctUntilChanged()
        .flatMapLatest { (s, open, _) ->
            val online = Flavor.online
            if (s == null || online == null || !s.isOnline || !open) flowOf<VideoState>(VideoState.None)
            else flow<VideoState> {
                emit(VideoState.Checking)
                try {
                    val id = online.videoFor(s)
                    emit(if (id != null) VideoState.Available(s.id, id) else VideoState.Unavailable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("KairosVideo", "video lookup failed temporarily for ${s.id}", e)
                    emit(VideoState.Error(onlineUi.describeError(e)))
                }
            }
        }
        .always(VideoState.None)

    fun setVideo(on: Boolean) {
        if (on && videoState.value !is VideoState.Available) return
        minimizeVideoGraceJob?.cancel()
        videoPreferred.value = on
        videoWanted.value = on
        // Back to Audio on purpose: the "no video" card on the cover has done its job.
        if (!on) _videoFellBack.value = false
    }

    /**
     * App sent to the background: stop the picture (no data, no decoding) but keep the listener's
     * Song/Video choice, so coming back resumes the same mode instead of silently reverting to audio.
     */
    fun pauseVideoForBackground() {
        minimizeVideoGraceJob?.cancel()
        // No stream swap: the sound keeps playing without a hiccup, only the picture is switched off.
        ct.player.setVideoRendering(false)
    }

    /** App back in front with the full player open: restore the Song/Video choice. */
    fun resumeVideoIfPreferred() {
        if (playerOpen.value) ct.player.setVideoRendering(true)
        if (playerOpen.value && videoPreferred.value) videoWanted.value = true
    }

    fun setPlayerOpen(open: Boolean) {
        playerOpen.value = open
        minimizeVideoGraceJob?.cancel()
        if (open) {
            ct.player.setVideoRendering(true)
            videoWanted.value = videoPreferred.value
        } else if (videoWanted.value) {
            // Minimised: keep the stream (swapping back to audio would pause and rebuffer the song),
            // just stop drawing and decoding the picture. Reopening the player brings it back at once.
            ct.player.setVideoRendering(false)
        }
    }

    fun attachVideo(view: android.view.TextureView) = ct.player.attachVideo(view)
    fun detachVideo(view: android.view.TextureView) = ct.player.detachVideo(view)

    init {
        // Keep the user's Video preference across tracks. Tracks without a match remain audio-only;
        // the next track with a valid match can automatically return to video.
        viewModelScope.launch {
            now.map { it.songId }.filterNotNull().distinctUntilChanged().collect {
                videoRetriedFor = null
                _videoFellBack.value = false
                // Only with the player open: a new song while minimised starts as plain sound (no
                // picture to prepare); the Video choice comes back when the player is opened.
                if (playerOpen.value) ct.player.setVideoRendering(true)
                videoWanted.value = playerOpen.value && videoPreferred.value
            }
        }
        // Video is chosen but this song has no video (or the lookup failed): go back to the sound right away
        // instead of sitting on an empty picture. The choice itself is kept, so the next song that has a
        // video comes back as video, and "Find video" (or a retry that succeeds) brings this one back.
        viewModelScope.launch {
            videoState.collect { st ->
                when (st) {
                    is VideoState.Unavailable, is VideoState.Error ->
                        if (videoWanted.value && playerOpen.value) { videoWanted.value = false; _videoFellBack.value = true }
                    is VideoState.Available ->
                        if (_videoFellBack.value && playerOpen.value && videoPreferred.value) { _videoFellBack.value = false; videoWanted.value = true }
                    else -> {}
                }
            }
        }
        ct.player.onVideoError = { why ->
            val ready = videoState.value as? VideoState.Available
            val sid = now.value.songId
            val online = Flavor.online
            if (videoWanted.value && ready != null && online != null && sid != null && videoRetriedFor != sid) {
                // One quiet retry with a fresh stream address (the address may simply have expired,
                // or the network switched), going back to the song's sound in between.
                videoRetriedFor = sid
                online.forgetStream(online.videoUri(ready.videoId))
                videoWanted.value = false
                viewModelScope.launch {
                    kotlinx.coroutines.delay(400)
                    if (playerOpen.value && videoPreferred.value && now.value.songId == sid) videoWanted.value = true
                }
            } else {
                if (videoWanted.value) notify("Couldn't play video: $why · Playing audio instead", isError = true)
                // Keep the preference, but stop retrying this track in a tight loop. The next track
                // change re-arms videoWanted and can resume video if that track has a valid match.
                videoWanted.value = false
            }
        }
        // Slow or dropped network while streaming: the same notice for a song and a video, shown
        // while it lasts and taken away as soon as playback is running again.
        ct.player.onSlowNetwork = { video ->
            val what = if (video) "video" else "song"
            if (!onlineUi.networkUp.value) notify("You're offline. The $what will carry on when you reconnect.", isError = true, durationMs = 10_000)
            else notify("Slow connection: the $what is still loading…", durationMs = 10_000)
            slowNoticeId = _notice.value?.id
        }
        ct.player.onNetworkRecovered = {
            slowNoticeId?.let { dismissNotice(it) }
            slowNoticeId = null
        }
        ct.player.onPlaybackError = { title, why, skipped ->
            val prefix = if (!title.isNullOrBlank()) "Couldn't play \"$title\"" else "Couldn't play song"
            val suffix = if (skipped) " · Skipping" else ""
            notify("$prefix: $why$suffix", isError = true)
        }
        // Keeps what plays in step with what was asked. Prepare a short video range while audio
        // continues, then swap. A failed prefetch is non-fatal: normal streaming remains the fallback.
        viewModelScope.launch {
            combine(videoWanted, videoState, now.map { it.videoActive }.distinctUntilChanged()) { wanted, st, active -> Triple(wanted, st, active) }
                .collectLatest { (wanted, st, active) ->
                    val online = Flavor.online ?: return@collectLatest
                    val song = currentSong.value ?: return@collectLatest
                    // An answer for the song before this one must not turn this one into its video.
                    if (wanted && active && st is VideoState.Checking) return@collectLatest
                    val target = wanted && st is VideoState.Available && st.songId == now.value.songId && st.songId == song.id
                    when {
                        target && !active -> {
                            val quality = videoPrefetchQuality()
                            try {
                                val prefetched = withTimeoutOrNull(6_000) {
                                    online.prefetchVideo(getApplication(), song, quality)
                                    true
                                } ?: false
                                if (!prefetched) android.util.Log.w("KairosVideo", "startup prefetch timed out; falling back to streaming")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("KairosVideo", "startup prefetch failed; falling back to streaming", e)
                            }
                            // Do not switch if the user turned video off or changed songs while prefetching.
                            if (videoWanted.value && now.value.songId == song.id) {
                                val ready = videoState.value as? VideoState.Available
                                if (ready?.songId == song.id) ct.player.swapStream(online.videoUri(ready.videoId))
                            }
                        }
                        !target && active -> ct.player.swapStream(online.mediaUri(song))
                    }
                }
        }

        // Prepare only the next song's match and stream metadata. No video bytes are downloaded
        // until the listener explicitly enables Video for that song.
        viewModelScope.launch {
            now.map { n -> n.queue.getOrNull(n.queuePosition + 1)?.songId }
                .distinctUntilChanged()
                .collectLatest { nextId ->
                    val online = Flavor.online ?: return@collectLatest
                    val id = nextId ?: return@collectLatest
                    val song = anySong(id) ?: songById.first { it.containsKey(id) }[id] ?: return@collectLatest
                    if (!song.isOnline) return@collectLatest
                    try {
                        online.prefetchVideoMetadata(song, videoPrefetchQuality())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.d("KairosVideo", "next-track metadata prefetch skipped: ${e.message}")
                    }
                }
        }
    }

    private val _playingFrom = MutableStateFlow<String?>(null)
    /** "Playing from" label for the player: the album, playlist or screen the queue came from. */
    val playingFrom: StateFlow<String?> = _playingFrom.asStateFlow()

    fun play(list: List<SongEntity>, index: Int, from: String? = null) { keepOnline(list); _playingFrom.value = from; ct.player.play(list, index) }

    /** Song radio: this song, then similar songs from your library. */
    /**
     * Song radio: the song, then 50 that fit it, closest first and widening out (see similarSongs).
     * Autoplay keeps it going when those run out.
     */
    fun startRadio(song: SongEntity) {
        val alreadyPlaying = now.value.songId == song.id
        if (song.isOnline) {
            if (!alreadyPlaying) {
                play(listOf(song), 0, from = "${song.title} radio")
            } else {
                _playingFrom.value = "${song.title} radio"
                notify("Loading ${song.title} radio…")
            }
            viewModelScope.launch {
                val playedIds = if (alreadyPlaying) now.value.queue.take(now.value.queuePosition + 1).map { it.songId }.toSet() else emptySet()
                val more = onlineUi.radio(song).filter { it.id != song.id && it.id !in playedIds }
                if (more.isEmpty()) {
                    notify(if (onlineUi.networkUp.value) "Couldn't load radio for ${song.title}" else "Radio needs a connection")
                } else if (now.value.songId == song.id) {
                    keepOnline(more)
                    if (alreadyPlaying) ct.player.clearUpcoming()
                    ct.player.append(more)
                    if (alreadyPlaying) notify("Queue updated with ${more.size} radio songs")
                }
            }
            return
        }
        val queuedIds = if (alreadyPlaying) now.value.queue.map { it.songId }.toSet() else emptySet()
        val more = similarSongs(listOf(song), songs.value, historyFlow.value.taste, 50, kotlin.random.Random.Default, blocked.value + queuedIds, drift = true)
        if (alreadyPlaying) {
            _playingFrom.value = "${song.title} radio"
            ct.player.clearUpcoming()
            ct.player.append(more)
            notify("Queue updated with ${more.size} radio songs")
        } else {
            play(listOf(song) + more, 0, from = "${song.title} radio")
        }
    }

    fun setSpeed(v: Float) { ct.player.setSpeed(v) }
    /**
     * Shuffles with artists spread out, then plays that order with the player's own shuffle off.
     * The queue shows exactly what will play, and play next / reorder keep working.
     */
    fun shuffle(list: List<SongEntity>, smart: Boolean = settings.value.smartShuffle, from: String? = null) {
        if (list.isEmpty()) return
        _playingFrom.value = from
        val r = kotlin.random.Random.Default
        val order = if (smart) smartShuffle(list, songs.value, historyFlow.value.taste, r, blocked.value) else spreadShuffle(list, r)
        keepOnline(order)
        ct.player.play(order, 0)
    }

    /** Shuffle the whole library (or a big list), leaving out hidden and snoozed songs. */
    fun shuffleAll(list: List<SongEntity>, from: String? = "Your library") = shuffle(list.filter { it.id !in blocked.value }.ifEmpty { list }, smart = false, from = from)

    // ---------------------------------------------------------------- hide, snooze, taste

    fun setHidden(songId: Long, hidden: Boolean) {
        updateSettings { s -> s.copy(hiddenSongs = if (hidden) s.hiddenSongs + "$songId" else s.hiddenSongs - "$songId") }
        if (hidden) notify("Hidden from mixes and autoplay") { setHidden(songId, false) }
    }

    /** Keeps a song out of mixes and autoplay for [days] days. */
    fun snooze(songId: Long, days: Int = 30) {
        updateSettings { s ->
            val until = System.currentTimeMillis() + days * 24L * 3600 * 1000
            s.copy(snoozedSongs = s.snoozedSongs.filterNot { it.startsWith("$songId:") }.toSet() + "$songId:$until")
        }
        notify("Snoozed for $days days") { unsnooze(songId) }
    }

    fun unsnooze(songId: Long) = updateSettings { s -> s.copy(snoozedSongs = s.snoozedSongs.filterNot { it.startsWith("$songId:") }.toSet()) }

    fun setTasteExcluded(songId: Long, excluded: Boolean) = updateSettings { s ->
        s.copy(tasteExcluded = if (excluded) s.tasteExcluded + "$songId" else s.tasteExcluded - "$songId")
    }

    fun clearHiddenAndSnoozed() = updateSettings { it.copy(hiddenSongs = emptySet(), snoozedSongs = emptySet()) }
    fun clearTasteExclusions() = updateSettings { it.copy(tasteExcluded = emptySet()) }

    // ---------------------------------------------------------------- playlist tools

    fun renamePlaylist(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { ct.db.playlists().rename(id, name.trim()) }
    }

    fun removeFromPlaylist(id: Long, index: Int) {
        val p = playlists.value.firstOrNull { it.id == id } ?: return
        if (index !in p.songs.indices) return
        val before = p.songs.map { it.id }
        viewModelScope.launch { ct.db.playlists().replaceSongs(id, before.toMutableList().apply { removeAt(index) }) }
        notify("Removed ${p.songs[index].title} from ${p.name}") { viewModelScope.launch { ct.db.playlists().replaceSongs(id, before) } }
    }

    /** Reorders a whole playlist; Undo brings the old order back. */
    fun sortPlaylist(id: Long, by: PlaylistSort) {
        val p = playlists.value.firstOrNull { it.id == id } ?: return
        val before = p.songs.map { it.id }
        val ci = String.CASE_INSENSITIVE_ORDER
        val sorted = when (by) {
            PlaylistSort.TITLE -> p.songs.sortedWith(compareBy<SongEntity, String>(ci) { it.title })
            PlaylistSort.ARTIST -> p.songs.sortedWith(compareBy<SongEntity, String>(ci) { it.artist }.thenBy(ci) { it.title })
            PlaylistSort.ALBUM -> p.songs.sortedWith(compareBy<SongEntity, String>(ci) { it.album }.thenBy { it.track })
            PlaylistSort.NEWEST -> p.songs.sortedByDescending { it.dateAddedSec }
            PlaylistSort.OLDEST -> p.songs.sortedBy { it.dateAddedSec }
            PlaylistSort.REVERSE -> p.songs.reversed()
            PlaylistSort.SHUFFLE -> p.songs.shuffled()
        }.map { it.id }
        if (sorted == before) return
        viewModelScope.launch { ct.db.playlists().replaceSongs(id, sorted) }
        notify("${p.name}: ${by.label.lowercase()}") { viewModelScope.launch { ct.db.playlists().replaceSongs(id, before) } }
    }

    /** Keeps each song's first place in the playlist and drops the repeats. */
    fun removePlaylistDuplicates(id: Long) {
        val p = playlists.value.firstOrNull { it.id == id } ?: return
        val before = p.songs.map { it.id }
        val after = before.distinct()
        if (after.size == before.size) { notify("No duplicates in ${p.name}"); return }
        viewModelScope.launch { ct.db.playlists().replaceSongs(id, after) }
        notify("Removed ${before.size - after.size} duplicates from ${p.name}") { viewModelScope.launch { ct.db.playlists().replaceSongs(id, before) } }
    }

    fun duplicatePlaylist(id: Long) {
        val p = playlists.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { ct.db.playlists().create("${p.name} copy", p.songs.map { it.id }) }
        notify("Duplicated ${p.name}")
    }

    fun moveInPlaylist(id: Long, from: Int, to: Int) {
        val p = playlists.value.firstOrNull { it.id == id } ?: return
        if (from !in p.songs.indices || to !in p.songs.indices || from == to) return
        val ids = p.songs.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
        viewModelScope.launch { ct.db.playlists().replaceSongs(id, ids) }
    }

    /** Copies the picked image into app storage, scaled down, as the playlist's cover. */
    fun setPlaylistCover(id: Long, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            runCatching {
                val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@runCatching
                val side = minOf(src.width, src.height)
                val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                val scaled = Bitmap.createScaledBitmap(square, 720.coerceAtMost(side), 720.coerceAtMost(side), true)
                coverDir.listFiles().orEmpty().filter { it.name.startsWith("$id-") }.forEach { it.delete() }
                // A new file name each time, so screens that cached the old image reload it.
                java.io.File(coverDir, "$id-${System.currentTimeMillis()}.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }.onFailure { toast("Couldn't use that image") }
            _covers.value = readCovers()
        }
    }

    fun removePlaylistCover(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            coverDir.listFiles().orEmpty().filter { it.name.startsWith("$id-") }.forEach { it.delete() }
            _covers.value = readCovers()
        }
    }

    fun exportPlaylist(id: Long, uri: Uri) {
        val p = playlists.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(toM3u(p.songs).toByteArray()) }
            }.onSuccess { toast("Saved ${p.name} (${p.songs.size} songs)") }.onFailure { toast("Couldn't save the playlist") }
        }
    }

    /** Reads an .m3u/.m3u8 file and makes a playlist from the songs found on this phone. */
    fun importPlaylist(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }?.substringBeforeLast('.') ?: "Imported playlist"
                val (found, missing) = parseM3u(text, songs.value)
                if (found.isEmpty()) { toast("None of those songs are on this phone"); return@runCatching }
                ct.db.playlists().create(name, found.map { it.id })
                toast("Imported $name: ${found.size} songs" + if (missing > 0) ", $missing not found" else "")
            }.onFailure { toast("Couldn't read that file") }
        }
    }

    private val _reselect = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** A tab that was tapped while already open: its page scrolls back to the top. */
    val tabReselected: kotlinx.coroutines.flow.SharedFlow<String> = _reselect
    fun reselectTab(route: String) { _reselect.tryEmit(route) }

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    fun notify(text: String, isError: Boolean = false, durationMs: Long? = null, undo: (() -> Unit)? = null) { _notice.value = Notice(text, undo, isError, durationMs = durationMs) }
    fun dismissNotice(id: Long) { if (_notice.value?.id == id) _notice.value = null }

    private suspend fun toast(text: String) = kotlinx.coroutines.withContext(Dispatchers.Main) { notify(text) }
    fun playNext(list: List<SongEntity>) {
        if (list.isEmpty()) return
        keepOnline(list)
        ct.player.playNext(list)
        notify(if (list.size == 1) "${list[0].title} plays next" else "${list.size} songs play next")
    }
    suspend fun firstPlayedAt(songId: Long): Long? = ct.db.playEvents().firstPlayedAt(songId)
    /** Adds to the end of the queue. */
    fun addToQueue(list: List<SongEntity>) {
        if (list.isEmpty()) return
        keepOnline(list)
        ct.player.append(list)
        notify(if (list.size == 1) "Added to queue" else "${list.size} songs added to queue")
    }
    fun toggle() { ct.player.toggle() }
    fun next() { ct.player.next() }
    fun previous() { ct.player.previous() }
    /** Always the previous song, never a restart of this one. */
    fun previousTrack() { ct.player.previousTrack() }
    fun seekTo(ms: Long) { ct.player.seekTo(ms) }
    fun playQueueItem(i: Int) { ct.player.playQueueItem(i) }
    fun moveQueueItem(from: Int, to: Int) { ct.player.moveQueueItem(from, to) }
    fun removeQueueItem(i: Int) {
        val song = now.value.queue.firstOrNull { it.index == i }?.let { songById.value[it.songId] }
        ct.player.removeQueueItem(i)
        if (song != null) notify("Removed ${song.title} from the queue") { ct.player.insertAt(i, listOf(song)) }
    }
    fun moveToNext(i: Int) { ct.player.moveToNext(i) }
    fun moveToEnd(i: Int) { ct.player.moveToEnd(i) }
    fun clearUpcoming() {
        val n = now.value
        // In the player's own order (not shuffle order), which is what insertAt uses.
        val removed = n.queue.filter { it.index > n.queueIndex }.sortedBy { it.index }.mapNotNull { songById.value[it.songId] }
        ct.player.clearUpcoming()
        if (removed.isNotEmpty()) notify("Cleared ${removed.size} songs from up next") { ct.player.append(removed) }
    }
    fun clearPlayed() { ct.player.clearPlayed() }
    fun toggleShuffle() { ct.player.toggleShuffle() }
    fun reshuffle() {
        val n = now.value
        val upcoming = n.queue.drop(n.queuePosition + 1).mapNotNull { songById.value[it.songId] }
        val ok = if (!n.shuffle && upcoming.size >= 3 && upcoming.size == n.queue.size - n.queuePosition - 1) ct.player.replaceUpcoming(spreadShuffle(upcoming, kotlin.random.Random.Default)) else ct.player.reshuffleUpcoming()
        if (ok) notify("Shuffled what's coming up")
    }
    fun cycleRepeat() { ct.player.cycleRepeat() }
    fun song(id: Long): SongEntity? = songById.value[id]

    fun startSleep(minutes: Int, fade: Boolean) = ct.sleep.start(minutes, fade)
    fun startSleepEndOfSong(fade: Boolean) = ct.sleep.startEndOfSong(fade)
    fun cancelSleep() = ct.sleep.cancel()

    fun toggleFavorite(songId: Long) {
        viewModelScope.launch {
            if (songId in favorites.value) {
                ct.db.favorites().remove(songId)
                notify("Removed from favourites") { viewModelScope.launch { ct.db.favorites().add(FavoriteEntity(songId, System.currentTimeMillis())) } }
            } else {
                keepOnlineIds(listOf(songId))
                ct.db.favorites().add(FavoriteEntity(songId, System.currentTimeMillis()))
                notify("Added to favourites")
            }
        }
    }

    fun createPlaylist(name: String, songIds: List<Long>) {
        viewModelScope.launch {
            keepOnlineIds(songIds)
            val title = name.ifBlank { "New playlist" }
            ct.db.playlists().create(title, songIds)
            notify("Created \"$title\" with ${songIds.size} songs")
        }
    }

    fun addToPlaylistNamed(playlistId: Long, songIds: List<Long>) {
        val name = playlists.value.firstOrNull { it.id == playlistId }?.name ?: return
        addToPlaylist(playlistId, songIds)
        notify("Added to $name")
    }

    fun addToPlaylist(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch { keepOnlineIds(songIds); ct.db.playlists().append(playlistId, songIds) }
    }

    fun deletePlaylist(playlistId: Long) {
        val p = playlists.value.firstOrNull { it.id == playlistId }
        viewModelScope.launch { ct.db.playlists().delete(playlistId) }
        if (p != null) notify("Deleted ${p.name}") { createPlaylist(p.name, p.songs.map { it.id }) }
    }

    /** Positive = lyrics appear later. */
    fun nudgeLyrics(deltaMs: Long) {
        val s = currentSong.value ?: return
        viewModelScope.launch { ct.lyrics.setOffset(s.id, lyricsOffset.value + deltaMs) }
    }

    fun updateSettings(change: (Settings) -> Settings) {
        viewModelScope.launch { ct.settings.update(change) }
    }

    fun addLrcFolder(uri: Uri) {
        // Read and write when the picker gave both (so lyrics can also be saved there), else read.
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        updateSettings { it.copy(lrcFolderUris = it.lrcFolderUris + uri.toString()) }
    }

    private val _lyricsDownloading = MutableStateFlow(false)
    /** True while the lyrics page is fetching from LRCLIB. */
    val lyricsDownloading: StateFlow<Boolean> = _lyricsDownloading

    /** The lyrics page's download: fetch this song's lyrics now, synced or plain, and show them. */
    fun downloadLyrics(type: com.skarif.kairos.data.OnlineLyricsType) {
        val song = currentSong.value ?: return
        if (_lyricsDownloading.value) return
        _lyricsDownloading.value = true
        viewModelScope.launch {
            val r = try { ct.lyrics.download(song, type) } finally { _lyricsDownloading.value = false }
            lyricsReload.value++
            notify(
                when (r) {
                    com.skarif.kairos.data.LyricsRepository.Download.SYNCED -> "Time-synced lyrics downloaded"
                    com.skarif.kairos.data.LyricsRepository.Download.PLAIN -> "Lyrics downloaded"
                    com.skarif.kairos.data.LyricsRepository.Download.ONLY_PLAIN -> "No synced lyrics for this song, downloaded plain text"
                    com.skarif.kairos.data.LyricsRepository.Download.NOT_FOUND -> "LRCLIB has no lyrics for this song"
                    com.skarif.kairos.data.LyricsRepository.Download.FAILED -> "Couldn't reach LRCLIB. Check your connection"
                },
            )
        }
    }

    /**
     * Saves the downloaded lyrics as an .lrc next to the song. If no allowed folder covers it,
     * [onNeedFolder] runs so the screen can open the system folder picker once.
     */
    fun saveLyricsFile(onNeedFolder: () -> Unit) {
        val song = currentSong.value ?: return
        viewModelScope.launch {
            when (ct.lyrics.saveLrc(song)) {
                com.skarif.kairos.data.LyricsRepository.SaveLrc.SAVED -> notify("Saved ${song.fileName.substringBeforeLast('.')}.lrc next to the song")
                com.skarif.kairos.data.LyricsRepository.SaveLrc.NOTHING -> notify("Download lyrics first, then save them")
                com.skarif.kairos.data.LyricsRepository.SaveLrc.NEED_FOLDER -> onNeedFolder()
            }
        }
    }

    /** The folder picker's answer for saving lyrics: remember it (read and write), then save. */
    fun grantLyricsFolderAndSave(uri: Uri) {
        addLrcFolder(uri)
        saveLyricsFile { notify("That folder doesn't hold this song. Pick its folder, or one above it") }
    }

    /** Drops this song's downloaded lyrics and goes back to its own. */
    fun removeDownloadedLyrics() {
        val song = currentSong.value ?: return
        viewModelScope.launch {
            ct.lyrics.removeDownloaded(song)
            lyricsReload.value++
            notify("Downloaded lyrics removed")
        }
    }

    /** Deletes lyrics saved from LRCLIB; songs are looked up again the next time they play. */
    fun clearOnlineLyrics() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ct.lyrics.clearOnline()
            lyricsReload.value++
        }
        notify("Downloaded lyrics cleared")
    }

    fun removeLrcFolder(uri: String) {
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        updateSettings { it.copy(lrcFolderUris = it.lrcFolderUris - uri) }
    }

    suspend fun thumbnail(song: SongEntity): Bitmap? = ct.artwork.load(song, 160)
    /** The thumbnail if it is already decoded; never touches the disk. */
    fun cachedThumbnail(song: SongEntity): Bitmap? = ct.artwork.peek(song, 160)

    // ---------------------------------------------------------------- colour mood

    /** Album id -> the cover's hue in degrees (null for a grey or black-and-white cover). */
    private var coverHues: MutableMap<Long, Float?>? = null
    private val huesFile get() = java.io.File(getApplication<Application>().filesDir, "cover_hues.txt")
    private var colourJob: kotlinx.coroutines.Job? = null

    /**
     * "Play something blue": shuffles songs whose album cover is mostly that colour. Each cover's
     * colour is worked out once from an 8x8 copy of its thumbnail and saved, so only the first
     * use (or a new album) reads covers.
     */
    fun playByColour(colour: CoverColour) {
        if (colourJob?.isActive == true) return
        colourJob = viewModelScope.launch(Dispatchers.Default) {
            val hues = coverHues ?: loadHues().also { coverHues = it }
            val byAlbum = songs.value.filter { it.id !in blocked.value }.groupBy { it.albumId }
            val missing = byAlbum.keys.filter { it !in hues }
            if (missing.size > 20) notify("Reading your covers' colours…")
            missing.forEach { id -> hues[id] = byAlbum[id]?.first()?.let { s -> ct.artwork.load(s, 160)?.let(::coverHue) } }
            if (missing.isNotEmpty()) saveHues(hues)
            val pick = byAlbum.filterKeys { id -> hues[id]?.let(colour::contains) == true }.values.flatten()
            if (pick.size < 5) { notify("Not enough ${colour.label.lowercase()} covers yet"); return@launch }
            kotlinx.coroutines.withContext(Dispatchers.Main) { shuffle(pick, smart = false, from = "${colour.label} covers") }
        }
    }

    private fun loadHues(): MutableMap<Long, Float?> = runCatching {
        huesFile.readLines().mapNotNull { l ->
            val (k, v) = l.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            (k.toLongOrNull() ?: return@mapNotNull null) to v.toFloatOrNull()
        }.toMap(HashMap())
    }.getOrDefault(HashMap())

    private fun saveHues(h: Map<Long, Float?>) {
        runCatching { huesFile.writeText(h.entries.joinToString("\n") { "${it.key}=${it.value ?: "-"}" }) }
    }
    suspend fun cover(song: SongEntity): Bitmap? = ct.artwork.load(song, 720)
    suspend fun remoteArt(url: String, sizePx: Int): Bitmap? = ct.artwork.loadUrl(url, sizePx)
    fun cachedRemoteArt(url: String, sizePx: Int): Bitmap? = ct.artwork.peekUrl(url, sizePx)
    fun cachedCover(song: SongEntity): Bitmap? = ct.artwork.peek(song, 720)

    // Kept last: Kotlin runs initialisers in file order, and these collectors start
    // immediately, so every field they touch must already exist.
    init {
        viewModelScope.launch { onlineUi.downloadedIds.collect { downloadedOnline.value = it } }
        ct.player.connect()
        // Long queues finish loading only once the player isn't animating.
        ct.player.busy = { com.skarif.kairos.ui.motion.PlayerMotion.animating }
        // Performance log context: queue loading, and the second after a song change.
        viewModelScope.launch { ct.player.filling.collect { com.skarif.kairos.perf.PerfLog.state("queue", if (it) "loading" else null) } }
        viewModelScope.launch {
            now.map { it.songId }.distinctUntilChanged().collectLatest {
                com.skarif.kairos.perf.PerfLog.state("song", "just changed")
                kotlinx.coroutines.delay(1500)
                com.skarif.kairos.perf.PerfLog.state("song", null)
            }
        }
        viewModelScope.launch { restoreQueue() }
        // Save the queue when it, the song, or play/pause changes, and every 10 s while playing.
        viewModelScope.launch {
            now.map { Triple(it.queue, it.queuePosition, it.isPlaying) }.distinctUntilChanged().collect { saveQueue() }
        }
        viewModelScope.launch { ct.player.filling.collect { if (!it) saveQueue() } }
        viewModelScope.launch {
            while (true) { kotlinx.coroutines.delay(10_000); if (now.value.isPlaying) saveQueue() }
        }
        // Autoplay: when the queue is down to its last two songs, top it up (before the end, so there is never a gap).
        viewModelScope.launch {
            combine(now, ct.player.filling) { n, f -> Triple(n.queue.lastOrNull()?.songId, n.queue.isNotEmpty() && n.queuePosition >= n.queue.lastIndex - 1 && !f, n.repeatMode) }.distinctUntilChanged().collect { (id, last, repeat) ->
                if (id == null || !last || repeat != androidx.media3.common.Player.REPEAT_MODE_OFF || !settings.value.autoplay) return@collect
                // A long queue is still being added; the "last" song isn't really the last.
                if (ct.player.filling.value) return@collect
                if (autoplayedAfter == id) return@collect
                autoplayedAfter = id
                val queued = now.value.queue.mapNotNull { songById.value[it.songId] }
                // After an online song, continue with YouTube's mix for it; after a local one, from the library.
                val lastSong = queued.lastOrNull()
                if (lastSong != null && lastSong.isOnline) {
                    val inQueue = queued.mapTo(HashSet()) { it.id }
                    val online = onlineUi.radio(lastSong).filter { it.id !in inQueue && it.id !in blocked.value }.take(15)
                    if (online.isNotEmpty()) { keepOnline(online); ct.player.append(online) }
                    return@collect
                }
                val more = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    similarSongs(queued.takeLast(10), songs.value, historyFlow.value.taste, 15, kotlin.random.Random.Default, blocked.value + queued.map { it.id }, drift = true)
                }
                ct.player.append(more)
            }
        }
    }
}

/** Days between two instants; used by screens that show "x days ago". */
