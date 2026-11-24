package com.skarif.kairos.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.skarif.kairos.AppContainer
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.db.isOnline
import com.skarif.kairos.online.Flavor
import com.skarif.kairos.online.OnlineKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import org.json.JSONArray

/** One row of the Discover page. */
data class Shelf(val title: String, val songs: List<SongEntity>)

sealed interface DiscoverState {
    data object Loading : DiscoverState
    data class Ready(val shelves: List<Shelf>) : DiscoverState
    data class Failed(val message: String) : DiscoverState
}

/** Discover's mood chips: a label and the search behind it. */
val DISCOVER_MOODS = listOf(
    "Trending" to "trending songs", "Bollywood" to "bollywood hits", "Lo-fi" to "lofi chill",
    "Punjabi" to "punjabi hits", "Hip hop" to "hip hop hits", "Romantic" to "romantic songs",
    "Workout" to "workout songs", "Indie" to "indie hits", "Sad" to "sad songs", "Party" to "party songs",
)

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
 * Everything the online build adds on top of the offline app's state: Discover, network
 * awareness and the stream cache. Kept out of [MainViewModel] so merges from main stay clean.
 * In the play build [Flavor.online] is null and all of this stays empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /** Online songs you've played, most recent first. Comes from the database, so it works offline. */
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

    private val _discover = MutableStateFlow<DiscoverState>(DiscoverState.Loading)
    val discover: StateFlow<DiscoverState> = _discover.asStateFlow()

    /** Selected mood chip (index into [DISCOVER_MOODS]) and its songs; -1 shows the shelves. */
    private val _mood = MutableStateFlow(-1)
    val mood: StateFlow<Int> = _mood.asStateFlow()
    private val _moodSongs = MutableStateFlow<OnlineSearch>(OnlineSearch.Idle)
    val moodSongs: StateFlow<OnlineSearch> = _moodSongs.asStateFlow()

    /** This build's version without the "-online" suffix, e.g. "0.7.6.1". */
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
        // One quiet update check per launch, once there is a connection.
        scope.launch {
            networkUp.filter { it }.first()
            checkForUpdate()
        }
        // Coming back online retries whatever failed.
        scope.launch {
            networkUp.drop(1).filter { it }.collect {
                if (_discover.value is DiscoverState.Failed) loadDiscover(force = true)
                if (_moodSongs.value is OnlineSearch.Failed) _mood.value.takeIf { it >= 0 }?.let(::loadMood)
            }
        }
    }

    private suspend fun search(q: String, kind: OnlineKind = OnlineKind.SONGS): List<SongEntity> =
        source!!.search(q, kind).also(seen)

    /** Builds the Discover shelves: your most played artists first, then general picks and music videos. */
    fun loadDiscover(force: Boolean = false) {
        val source = source ?: return
        if (!force && _discover.value is DiscoverState.Ready) return
        if (!networkUp.value) {
            _discover.value = DiscoverState.Failed("You're offline.")
            return
        }
        scope.launch {
            _discover.value = DiscoverState.Loading
            val byId = ct.db.songs().observeAll().first().associateBy { it.id }
            val topArtists = ct.db.playEvents().observeAll().first()
                .mapNotNull { byId[it.songId]?.artist }
                .filter { it != "Unknown artist" }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(3).map { it.key }
            val year = LocalDate.now().year
            val plan = topArtists.map { Triple("More from $it", it, OnlineKind.SONGS) } + listOf(
                Triple("Trending now", "top songs $year", OnlineKind.SONGS),
                Triple("New releases", "new songs $year", OnlineKind.SONGS),
                Triple("Music videos", topArtists.firstOrNull()?.let { "$it official music video" } ?: "official music video $year", OnlineKind.VIDEOS),
                Triple("Chill & lo-fi", "lofi chill", OnlineKind.SONGS),
                Triple("Bollywood hits", "bollywood hits", OnlineKind.SONGS),
            )
            val shelves = coroutineScope {
                plan.map { (title, q, kind) ->
                    async {
                        try { Shelf(title, search(q, kind).take(15)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { null }
                    }
                }.awaitAll()
            }.filterNotNull().filter { it.songs.isNotEmpty() }
            _discover.value = if (shelves.isEmpty()) DiscoverState.Failed("Couldn't reach ${source.name}.")
            else DiscoverState.Ready(shelves)
        }
    }

    private var moodJob: Job? = null

    fun selectMood(i: Int) {
        _mood.value = if (_mood.value == i) -1 else i
        moodJob?.cancel()
        _mood.value.takeIf { it >= 0 }?.let(::loadMood)
    }

    private fun loadMood(m: Int) {
        moodJob?.cancel()
        moodJob = scope.launch {
            _moodSongs.value = OnlineSearch.Loading
            _moodSongs.value = try { OnlineSearch.Ready(search(DISCOVER_MOODS[m].second)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { OnlineSearch.Failed(if (networkUp.value) "Couldn't load ${DISCOVER_MOODS[m].first}." else "You're offline.") }
        }
    }
}
