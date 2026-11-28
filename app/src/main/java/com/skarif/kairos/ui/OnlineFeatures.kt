package com.skarif.kairos.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.skarif.kairos.AppContainer
import com.skarif.kairos.data.PhoneSaver
import com.skarif.kairos.data.DownloadMode
import com.skarif.kairos.data.db.ONLINE_VOLUME
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.db.isOnline
import com.skarif.kairos.online.Flavor
import com.skarif.kairos.online.OnlineArtist
import com.skarif.kairos.online.OnlineCheck
import com.skarif.kairos.online.OnlineCollection
import com.skarif.kairos.online.OnlineItem
import com.skarif.kairos.online.OnlineKind
import com.skarif.kairos.online.OnlinePage
import com.skarif.kairos.online.onlineId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** How a shelf is drawn on Explore. */
enum class ShelfStyle { PICKS, CARDS, ARTISTS }

/** One row of Explore. */
data class Shelf(val key: String, val title: String, val subtitle: String?, val style: ShelfStyle, val items: List<OnlineItem>)

sealed interface ExploreState {
    data object Loading : ExploreState
    data class Ready(val shelves: List<Shelf>, val refreshing: Boolean) : ExploreState
    data class Failed(val message: String) : ExploreState
}

/** Explore's mood and genre chips: label and the words searched for. */
val EXPLORE_MOODS = listOf(
    "Chill" to "chill", "Romance" to "romantic", "Party" to "party", "Workout" to "workout",
    "Focus" to "focus", "Sad" to "sad", "Bollywood" to "bollywood", "Punjabi" to "punjabi",
    "Lo-fi" to "lofi", "Hip hop" to "hip hop", "Indie" to "indie", "Retro" to "90s",
)

/** A mood page: playlists and songs. */
data class MoodPage(val label: String, val playlists: List<OnlineItem.Collection>, val songs: List<SongEntity>)

/** Online search: what was asked, what came back, and whether more is loading. */
data class OnlineResults(
    val query: String = "",
    val kind: OnlineKind = OnlineKind.SONGS,
    val items: List<OnlineItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    internal val page: OnlinePage? = null,
) {
    val hasMore get() = page?.hasMore == true
}

/** Loads something once for a screen. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

const val REPO = "Arif1221/kairos-online"

/** Kairos Online ships only on GitHub, so the app checks there for a newer release itself. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** [apkUrl] is the APK to install in the app; [url] the release page; [notes] what's new. */
    data class Available(val version: String, val url: String, val apkUrl: String?, val notes: List<String>) : UpdateState
    data object Failed : UpdateState
}

