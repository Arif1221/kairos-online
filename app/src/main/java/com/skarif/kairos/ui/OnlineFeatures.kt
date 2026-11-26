package com.skarif.kairos.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.skarif.kairos.AppContainer
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    data class Available(val version: String, val url: String) : UpdateState
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
    private fun failMessage(e: Exception) =
        if (!networkUp.value) offlineMessage() else "Couldn't reach ${source?.name}. Try again in a moment."

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

    suspend fun artist(id: String, name: String): Loadable<OnlineArtist> {
        val s = source ?: return Loadable.Failed("Not available")
        return try { Loadable.Ready(s.artist(id, name).also { seen(it.songs) }) }
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
            val names = topArtists.ifEmpty { listOf("Arijit Singh", "Taylor Swift", "Diljit Dosanjh", "The Weeknd") }
            val found = names.map { n -> async { runCatching { s.search(n, OnlineKind.ARTISTS).items.firstOrNull() }.getOrNull() } }.awaitAll()
            found.filterNotNull().distinctBy { it.key }.takeIf { it.size >= 2 }
                ?.let { Shelf("artists", if (topArtists.isEmpty()) "Artists to try" else "Your artists", null, ShelfStyle.ARTISTS, it) }
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
                    .map { it.optString("tag_name") to it.optString("html_url") }
                    .filter { (tag, _) -> tag.startsWith("v") && tag.endsWith("-online") }
                    .maxWithOrNull { a, b -> compareVersions(a.first.version(), b.first.version()) }
                when {
                    latest == null -> UpdateState.UpToDate
                    compareVersions(latest.first.version(), appVersion) > 0 -> UpdateState.Available(latest.first.version(), latest.second)
                    else -> UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed
            }
        }
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