/**
 * Everything the online build adds on top of the offline app's state: Explore, online search,
 * network awareness and the stream cache. Kept out of [MainViewModel] so merges from the
 * offline app stay clean. In the play build [Flavor.online] is null and all of this stays empty.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class OnlineFeatures(
    private val app: Application,
    private val ct: AppContainer,
    private val scope: CoroutineScope,
    /** Records songs seen in results, so they can be saved when favourited or added to a playlist. */
    private val seen: (List<SongEntity>) -> Unit,
    /** Shows a short message (download finished or failed). */
    private val notify: (String) -> Unit = {},
    /** A file was saved to Music/Kairos: rescan so it shows in the Library. */
    private val onSavedToPhone: () -> Unit = {},
) {
    private val source = Flavor.online

    /** True while the phone has a working connection. Always false in the play build. */
    val networkUp: StateFlow<Boolean> = (if (source == null) flowOf(false) else networkFlow())
        .stateIn(scope, SharingStarted.Eagerly, source != null)

    private fun networkFlow() = callbackFlow {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        fun up() = cm.getNetworkCapabilities(cm.activeNetwork)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        trySend(up())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(up()) }
            override fun onLost(network: Network) { trySend(up()) }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun offlineMessage() = "You're offline. ${source?.name ?: "Streaming"} needs a connection."

    /** Turns network and extractor exceptions into clear, actionable user-facing messages. */
    fun describeError(e: Throwable): String {
        if (!networkUp.value) return offlineMessage()
        for (t in generateSequence(e) { it.cause }) {
            val name = t.javaClass.simpleName
            val msg = t.message.orEmpty()
            when {
                t is java.net.UnknownHostException || t is java.net.ConnectException ->
                    return "No internet connection. Check your network and try again."
                t is java.net.SocketTimeoutException ->
                    return "Connection timed out. Try again in a moment."
                name == "ReCaptchaException" || "429" in msg ->
                    return "Too many requests right now. Wait a moment and try again."
                name == "AgeRestrictedContentException" ->
                    return "This is age-restricted on ${source?.name ?: "YouTube"}."
                name == "GeographicRestrictionException" ->
                    return "This isn't available in your region."
                name == "PrivateContentException" ->
                    return "This video is private."
                name == "PaidContentException" ->
                    return "Paid content cannot be streamed."
                name == "ContentNotAvailableException" ->
                    return "This isn't available on ${source?.name ?: "YouTube"} anymore."
                msg.startsWith("Nothing found for ") ->
                    return msg
            }
        }
        return "Couldn't reach ${source?.name}. Try again in a moment."
    }

    private fun failMessage(e: Exception) = describeError(e)

    private fun seenItems(items: List<OnlineItem>) = seen(items.filterIsInstance<OnlineItem.Track>().map { it.song })

    // ---- From the database: works offline --------------------------------------------------

    /** Online songs you've played, most recent first. */
    val recentlyStreamed: StateFlow<List<SongEntity>> =
        combine(ct.db.songs().observeAll(), ct.db.playEvents().observeAll()) { songs, events ->
            val online = songs.filter { it.isOnline }.associateBy { it.id }
            events.sortedByDescending { it.startedAt }.map { it.songId }.distinct().mapNotNull { online[it] }.take(40)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val cacheChanged = MutableStateFlow(0)

    /** [recentlyStreamed] songs fully in the stream cache: they play with no connection. */
    val availableOffline: StateFlow<List<SongEntity>> =
        combine(recentlyStreamed, networkUp, cacheChanged) { list, _, _ -> list }
            .mapLatest { list -> withContext(Dispatchers.IO) { list.filter { source?.isCached(app, it) == true } } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    fun refreshCacheSize() {
        val s = source ?: return
        scope.launch(Dispatchers.IO) { _cacheBytes.value = s.cacheBytes(app) }
    }

    fun clearCache() {
        val s = source ?: return
        scope.launch(Dispatchers.IO) {
            s.clearCache(app)
            _cacheBytes.value = s.cacheBytes(app)
            cacheChanged.value++
        }
    }

    // ---- Downloads ----------------------------------------------------------------------------

    /** Songs waiting or downloading, by id, with progress 0..1 (0 while waiting). */
    private val _downloading = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloading: StateFlow<Map<Long, Float>> = _downloading.asStateFlow()

    /** Video ids of finished downloads. */
    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    private val _downloadedVideo = MutableStateFlow<Set<String>>(emptySet())
    val downloadedVideoIds: StateFlow<Set<Long>> = _downloadedVideo.map { ids -> ids.mapTo(HashSet(), ::onlineId) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())
    val downloadedIds: StateFlow<Set<Long>> = _downloaded.map { ids -> ids.mapTo(HashSet(), ::onlineId) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Downloaded songs, newest first. They play with no connection and are never evicted. */
    val downloads: StateFlow<List<SongEntity>> = combine(ct.db.songs().observeAll(), _downloaded) { songs, ids ->
        songs.filter { it.isOnline && it.fileName in ids }.sortedByDescending { it.dateAddedSec }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _downloadBytes = MutableStateFlow(0L)
    val downloadBytes: StateFlow<Long> = _downloadBytes.asStateFlow()

    /** Video ids saved to the phone as files (kept across launches). */
    private val phonePrefs = app.getSharedPreferences("saved_to_phone", android.content.Context.MODE_PRIVATE)
    private val _savedToPhone = MutableStateFlow(phonePrefs.getStringSet("ids", emptySet()).orEmpty())
    val savedToPhoneIds: StateFlow<Set<Long>> = _savedToPhone.map { ids -> ids.mapTo(HashSet(), ::onlineId) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private fun markSavedToPhone(videoId: String) {
        _savedToPhone.update { it + videoId }
        phonePrefs.edit().putStringSet("ids", _savedToPhone.value).apply()
    }

    /** Fires when Android 9 needs the storage permission before saving files; MainActivity asks for it. */
    private val _askStorage = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val askStorage: kotlinx.coroutines.flow.SharedFlow<Unit> = _askStorage

    private class Task(val song: SongEntity, val mode: DownloadMode)
    private val queue = Channel<Task>(Channel.UNLIMITED)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    private fun refreshDownloads() {
        val s = source ?: return
        scope.launch(Dispatchers.IO) {
            _downloaded.value = s.downloadedIds(app)
            _downloadedVideo.value = s.downloadedVideoIds(app)
            _downloadBytes.value = s.downloadBytes(app)
        }
    }

    /** Uses the remembered choice for bulk actions; otherwise bulk Download defaults to audio-only. */
    fun download(songs: List<SongEntity>) {
        val settings = ct.settings.settings.value
        val mode = if (settings.rememberDownloadChoice) settings.downloadMode else DownloadMode.AUDIO_ONLY
        enqueue(songs, mode)
    }

    fun download(songs: List<SongEntity>, mode: DownloadMode) = enqueue(songs, mode)

    /** Saves [songs] as M4A files in Music/Kairos, whatever the remembered Download choice is. */
    fun saveToPhone(songs: List<SongEntity>) = enqueue(songs, DownloadMode.SAVE_TO_PHONE)

    private fun enqueue(songs: List<SongEntity>, mode: DownloadMode) {
        if (source == null) return
        val toPhone = mode == DownloadMode.SAVE_TO_PHONE
        val todo = songs.filter { song ->
            if (!song.isOnline || song.id in _downloading.value) return@filter false
            when (mode) {
                DownloadMode.SAVE_TO_PHONE -> song.fileName !in _savedToPhone.value
                DownloadMode.AUDIO_ONLY -> song.fileName !in _downloaded.value
                DownloadMode.AUDIO_VIDEO -> song.fileName !in _downloaded.value || song.fileName !in _downloadedVideo.value
            }
        }.distinctBy { it.id }
        if (todo.isEmpty()) {
            if (songs.isNotEmpty()) notify(when (mode) {
                DownloadMode.SAVE_TO_PHONE -> "Already saved to Music/${PhoneSaver.FOLDER}"
                DownloadMode.AUDIO_ONLY -> "Already downloaded"
                DownloadMode.AUDIO_VIDEO -> "Audio and video already downloaded"
            })
            return
        }
        if (!networkUp.value) { notify("Downloading needs a connection"); return }
        if (toPhone && PhoneSaver.needsPermission &&
            app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            _askStorage.tryEmit(Unit)
            notify("Allow storage access, then try again")
            return
        }
        _downloading.update { m -> m + todo.associate { it.id to 0f } }
        scope.launch {
            // Downloads need their rows, so they show up offline under Downloads.
            if (!toPhone) ct.db.songs().upsertAll(todo)
            todo.forEach { queue.send(Task(it, mode)) }
        }
        val what = if (toPhone) "Saving" else "Downloading"
        notify(if (todo.size == 1) "$what ${todo[0].title}" else "$what ${todo.size} songs")
    }

    /** Stops a download (or takes it out of the queue) and deletes what was saved of it. */
    fun cancelDownload(song: SongEntity) {
        _downloading.update { it - song.id }
        jobs.remove(song.id)?.cancel()
        scope.launch(Dispatchers.IO) {
            if (song.fileName in _downloaded.value) source?.removeVideoDownload(app, song.fileName)
            else source?.removeDownload(app, song.fileName)
            refreshDownloads()
        }
    }

    fun removeDownload(song: SongEntity) {
        val s = source ?: return
        scope.launch(Dispatchers.IO) {
            s.removeDownload(app, song.fileName)
            refreshDownloads()
            cacheChanged.value++
        }
    }

    fun removeAllDownloads() {
        val s = source ?: return
        _downloading.value.keys.forEach { jobs.remove(it)?.cancel() }
        _downloading.value = emptyMap()
        scope.launch(Dispatchers.IO) {
            s.downloadedIds(app).forEach { s.removeDownload(app, it) }
            refreshDownloads()
            cacheChanged.value++
        }
    }

    private suspend fun runDownloads() {
        val s = source ?: return
        for (task in queue) {
            val song = task.song
            if (song.id !in _downloading.value) continue // cancelled while waiting
            var failed: Exception? = null
            var partialFailure: Exception? = null
            val job = scope.launch {
                var shown = 0f
                // Every 2%: enough to look alive, few enough not to redraw lists constantly.
                val report = { p: Float ->
                    if (p - shown >= 0.02f || p >= 1f) {
                        shown = p
                        _downloading.update { if (song.id in it) it + (song.id to p) else it }
                    }
                }
                try {
                    when (task.mode) {
                        DownloadMode.SAVE_TO_PHONE -> {
                            val tmp = File(app.cacheDir, "save/${song.fileName}.m4a").apply { parentFile?.mkdirs() }
                            try {
                                s.fetchM4a(song, tmp) { p -> report(p * 0.95f) }
                                PhoneSaver.save(app, song, tmp, ct.artwork.load(song, 720))
                                markSavedToPhone(song.fileName)
                            } finally { tmp.delete() }
                        }
                        DownloadMode.AUDIO_ONLY, DownloadMode.AUDIO_VIDEO -> {
                            if (!s.isDownloaded(app, song)) s.download(app, song, ct.settings.settings.value.streamQuality) { p ->
                                report(if (task.mode == DownloadMode.AUDIO_VIDEO) p * 0.45f else p)
                            }
                            if (task.mode == DownloadMode.AUDIO_VIDEO && song.fileName !in _downloadedVideo.value) {
                                try {
                                    s.downloadVideo(app, song, ct.settings.settings.value.streamQuality) { p -> report(0.45f + p * 0.55f) }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { partialFailure = e }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = e
                }
            }
            jobs[song.id] = job
            job.join()
            jobs.remove(song.id)
            val wasCancelled = song.id !in _downloading.value
            _downloading.update { it - song.id }
            refreshDownloads()
            cacheChanged.value++
            when {
                wasCancelled -> {}
                failed != null -> notify(
                    if (!networkUp.value) "Download paused: you're offline"
                    else "Couldn't download ${song.title}: ${describeError(failed).removeSuffix(".")}",
                )
                task.mode == DownloadMode.SAVE_TO_PHONE && _downloading.value.isEmpty() -> notify("Saved to Music/${PhoneSaver.FOLDER}")
                partialFailure != null && _downloading.value.isEmpty() -> notify(
                    if (partialFailure?.message?.startsWith("No matching video") == true) "Audio downloaded; no matching video for ${song.title}"
                    else "Audio downloaded; video download failed for ${song.title}"
                )
                _downloading.value.isEmpty() -> notify(if (task.mode == DownloadMode.AUDIO_VIDEO) "Downloaded audio and video for ${song.title}" else "Downloaded ${song.title}")
            }
            if (task.mode == DownloadMode.SAVE_TO_PHONE && failed == null && !wasCancelled && _downloading.value.isEmpty()) onSavedToPhone()
        }
    }

    // ---- Liked artists, albums and playlists -----------------------------------------------------

    /** Saved as JSON on the phone, newest first, so the Library tiles work offline. */
    private val likesPrefs = app.getSharedPreferences("liked_online", android.content.Context.MODE_PRIVATE)

    private fun loadLikes(key: String): List<OnlineItem> = runCatching {
        val arr = JSONArray(likesPrefs.getString(key, "[]"))
        (0 until arr.length()).mapNotNull { itemFromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private val _likedArtists = MutableStateFlow(loadLikes("artists").filterIsInstance<OnlineItem.Artist>())
    val likedArtists: StateFlow<List<OnlineItem.Artist>> = _likedArtists.asStateFlow()
    private val _likedCollections = MutableStateFlow(loadLikes("collections").filterIsInstance<OnlineItem.Collection>())
    val likedCollections: StateFlow<List<OnlineItem.Collection>> = _likedCollections.asStateFlow()

    private fun saveLikes(key: String, items: List<OnlineItem>) =
        likesPrefs.edit().putString(key, JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()).apply()

    fun isLiked(id: String) = _likedArtists.value.any { it.id == id } || _likedCollections.value.any { it.id == id }

    /** Likes or unlikes an artist (by channel id; artists found by name are matched by name too). */
    fun toggleLike(artist: OnlineItem.Artist) {
        val had = _likedArtists.value.any { it.id == artist.id || it.name.equals(artist.name, true) }
        _likedArtists.value = if (had) _likedArtists.value.filterNot { it.id == artist.id || it.name.equals(artist.name, true) }
        else listOf(artist) + _likedArtists.value
        saveLikes("artists", _likedArtists.value)
        notify(if (had) "Removed ${artist.name} from your artists" else "Added ${artist.name} to your artists")
    }

    fun toggleLike(collection: OnlineItem.Collection) {
        val had = _likedCollections.value.any { it.id == collection.id }
        _likedCollections.value = if (had) _likedCollections.value.filterNot { it.id == collection.id }
        else listOf(collection) + _likedCollections.value
        saveLikes("collections", _likedCollections.value)
        notify(if (had) "Removed ${collection.title} from your albums" else "Added ${collection.title} to your albums")
    }

    // ---- Spotify import -------------------------------------------------------------------------

    sealed interface ImportState {
        data object Idle : ImportState
        data object Reading : ImportState
        /** Reading a long playlist page by page. */
        data class Reading2(val read: Int, val total: Int) : ImportState
        data class Matching(val name: String, val done: Int, val total: Int, val found: Int) : ImportState
        data class Done(
            val name: String, val playlistId: Long, val found: Int, val total: Int,
            val missing: List<com.skarif.kairos.online.SpotifyImport.Track>, val maybeCut: Boolean,
        ) : ImportState
        data class Failed(val message: String) : ImportState
    }

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: StateFlow<ImportState> = _import.asStateFlow()
    private var importJob: Job? = null

    fun resetImport() { importJob?.cancel(); _import.value = ImportState.Idle }

    /**
     * A public Spotify playlist or album → a Kairos playlist: each song is looked for on this
     * phone first (same title and artist), then on YouTube Music. Four at a time.
     */
    fun importSpotify(link: String) {
        val s = source ?: return
        val target = com.skarif.kairos.online.SpotifyImport.parseLink(link)
        if (target == null) { _import.value = ImportState.Failed("That isn't a Spotify playlist or album link."); return }
        if (!networkUp.value) { _import.value = ImportState.Failed(offlineMessage()); return }
        importJob?.cancel()
        importJob = scope.launch {
            _import.value = ImportState.Reading
            try {
                val list = withContext(Dispatchers.IO) {
                    val conn = URL(com.skarif.kairos.online.SpotifyImport.embedUrl(target.first, target.second)).openConnection() as HttpURLConnection
                    val html = try {
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 20_000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
                        if (conn.responseCode == 404) error("Spotify can't find it. Is the playlist public?")
                        conn.inputStream.use { it.readBytes().decodeToString() }
                    } finally {
                        conn.disconnect()
                    }
                    val first = com.skarif.kairos.online.SpotifyImport.parse(html)
                    // Playlists past the embed's 100: read the rest with the page's own anonymous token.
                    if (target.first == "playlist") {
                        com.skarif.kairos.online.SpotifyImport.fetchAll(target.second, html, first) { n, total ->
                            _import.value = ImportState.Reading2(n, total)
                        }
                    } else first
                }
                if (list.tracks.isEmpty()) error("That playlist is empty, or private.")
                // Songs on this phone, by matching key, for the first pass.
                val local = ct.db.songs().observeAll().first().filterNot { it.isOnline }
                    .groupBy { com.skarif.kairos.online.SpotifyImport.key(it.title) }
                val found = arrayOfNulls<SongEntity>(list.tracks.size)
                var done = 0
                var hits = 0
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                coroutineScope {
                    list.tracks.forEachIndexed { i, t ->
                        launch {
                            gate.acquire()
                            try {
                                val artist = com.skarif.kairos.online.SpotifyImport.firstArtist(t.artist).lowercase()
                                val mine = local[com.skarif.kairos.online.SpotifyImport.key(t.title)]
                                    ?.firstOrNull { artist.isEmpty() || artist in it.artist.lowercase() }
                                found[i] = mine ?: runCatching {
                                    // "(feat. …)" and "[From …]" make the search worse, not better.
                                    val plain = t.title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim().ifEmpty { t.title }
                                    withTimeoutOrNull(20_000) { s.findSong(plain, com.skarif.kairos.online.SpotifyImport.firstArtist(t.artist)) }
                                }.getOrNull()
                            } finally {
                                gate.release()
                                done++
                                if (found[i] != null) hits++
                                _import.value = ImportState.Matching(list.name, done, list.tracks.size, hits)
                            }
                        }
                    }
                }
                val songs = found.filterNotNull().distinctBy { it.id }
                ct.db.songs().upsertAll(songs.filter { it.isOnline })
                seen(songs)
                val id = ct.db.playlists().create(list.name, songs.map { it.id })
                _import.value = ImportState.Done(
                    list.name, id, songs.size, list.tracks.size,
                    list.tracks.filterIndexed { i, _ -> found[i] == null }, list.maybeCut,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _import.value = ImportState.Failed(
                    e.message?.takeIf { it.isNotBlank() && e is IllegalStateException } ?: failMessage(e),
                )
            }
        }
    }

    // ---- Search ----------------------------------------------------------------------------

    private val query = MutableStateFlow("")
    private val _kind = MutableStateFlow(OnlineKind.SONGS)
    val kind: StateFlow<OnlineKind> = _kind.asStateFlow()

    /** Search looks online (true) or in your library (false). */
    private val _searchOnline = MutableStateFlow(source != null)
    val searchOnline: StateFlow<Boolean> = _searchOnline.asStateFlow()
    fun setSearchOnline(on: Boolean) { _searchOnline.value = on && source != null }

    fun setQuery(q: String) { query.value = q }
    fun setKind(k: OnlineKind) { _kind.value = k }

    /** Completions for what's being typed. */
    val suggestions: StateFlow<List<String>> = query.debounce { if (it.isBlank()) 0L else 180L }
        .distinctUntilChanged()
        .mapLatest { q -> if (q.isBlank() || source == null || !networkUp.value || !_searchOnline.value) emptyList() else source.suggestions(q) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _results = MutableStateFlow(OnlineResults())
    val results: StateFlow<OnlineResults> = _results.asStateFlow()
    private var moreJob: Job? = null

    private fun runSearch(q: String, k: OnlineKind) {
        moreJob?.cancel()
        val s = source ?: return
        if (q.isBlank()) { _results.value = OnlineResults(kind = k); return }
        if (!networkUp.value) { _results.value = OnlineResults(q, k, error = offlineMessage()); return }
        moreJob = scope.launch {
            _results.value = OnlineResults(q, k, loading = true)
            _results.value = try {
                val page = s.search(q, k)
                seenItems(page.items)
                OnlineResults(q, k, page.items, page = page)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                OnlineResults(q, k, error = failMessage(e))
            }
        }
    }

    /** Online songs for [q], for pickers (new playlist). Empty when offline or on failure; never throws. */
    suspend fun searchSongs(q: String): List<SongEntity> {
        val s = source ?: return emptyList()
        if (q.isBlank() || !networkUp.value) return emptyList()
        return try {
            s.search(q.trim(), OnlineKind.SONGS).items.filterIsInstance<OnlineItem.Track>().map { it.song }.also(seen)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    /** Next page of the current results; call when the list nears its end. */
    fun loadMore() {
        val s = source ?: return
        val r = _results.value
        if (r.loading || !r.hasMore || moreJob?.isActive == true) return
        moreJob = scope.launch {
            _results.value = r.copy(loading = true)
            _results.value = try {
                val page = s.search(r.query, r.kind, r.page)
                seenItems(page.items)
                val known = r.items.mapTo(HashSet()) { it.key }
                r.copy(items = r.items + page.items.filter { it.key !in known }, loading = false, page = page)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                r.copy(loading = false) // keep what's shown; scrolling again retries
            }
        }
    }

    fun retrySearch() = runSearch(query.value, _kind.value)

    // ---- Pages: album, playlist, artist ----------------------------------------------------

    suspend fun collection(id: String): Loadable<OnlineCollection> {
        val s = source ?: return Loadable.Failed("Not available")
        return try { Loadable.Ready(s.collection(id).also { seen(it.songs) }) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Loadable.Failed(failMessage(e)) }
    }

    /** [id] "_" means "find them by [name]" (from the player or a song's menu, where only the name is known). */
    suspend fun artist(id: String, name: String): Loadable<OnlineArtist> {
        val s = source ?: return Loadable.Failed("Not available")
        return try {
            val channel = if (id.isNotBlank() && id != "_" && !id.startsWith("_:")) id else
                (s.search(name, OnlineKind.ARTISTS).items.firstOrNull() as? OnlineItem.Artist)?.id ?: ""
            Loadable.Ready(s.artist(channel, name).also { seen(it.songs) })
        }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Loadable.Failed(failMessage(e)) }
    }

    /** Songs for radio and autoplay after [song]; empty when offline or on failure. */
    suspend fun radio(song: SongEntity): List<SongEntity> {
        val s = source ?: return emptyList()
        if (!networkUp.value) return emptyList()
        return try { s.radio(song).also(seen) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    private val _checks = MutableStateFlow<Loadable<List<OnlineCheck>>?>(null)
    val checks: StateFlow<Loadable<List<OnlineCheck>>?> = _checks.asStateFlow()

    /** Settings > Online check. */
    fun runChecks() {
        val s = source ?: return
        if (_checks.value == Loadable.Loading) return
        scope.launch {
            _checks.value = Loadable.Loading
            _checks.value = if (!networkUp.value) Loadable.Failed(offlineMessage())
            else try { Loadable.Ready(s.diagnose()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Loadable.Failed(failMessage(e)) }
        }
    }

    // ---- Explore ---------------------------------------------------------------------------

    private val _explore = MutableStateFlow<ExploreState>(ExploreState.Loading)
    val explore: StateFlow<ExploreState> = _explore.asStateFlow()
    private var exploreJob: Job? = null
    private val exploreFile get() = File(app.filesDir, "explore.json")

    /** Shows the saved Explore at once, and rebuilds it when it's old, missing, or [force]d. */
    fun loadExplore(force: Boolean = false) {
        val s = source ?: return
        if (exploreJob?.isActive == true) return
        exploreJob = scope.launch {
            val cached = if (_explore.value is ExploreState.Ready) null else withContext(Dispatchers.IO) { readExplore() }
            if (cached != null) _explore.value = ExploreState.Ready(cached.second, refreshing = false)
            val current = (_explore.value as? ExploreState.Ready)?.shelves
            val fresh = cached != null && System.currentTimeMillis() - cached.first < EXPLORE_TTL_MS
            if (!force && (fresh || current != null && cached == null)) return@launch
            if (!networkUp.value) {
                if (current == null) _explore.value = ExploreState.Failed(offlineMessage())
                return@launch
            }
            _explore.value = if (current != null) ExploreState.Ready(current, refreshing = true) else ExploreState.Loading
            val shelves = buildExplore(s.name)
            when {
                shelves.isNotEmpty() -> {
                    _explore.value = ExploreState.Ready(shelves, refreshing = false)
                    withContext(Dispatchers.IO) { writeExplore(shelves) }
                }
                current != null -> _explore.value = ExploreState.Ready(current, refreshing = false)
                else -> _explore.value = ExploreState.Failed("Couldn't reach ${s.name}. Try again in a moment.")
            }
        }
    }

    /** Shelves built from what you listen to, fetched together; a shelf that fails is just left out. */
    private suspend fun buildExplore(sourceName: String): List<Shelf> = coroutineScope {
        val s = source ?: return@coroutineScope emptyList()
        val songs = ct.db.songs().observeAll().first()
        val byId = songs.associateBy { it.id }
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600_000
        val events = ct.db.playEvents().observeAll().first().filter { it.listenedMs >= 30_000 }
        val recentEvents = events.filter { it.startedAt >= monthAgo }.ifEmpty { events }
        val topSongs = recentEvents.groupingBy { it.songId }.eachCount().entries
            .sortedByDescending { it.value }.mapNotNull { byId[it.key] }
        val topArtists = recentEvents.mapNotNull { byId[it.songId]?.artist?.substringBefore(",")?.substringBefore(" & ")?.trim() }
            .filter { it.isNotBlank() && !it.startsWith("Unknown") }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(4)
        val now = LocalDate.now()
        val month = now.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

        fun <T> safe(block: suspend () -> T?) = async {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        suspend fun tracks(q: String, n: Int) = s.search(q, OnlineKind.SONGS).items.filterIsInstance<OnlineItem.Track>().take(n)

        val picks = safe {
            // Mixes of what you play most (local songs are matched online first), woven together.
            val seeds = (topSongs.take(3) + recentlyStreamed.value.take(1)).distinctBy { it.title.lowercase() }.take(3)
            val mixes = seeds.map { seed -> async { runCatching { s.radio(seed) }.getOrDefault(emptyList()) } }.awaitAll()
            val woven = mixes.flatMap { it.withIndex() }.sortedBy { it.index }.map { it.value }
                .distinctBy { it.fileName }.filter { m -> seeds.none { it.title.equals(m.title, true) } }
            val list = woven.ifEmpty { tracks("top hits ${now.year}", 20).map { it.song } }.take(20)
            list.takeIf { it.isNotEmpty() }?.let {
                Shelf("picks", "Quick picks", if (seeds.isEmpty()) "Popular right now" else "Based on what you play", ShelfStyle.PICKS, it.map(OnlineItem::Track))
            }
        }
        val forArtists = topArtists.take(2).map { a ->
            safe { tracks(a, 12).takeIf { it.size >= 3 }?.let { Shelf("more:$a", "More from $a", null, ShelfStyle.CARDS, it) } }
        }
        val artists = safe {
            // Artists you liked first, then the ones you play most.
            val liked = _likedArtists.value
            val names = topArtists.filter { n -> liked.none { it.name.equals(n, true) } }
                .ifEmpty { if (liked.isEmpty()) listOf("Arijit Singh", "Taylor Swift", "Diljit Dosanjh", "The Weeknd") else emptyList() }
            val found = names.map { n -> async { runCatching { s.search(n, OnlineKind.ARTISTS).items.firstOrNull() }.getOrNull() } }.awaitAll()
            (liked + found.filterNotNull()).distinctBy { it.key }.takeIf { it.size >= 2 }
                ?.let { Shelf("artists", if (topArtists.isEmpty() && liked.isEmpty()) "Artists to try" else "Your artists", null, ShelfStyle.ARTISTS, it) }
        }
        val playlists = safe {
            val q = topArtists.firstOrNull()?.let { "$it hits" } ?: "top hits ${now.year}"
            s.search(q, OnlineKind.PLAYLISTS).items.take(12).takeIf { it.isNotEmpty() }
                ?.let { Shelf("lists", "Playlists for you", null, ShelfStyle.CARDS, it) }
        }
        val fresh = safe {
            tracks("new songs $month ${now.year}", 15).takeIf { it.size >= 3 }
                ?.let { Shelf("new", "New releases", "$month ${now.year}", ShelfStyle.CARDS, it) }
        }
        val charts = safe {
            s.search("top 50 ${now.year}", OnlineKind.PLAYLISTS).items.take(12).takeIf { it.isNotEmpty() }
                ?.let { Shelf("charts", "Charts", null, ShelfStyle.CARDS, it) }
        }
        val shelves = (listOf(picks) + forArtists.take(1) + listOf(artists, playlists) + forArtists.drop(1) + listOf(fresh, charts))
            .awaitAll().filterNotNull()
        shelves.forEach { seenItems(it.items) }
        shelves
    }

    private val _mood = MutableStateFlow<Loadable<MoodPage>?>(null)
    val mood: StateFlow<Loadable<MoodPage>?> = _mood.asStateFlow()
    private val _moodIndex = MutableStateFlow(-1)
    val moodIndex: StateFlow<Int> = _moodIndex.asStateFlow()
    private var moodJob: Job? = null

    /** Tapping a chip opens its page; tapping it again goes back to the shelves. */
    fun selectMood(i: Int) {
        moodJob?.cancel()
        if (_moodIndex.value == i || i !in EXPLORE_MOODS.indices) { _moodIndex.value = -1; _mood.value = null; return }
        _moodIndex.value = i
        val s = source ?: return
        val (label, words) = EXPLORE_MOODS[i]
        moodJob = scope.launch {
            _mood.value = Loadable.Loading
            _mood.value = if (!networkUp.value) Loadable.Failed(offlineMessage()) else try {
                coroutineScope {
                    val lists = async { runCatching { s.search("$words playlist", OnlineKind.PLAYLISTS).items }.getOrDefault(emptyList()) }
                    val songs = async { runCatching { s.search("$words songs", OnlineKind.SONGS).items }.getOrDefault(emptyList()) }
                    val page = MoodPage(
                        label,
                        lists.await().filterIsInstance<OnlineItem.Collection>().take(12),
                        songs.await().filterIsInstance<OnlineItem.Track>().map { it.song },
                    )
                    seen(page.songs)
                    if (page.playlists.isEmpty() && page.songs.isEmpty()) Loadable.Failed("Couldn't load $label.") else Loadable.Ready(page)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Loadable.Failed(failMessage(e)) }
        }
    }

    fun retryMood() { val i = _moodIndex.value; if (i >= 0) { _moodIndex.value = -1; selectMood(i) } }

    // Explore is saved as JSON so it shows instantly on the next launch, even offline.
    private fun writeExplore(shelves: List<Shelf>) = runCatching {
        val arr = JSONArray()
        shelves.forEach { sh ->
            arr.put(JSONObject().put("key", sh.key).put("title", sh.title).put("sub", sh.subtitle).put("style", sh.style.name)
                .put("items", JSONArray().apply { sh.items.forEach { put(it.toJson()) } }))
        }
        val tmp = File(exploreFile.path + ".tmp")
        tmp.writeText(JSONObject().put("at", System.currentTimeMillis()).put("v", 1).put("shelves", arr).toString())
        tmp.renameTo(exploreFile)
    }

    private fun readExplore(): Pair<Long, List<Shelf>>? = runCatching {
        if (!exploreFile.exists()) return null
        val o = JSONObject(exploreFile.readText())
        if (o.optInt("v") != 1) return null
        val arr = o.getJSONArray("shelves")
        o.getLong("at") to (0 until arr.length()).map { i ->
            val sh = arr.getJSONObject(i)
            val items = sh.getJSONArray("items")
            Shelf(
                sh.getString("key"), sh.getString("title"), sh.optString("sub").takeIf { it.isNotEmpty() && it != "null" },
                ShelfStyle.valueOf(sh.getString("style")),
                (0 until items.length()).mapNotNull { itemFromJson(items.getJSONObject(it)) },
            )
        }.filter { it.items.isNotEmpty() }.also { list -> list.forEach { seenItems(it.items) } }
    }.getOrNull()?.takeIf { it.second.isNotEmpty() }

    private fun OnlineItem.toJson(): JSONObject = when (this) {
        is OnlineItem.Track -> JSONObject().put("t", "s").put("id", song.fileName).put("title", song.title).put("artist", song.artist)
            .put("album", song.album).put("ms", song.durationMs).put("art", song.artUrl)
        is OnlineItem.Artist -> JSONObject().put("t", "a").put("id", id).put("name", name).put("art", art).put("detail", detail)
        is OnlineItem.Collection -> JSONObject().put("t", "c").put("id", id).put("title", title).put("owner", owner).put("art", art)
            .put("album", isAlbum).put("n", count)
    }

    private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }

    private fun itemFromJson(o: JSONObject): OnlineItem? = when (o.optString("t")) {
        "s" -> {
            val vid = o.getString("id")
            OnlineItem.Track(
                // Same id as the source gives it, so a song saved from Explore is the same row as from search.
                SongEntity(
                    id = onlineId(vid), title = o.getString("title"), artist = o.getString("artist"), album = o.optString("album"),
                    albumId = 0, durationMs = o.optLong("ms"), track = 0, year = 0, folder = "", fileName = vid,
                    volume = ONLINE_VOLUME, mimeType = "audio/opus", sizeBytes = 0, dateAddedSec = System.currentTimeMillis() / 1000,
                    bitrate = 0, artUrl = o.str("art"),
                ),
            )
        }
        "a" -> OnlineItem.Artist(o.getString("id"), o.getString("name"), o.str("art"), o.str("detail"))
        "c" -> OnlineItem.Collection(o.getString("id"), o.getString("title"), o.str("owner"), o.str("art"), o.optBoolean("album"), o.optInt("n"))
        else -> null
    }

    // ---- Updates ---------------------------------------------------------------------------

    /** This build's version without the "-online" suffix, e.g. "0.9.1.1". */
    val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty().removeSuffix("-online")
    }.getOrDefault("")

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** Looks for the newest `v<version>-online` release on GitHub. */
    fun checkForUpdate() {
        if (source == null || _update.value == UpdateState.Checking) return
        scope.launch(Dispatchers.IO) {
            _update.value = UpdateState.Checking
            _update.value = try {
                val conn = URL("https://api.github.com/repos/$REPO/releases?per_page=20").openConnection() as HttpURLConnection
                val body = try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.inputStream.use { it.readBytes().decodeToString() }
                } finally {
                    conn.disconnect()
                }
                val arr = JSONArray(body)
                val latest = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                    .filter { it.optString("tag_name").let { t -> t.startsWith("v") && t.endsWith("-online") } }
                    .maxWithOrNull { a, b -> compareVersions(a.optString("tag_name").version(), b.optString("tag_name").version()) }
                val v = latest?.optString("tag_name")?.version()
                if (latest == null || v == null || compareVersions(v, appVersion) <= 0) UpdateState.UpToDate else {
                    val assets = latest.optJSONArray("assets") ?: JSONArray()
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
                    UpdateState.Available(v, latest.optString("html_url"), apk, changelogFor(v))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed
            }
        }
    }

    /** "What's new" for [version]: its section of CHANGELOG.md on GitHub, as bullet lines. */
    private fun changelogFor(version: String): List<String> = runCatching {
        val text = URL("https://raw.githubusercontent.com/$REPO/main/CHANGELOG.md").readText()
        text.substringAfter("## Online $version\n", "").substringBefore("\n## ")
            .lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }.take(8)
    }.getOrDefault(emptyList())

    /** Downloads the update and installs it over this version (see [com.skarif.kairos.online.SelfUpdate]). */
    fun installUpdate() {
        val u = _update.value as? UpdateState.Available ?: return
        val apk = u.apkUrl ?: return
        scope.launch { com.skarif.kairos.online.SelfUpdate.downloadAndInstall(app, apk) }
    }

    private val updatePrefs = app.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE)
    private val _skipped = MutableStateFlow(updatePrefs.getString("skipped", null))
    /** The version the user said "Later" to; its popup isn't shown again (the banner stays). */
    val skippedUpdate: StateFlow<String?> = _skipped.asStateFlow()
    /** Shows the update popup again (from the Home banner or Settings). */
    fun showUpdate() {
        _skipped.value = null
        updatePrefs.edit().remove("skipped").apply()
    }

    fun skipUpdate(version: String) {
        _skipped.value = version
        updatePrefs.edit().putString("skipped", version).apply()
    }

    private fun String.version() = removePrefix("v").removeSuffix("-online")

    /** Compares dotted versions number by number: 0.7.10 > 0.7.9, 0.7.6.1 > 0.7.6. */
    private fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    init {
        if (source != null) {
            refreshDownloads()
            scope.launch {
                ct.settings.settings.map { it.streamCacheMb }.distinctUntilChanged().collect { mb ->
                    withContext(Dispatchers.IO) { source.setCacheLimit(app, mb * 1_048_576L) }
                    refreshCacheSize()
                    cacheChanged.value++
                }
            }
            scope.launch { runDownloads() }
            // One quiet update check per launch, once there is a connection.
            scope.launch {
                networkUp.filter { it }.first()
                checkForUpdate()
            }
            scope.launch {
                // Only while Search looks online: typing in "Your library" costs no data.
                combine(query.debounce { if (it.isBlank()) 0L else 450L }.distinctUntilChanged(), _kind, _searchOnline) { q, k, on -> Triple(q, k, on) }
                    .collect { (q, k, on) -> if (on) runSearch(q, k) else if (q.isBlank()) runSearch("", k) }
            }
            // Coming back online retries whatever failed.
            scope.launch {
                networkUp.drop(1).filter { it }.collect {
                    if (_explore.value is ExploreState.Failed) loadExplore(force = true)
                    if (_results.value.error != null) retrySearch()
                    if (_mood.value is Loadable.Failed) retryMood()
                }
            }
        }
    }

    private companion object {
        const val EXPLORE_TTL_MS = 6 * 3600_000L
    }
}
