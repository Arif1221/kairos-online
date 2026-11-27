package com.skarif.kairos.online

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.skarif.kairos.data.StreamQuality
import com.skarif.kairos.data.VideoQuality
import com.skarif.kairos.playback.VIDEO_SCHEME
import com.skarif.kairos.data.db.ONLINE_VOLUME
import com.skarif.kairos.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.skarif.kairos.data.db.isOnline
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music through NewPipeExtractor (YouTube's internal API, no key).
 *
 * Unofficial: it breaks when YouTube changes things, and the fix is usually bumping the
 * NewPipeExtractor version in libs.versions.toml.
 */
@OptIn(UnstableApi::class)
internal object YouTubeMusic : OnlineMusic {
    private const val SCHEME = "kairos-yt"

    /** The same video ids, played with their picture (see [videoUri]). */
    private const val SCHEME_VIDEO = VIDEO_SCHEME

    /** Album shown for plain YouTube videos; YouTube Music "Topic" songs show [name] instead. */
    private const val VIDEO_ALBUM = "YouTube"

    /** Stream URLs expire after about 6 hours; refresh well before that. */
    private const val URL_TTL_MS = 3 * 3600_000L

    override val name = "YouTube Music"

    private class Resolved(val url: String, val at: Long)
    private val streamUrls = ConcurrentHashMap<String, Resolved>()
    private val videoUrls = ConcurrentHashMap<String, Resolved>()

    private fun putResolved(map: ConcurrentHashMap<String, Resolved>, key: String, url: String) {
        val now = System.currentTimeMillis()
        map[key] = Resolved(url, now)
        if (map.size > 100) {
            map.entries.removeIf { now - it.value.at >= URL_TTL_MS }
            if (map.size > 100) map.entries.minByOrNull { it.value.at }?.let { map.remove(it.key) }
        }
    }

    override fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * One YouTube extraction per video, shared by the song's sound and its picture. The song is already
     * playing when the picture is asked for, so video mode starts at once instead of asking YouTube
     * a second time (which was slow, and fails more often when repeated).
     */
    private class Extracted(val info: StreamInfo, val at: Long)
    private val extracted = ConcurrentHashMap<String, Extracted>()
    private val inFlightInfo = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<StreamInfo>>()

    /** Coalesces concurrent StreamInfo extractions for the same [videoId] into one network call. */
    private fun infoFor(videoId: String): StreamInfo {
        extracted[videoId]?.let { if (System.currentTimeMillis() - it.at < URL_TTL_MS) return it.info }
        var owner = false
        val future = inFlightInfo.computeIfAbsent(videoId) {
            owner = true
            java.util.concurrent.CompletableFuture()
        }
        if (!owner) {
            return try {
                future.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause as? Exception) ?: IOException(e)
            }
        }
        return try {
            val info = retry { StreamInfo.getInfo(ServiceList.YouTube, watchUrl(videoId)) }
            extracted[videoId] = Extracted(info, System.currentTimeMillis())
            if (extracted.size > 40) extracted.entries.minByOrNull { it.value.at }?.let { extracted.remove(it.key) }
            future.complete(info)
            info
        } catch (t: Throwable) {
            future.completeExceptionally(t)
            throw t
        } finally {
            inFlightInfo.remove(videoId, future)
        }
    }

    /**
     * The quality each song is played at, fixed the first time it is opened in this process.
     * Seeks reopen the stream at a byte offset, so switching quality mid-song would corrupt it.
     */
    private val pinned = ConcurrentHashMap<String, StreamQuality>()
    @Volatile private var appContext: Context? = null

    private val init by lazy {
        val locale = Locale.getDefault()
        NewPipe.init(NewPipeDownloader, Localization.fromLocale(locale), ContentCountry(locale.country.ifBlank { "US" }))
    }

    /** Non-transient YouTube failures that should not be retried in a tight loop. */
    private fun isPermanentError(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            when (cur) {
                is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException,
                is org.schabi.newpipe.extractor.exceptions.ReCaptchaException -> return true
            }
            cur = cur.cause
        }
        return false
    }

    /** Turns NewPipe / network exceptions into concise, user-facing stream errors. */
    private fun friendlyStreamError(e: Throwable, fallback: String): IOException {
        var cur: Throwable? = e
        while (cur != null) {
            when (cur) {
                is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException ->
                    return IOException("Age-restricted on YouTube", e)
                is org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException ->
                    return IOException("Not available in your region", e)
                is org.schabi.newpipe.extractor.exceptions.PrivateContentException ->
                    return IOException("This video is private", e)
                is org.schabi.newpipe.extractor.exceptions.PaidContentException ->
                    return IOException("Paid content cannot be streamed", e)
                is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException ->
                    return IOException("Unavailable on YouTube", e)
                is org.schabi.newpipe.extractor.exceptions.ReCaptchaException ->
                    return IOException("Too many requests — try again shortly", e)
                is java.net.UnknownHostException,
                is java.net.ConnectException ->
                    return IOException("No internet connection", e)
                is java.net.SocketTimeoutException ->
                    return IOException("Connection timed out", e)
            }
            cur = cur.cause
        }
        return (e as? IOException)?.takeIf { !it.message.isNullOrBlank() } ?: IOException(fallback, e)
    }

    /** YouTube sometimes fails a request that works a moment later. */
    private inline fun <T> retry(times: Int = 2, block: () -> T): T {
        var last: Exception? = null
        repeat(times) { attempt ->
            try { return block() } catch (e: InterruptedException) {
                throw e // cancelled or timed out: stop here instead of fetching again in the background
            } catch (e: Exception) {
                if (isPermanentError(e)) throw e
                last = e
                if (attempt < times - 1) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw last!!
    }

    // ---- Browsing ----------------------------------------------------------------------------

    private class Cursor(val extractor: SearchExtractor, val page: Page, val kind: OnlineKind, val query: String)

    /** Small in-memory caches so tab switches and going back are instant. */
    private class Lru<V>(private val max: Int, private val ttlMs: Long) {
        private val map = object : LinkedHashMap<String, Pair<Long, V>>(16, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, V>>) = size > max
        }
        @Synchronized fun get(k: String): V? = map[k]?.takeIf { System.currentTimeMillis() - it.first < ttlMs }?.second
        @Synchronized fun put(k: String, v: V) { map[k] = System.currentTimeMillis() to v }
        @Synchronized fun remove(k: String) { map.remove(k) }
    }
    private val searches = Lru<OnlinePage>(40, 10 * 60_000L)
    private val collections = Lru<OnlineCollection>(30, 30 * 60_000L)
    private val radios = Lru<List<SongEntity>>(20, 30 * 60_000L)
    private val found = Lru<SongEntity?>(200, 24 * 3600_000L)
    /** Song's video id; "" is a remembered "no video found". */
    private val videos = Lru<String>(200, 24 * 3600_000L)

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        init
        runCatching { ServiceList.YouTube.suggestionExtractor.suggestionList(query.trim()).take(8) }.getOrDefault(emptyList())
    }

    override suspend fun search(query: String, kind: OnlineKind, after: OnlinePage?): OnlinePage = withContext(Dispatchers.IO) {
        init
        val cursor = after?.cursor as? Cursor
        if (cursor != null) {
            val page = retry { cursor.extractor.getPage(cursor.page) }
            return@withContext OnlinePage(toItems(page.items, cursor.kind, cursor.query), page.nextPage?.let { Cursor(cursor.extractor, it, cursor.kind, cursor.query) })
        }
        val q = query.trim()
        val cacheKey = "$kind|${q.lowercase()}"
        searches.get(cacheKey)?.let { return@withContext it }
        val result = when (kind) {
            // YouTube Music's song search answers empty in some regions; ranked music videos stand in.
            OnlineKind.SONGS -> first(q, MUSIC_SONGS, kind).takeIf { it.items.isNotEmpty() }
                ?: first(q, MUSIC_VIDEOS, kind)
            OnlineKind.VIDEOS -> first(q, YoutubeSearchQueryHandlerFactory.VIDEOS, kind)
            OnlineKind.ARTISTS -> first(q, MUSIC_ARTISTS, kind).takeIf { it.items.isNotEmpty() }
                ?: first(q, YoutubeSearchQueryHandlerFactory.CHANNELS, kind)
            OnlineKind.PLAYLISTS -> coroutineScope {
                val albums = async { runCatching { first(q, MUSIC_ALBUMS, kind) }.getOrNull() }
                val lists = async { first(q, MUSIC_PLAYLISTS, kind) }
                val listPage = lists.await()
                val merged = (albums.await()?.items.orEmpty() + listPage.items).distinctBy { it.key }
                    .sortedByDescending { (it as? OnlineItem.Collection)?.isAlbum == true }
                OnlinePage(merged, listPage.cursor)
            }
        }
        result.also { if (it.items.isNotEmpty()) searches.put(cacheKey, it) }
    }

    private fun first(query: String, filter: String, kind: OnlineKind): OnlinePage = retry {
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        ex.fetchPage()
        val page = ex.initialPage
        OnlinePage(toItems(page.items, kind, query), page.nextPage?.takeIf { Page.isValid(it) }?.let { Cursor(ex, it, kind, query) })
    }

    private fun toItems(items: List<InfoItem>, kind: OnlineKind, query: String): List<OnlineItem> = when (kind) {
        OnlineKind.SONGS -> songsFrom(items, query).map { OnlineItem.Track(it) }
        OnlineKind.VIDEOS -> items.filterIsInstance<StreamInfoItem>().filterNot { it.isShortFormContent }
            .mapNotNull { it.toSong(tidy = true) }.map { OnlineItem.Track(it) }
        OnlineKind.ARTISTS -> items.filterIsInstance<ChannelInfoItem>().map {
            OnlineItem.Artist(it.url, it.name.removeSuffix(" - Topic"), art(it.thumbnails, square = true),
                it.subscriberCount.takeIf { n -> n > 0 }?.let(::subscribers))
        }
        OnlineKind.PLAYLISTS -> items.filterIsInstance<PlaylistInfoItem>().map { it.toCollection() }
    }.distinctBy { it.key }

    /** Stream results as songs, best match first when they came from a video search. */
    private fun songsFrom(items: List<InfoItem>, query: String): List<SongEntity> {
        val streams = items.filterIsInstance<StreamInfoItem>().filterNot { it.isShortFormContent }
        val ranked = SongMatch.rank(streams, query) { SongMatch.Raw(it.name, it.uploaderName.orEmpty(), it.duration, it.viewCount, it.isUploaderVerified) }
        return ranked.mapNotNull { it.toSong(tidy = true, query = query) }.distinctBy { it.fileName }
    }

    override suspend fun collection(id: String): OnlineCollection = withContext(Dispatchers.IO) {
        collections.get(id)?.let { return@withContext it }
        init
        val yt = ServiceList.YouTube
        val info = retry { PlaylistInfo.getInfo(yt, id) }
        val items = info.relatedItems.toMutableList()
        var next = info.nextPage
        var pages = 0
        while (next != null && Page.isValid(next) && items.size < 200 && pages++ < 4) {
            val more = runCatching { PlaylistInfo.getMoreItems(yt, id, next) }.getOrNull() ?: break
            items += more.items
            next = more.nextPage
        }
        val album = isAlbum(id)
        OnlineCollection(
            title = info.name,
            owner = info.uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() },
            art = art(info.thumbnails, square = true) ?: items.firstOrNull()?.let { art(it.thumbnails, square = true) },
            isAlbum = album,
            songs = items.filterNot { it.isShortFormContent }.mapNotNull { it.toSong(tidy = !album) }.distinctBy { it.fileName },
        ).also { collections.put(id, it) }
    }

    private val artists = Lru<OnlineArtist>(20, 30 * 60_000L)

    /** Runs a blocking NewPipe call with a deadline, so one slow request can't hold a page forever. */
    private suspend fun <T> within(ms: Long, block: () -> T): T? =
        withTimeoutOrNull(ms) { runCatching { runInterruptible(Dispatchers.IO) { block() } }.getOrNull() }

    override suspend fun artist(id: String, name: String): OnlineArtist = withContext(Dispatchers.IO) {
        artists.get("$id|$name")?.let { return@withContext it }
        init
        val yt = ServiceList.YouTube
        coroutineScope {
            // The channel page and the search fallback start together: whichever is useful is ready
            // sooner, and nothing waits more than ~12 s. (Before, each step waited in turn, up to
            // minutes on a slow network, and the page sat on "loading".)
            val byName = async { runCatching { search(name, OnlineKind.SONGS).items }.getOrDefault(emptyList()) }
            val listsByName = async { runCatching { search(name, OnlineKind.PLAYLISTS).items }.getOrDefault(emptyList()) }
            val info = if (id.isBlank()) null else within(12_000) { ChannelInfo.getInfo(yt, id) }
            val tabs = info?.tabs.orEmpty()
            fun tab(f: String) = async {
                tabs.firstOrNull { f in it.contentFilters }?.let { within(10_000) { ChannelTabInfo.getInfo(yt, it).relatedItems } }.orEmpty()
            }
            val tracks = tab(ChannelTabs.TRACKS)
            val videos = tab(ChannelTabs.VIDEOS)
            val albums = tab(ChannelTabs.ALBUMS)
            val lists = tab(ChannelTabs.PLAYLISTS)
            val songs = tracks.await().ifEmpty { videos.await() }
                .filterIsInstance<StreamInfoItem>().filterNot { it.isShortFormContent }
                .mapNotNull { it.toSong(tidy = true) }
                .toMutableList()
            val releases = (albums.await() + lists.await())
                .filterIsInstance<PlaylistInfoItem>().map { it.toCollection() }.distinctBy { it.id }.toMutableList()
            val artistName = info?.name?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() } ?: name
            // Channel pages often come back empty; their songs and albums are still a search away.
            if (songs.size < 5) {
                val hits = (withTimeoutOrNull(12_000) { byName.await() } ?: emptyList())
                    .filterIsInstance<OnlineItem.Track>().map { it.song }
                val theirs = hits.filter { artistName.lowercase() in it.artist.lowercase() }
                songs += (theirs.ifEmpty { hits }).filter { s -> songs.none { it.fileName == s.fileName } }
            } else byName.cancel()
            if (releases.isEmpty()) {
                releases += (withTimeoutOrNull(12_000) { listsByName.await() } ?: emptyList())
                    .filterIsInstance<OnlineItem.Collection>().take(12)
            } else listsByName.cancel()
            if (songs.isEmpty() && releases.isEmpty()) throw IOException("Nothing found for $name")
            OnlineArtist(
                name = artistName,
                art = info?.avatars?.let { art(it, square = true) } ?: songs.firstOrNull()?.artUrl,
                songs = songs.take(50),
                collections = releases,
            ).also { artists.put("$id|$name", it) }
        }
    }

    override suspend fun radio(song: SongEntity): List<SongEntity> = withContext(Dispatchers.IO) {
        val seed = if (song.isOnline) song else findSong(song.title, song.artist) ?: return@withContext emptyList()
        val id = seed.fileName
        radios.get(id)?.let { return@withContext it }
        init
        val yt = ServiceList.YouTube
        val mix = runCatching {
            retry { PlaylistInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id&list=RD$id") }.relatedItems
        }.getOrNull().orEmpty()
        val items = mix.ifEmpty {
            // No mix for this video: fall back to the "up next" list.
            runCatching { StreamInfo.getInfo(yt, watchUrl(id)).relatedItems.filterIsInstance<StreamInfoItem>() }.getOrDefault(emptyList())
        }
        items.filterNot { it.isShortFormContent || it.duration in 1..44 || it.duration > 900 }
            .mapNotNull { it.toSong(tidy = true) }
            .filter { it.fileName != id && it.fileName != song.fileName }
            .distinctBy { it.fileName }
            .take(50)
            .also { if (it.isNotEmpty()) radios.put(id, it) }
    }

    override suspend fun findSong(title: String, artist: String): SongEntity? = withContext(Dispatchers.IO) {
        val key = "${title.lowercase()}|${artist.lowercase()}"
        found.get(key)?.let { return@withContext it }
        val q = if (artist.isBlank() || artist.startsWith("Unknown")) title else "$title $artist"
        val hits = runCatching { search(q, OnlineKind.SONGS).items }.getOrNull() ?: return@withContext null
        val want = title.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        hits.filterIsInstance<OnlineItem.Track>().map { it.song }
            .firstOrNull { s -> want.isEmpty() || s.title.lowercase().split(Regex("\\W+")).any { it in want } }
            .also { found.put(key, it) }
    }

    override suspend fun diagnose(): List<OnlineCheck> = withContext(Dispatchers.IO) {
        init
        val out = mutableListOf<OnlineCheck>()
        suspend fun check(name: String, block: suspend () -> String) {
            val t = System.currentTimeMillis()
            out += try {
                OnlineCheck(name, true, "${block()} · ${System.currentTimeMillis() - t} ms")
            } catch (e: Exception) {
                OnlineCheck(name, false, e.message?.take(80) ?: e.javaClass.simpleName)
            }
        }
        var sample: SongEntity? = null
        check("Suggestions") { "${ServiceList.YouTube.suggestionExtractor.suggestionList("arijit").size} found" }
        check("Song search") {
            val songs = first("kesariya", MUSIC_SONGS, OnlineKind.SONGS).items
            val videos = if (songs.isEmpty()) first("kesariya", MUSIC_VIDEOS, OnlineKind.SONGS).items else songs
            sample = (videos.firstOrNull() as? OnlineItem.Track)?.song
            if (videos.isEmpty()) error("No results")
            if (songs.isEmpty()) "${videos.size} via videos (songs tab empty)" else "${songs.size} songs"
        }
        check("Artists") { "${first("arijit singh", MUSIC_ARTISTS, OnlineKind.ARTISTS).items.size} found" }
        check("Playlists") { "${first("bollywood hits", MUSIC_PLAYLISTS, OnlineKind.PLAYLISTS).items.size} found" }
        sample?.let { s ->
            check("Radio") { "${PlaylistInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${s.fileName}&list=RD${s.fileName}").relatedItems.size} songs" }
            check("Stream") {
                val url = streamUrl(s.fileName, StreamQuality.LOW)
                val code = NewPipeDownloader.probe(url)
                if (code !in 200..299) error("HTTP $code — streams are blocked on this network")
                "plays (HTTP $code)"
            }
        }
        out
    }

    private fun PlaylistInfoItem.toCollection() = OnlineItem.Collection(
        id = url,
        title = name,
        owner = uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() },
        art = art(thumbnails, square = true),
        isAlbum = isAlbum(url),
        count = streamCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
    )

    private fun isAlbum(url: String) = "OLAK5uy" in url

    private fun subscribers(n: Long) = when {
        n >= 1_000_000 -> "%.1fM subscribers".format(n / 1_000_000.0).replace(".0M", "M")
        n >= 1_000 -> "${n / 1000}K subscribers"
        else -> "$n subscribers"
    }

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    override fun mediaUri(song: SongEntity): Uri = Uri.parse("$SCHEME:${song.fileName}")

    override fun videoUri(videoId: String): Uri = Uri.parse("$SCHEME_VIDEO:$videoId")

    override fun audioUri(videoId: String): Uri = Uri.parse("$SCHEME:$videoId")

    @Volatile private var videoTier: () -> VideoQuality = { VideoQuality.AUTO }
    override fun setVideoQualityProvider(provider: () -> VideoQuality) { videoTier = provider }

    /**
     * An explicit picture quality gets its own cache entries (a different file behind the same key
     * would play as garbage). AUTO keeps the original key, and downloads always use it, so videos
     * saved earlier stay playable whatever the picture setting is now.
     */
    private fun videoCacheKey(videoId: String, quality: StreamQuality, tier: VideoQuality = videoTier()) =
        "yt-video:$videoId:$quality" + if (tier == VideoQuality.AUTO) "" else ":${tier.name}"

    /** Resolve the current video and cache only its opening range; playback continues on audio meanwhile. */
    override suspend fun prefetchVideo(context: Context, song: SongEntity, quality: StreamQuality): Unit = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        val videoId = videoFor(song) ?: throw IOException("No video match for ${song.title}")
        // Resolve now so playback does not pay the extractor/API latency after the user switches.
        val app = context.applicationContext
        val selectedQuality = qualityFor(app, videoId) { quality }
        videoStreamUrl(videoId, selectedQuality)
        val videoKey = videoCacheKey(videoId, selectedQuality)
        val audioKey = cacheKey(videoId, selectedQuality)
        val videoBytes = when (selectedQuality) {
            StreamQuality.LOW -> 128L * 1024L
            StreamQuality.NORMAL -> 384L * 1024L
            StreamQuality.HIGH -> 768L * 1024L
        }
        val audioBytes = when (selectedQuality) {
            StreamQuality.LOW -> 64L * 1024L
            StreamQuality.NORMAL -> 96L * 1024L
            StreamQuality.HIGH -> 128L * 1024L
        }
        // The merged video source also needs the matched video's audio track. Warm both sides
        // concurrently so the stream swap does not wait for a second extractor/network startup.
        coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    prefetchRange(
                        videoDataSourceFactory(app, videoId, selectedQuality).createDataSource(),
                        videoUri(videoId), videoKey, videoBytes,
                    )
                },
                async(Dispatchers.IO) {
                    prefetchRange(
                        audioPrefetchDataSourceFactory(app, videoId, selectedQuality),
                        audioUri(videoId), audioKey, audioBytes,
                    )
                },
            ).awaitAll()
        }
        Unit
    }

    /** Next-track preparation is metadata-only: no video payload is downloaded. */
    override suspend fun prefetchVideoMetadata(song: SongEntity, quality: StreamQuality) = withContext(Dispatchers.IO) {
        val videoId = videoFor(song) ?: return@withContext
        infoFor(videoId)
        // Prepare both component URLs used by MergingMediaSource; still no media bytes.
        videoStreamUrl(videoId, quality)
        streamUrl(videoId, quality)
    }

    private suspend fun prefetchRange(source: CacheDataSource, uri: Uri, key: String, length: Long) {
        val spec = DataSpec.Builder().setUri(uri).setKey(key).setLength(length).build()
        val writer = CacheWriter(source, spec, null, null)
        try {
            runInterruptible { writer.cache() }
        } catch (e: CancellationException) {
            writer.cancel()
            throw e
        } finally {
            source.close()
        }
    }

    private fun audioPrefetchDataSourceFactory(context: Context, videoId: String, quality: StreamQuality): CacheDataSource {
        val key = cacheKey(videoId, quality)
        val streamed = CacheDataSource.Factory()
            .setCache(StreamCache.get(context))
            .setUpstreamDataSourceFactory(resolving { quality })
            .setCacheKeyFactory { key }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory()
            .setCache(StreamCache.downloads(context))
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(streamed)
            .setCacheKeyFactory { key }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }

    /** Writable persistent layer used only by explicit offline-video downloads. */
    private fun audioDownloadDataSourceFactory(context: Context, videoId: String, quality: StreamQuality): CacheDataSource {
        val key = cacheKey(videoId, quality)
        val streamed = CacheDataSource.Factory()
            .setCache(StreamCache.get(context))
            .setUpstreamDataSourceFactory(resolving { quality })
            .setCacheKeyFactory { key }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory()
            .setCache(StreamCache.downloads(context))
            .setUpstreamDataSourceFactory(streamed)
            .setCacheKeyFactory { key }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }

    private fun videoDataSourceFactory(context: Context, videoId: String, quality: StreamQuality, tier: VideoQuality = videoTier()): CacheDataSource.Factory {
        val key = videoCacheKey(videoId, quality, tier)
        val upstream = ResolvingDataSource.Factory(http) { spec ->
            spec.withUri(Uri.parse(videoStreamUrl(videoId, quality, tier)))
        }
        return CacheDataSource.Factory()
            .setCache(StreamCache.videos(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { key }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun videoDataSourceFactory(context: Context, quality: () -> StreamQuality): DataSource.Factory =
        DataSource.Factory {
            val app = context.applicationContext
            appContext = app
            // Resolve by the requested id per DataSpec, preserving the quality chosen for that item.
            val upstream = ResolvingDataSource.Factory(http) { spec ->
                val id = spec.uri.schemeSpecificPart
                spec.withUri(Uri.parse(videoStreamUrl(id, qualityFor(app, id, quality))))
            }
            val streamed = CacheDataSource.Factory()
                .setCache(StreamCache.videos(app))
                .setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory { spec ->
                    val id = spec.uri.schemeSpecificPart
                    videoCacheKey(id, qualityFor(app, id, quality))
                }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            // Persistent completed video downloads are read first and never written by playback.
            CacheDataSource.Factory()
                .setCache(StreamCache.videoDownloads(app))
                .setCacheWriteDataSinkFactory(null)
                .setUpstreamDataSourceFactory(streamed)
                .setCacheKeyFactory { spec ->
                    val id = spec.uri.schemeSpecificPart
                    videoCacheKey(id, qualityFor(app, id, quality), VideoQuality.AUTO) // downloads keep the original key
                }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        }

    override fun forgetStream(uri: Uri) {
        val id = uri.schemeSpecificPart ?: return
        streamUrls.keys.removeIf { it.startsWith("$id|") }
        videoUrls.keys.removeIf { it.startsWith("$id|") }
        extracted.remove(id)
    }

    override fun forgetVideoMatch(song: SongEntity) {
        videos.remove(song.fileName)
        appContext?.getSharedPreferences("video_matches", Context.MODE_PRIVATE)?.edit()?.remove(song.fileName)?.apply()
    }

    override fun chooseVideo(song: SongEntity, videoId: String) {
        videos.put(song.fileName, videoId)
        appContext?.getSharedPreferences("video_matches", Context.MODE_PRIVATE)?.edit()?.putString(song.fileName, videoId)?.apply()
    }

    override suspend fun videoFor(song: SongEntity): String? = withContext(Dispatchers.IO) {
        if (!song.isOnline) return@withContext null
        val prefs = appContext?.getSharedPreferences("video_matches", Context.MODE_PRIVATE)
        prefs?.getString(song.fileName, null)?.let { saved ->
            if (saved.isNotBlank()) { videos.put(song.fileName, saved); return@withContext saved }
            if (saved.isEmpty()) return@withContext null
        }
        // Found as a video (Videos tab, or a region where song search is empty): it is its own video.
        if (song.album == VIDEO_ALBUM) {
            prefs?.edit()?.putString(song.fileName, song.fileName)?.apply()
            return@withContext song.fileName
        }
        videos.get(song.fileName)?.let { return@withContext it.ifEmpty { null } }
        init
        // Search with the song's plain name and first artist: "(From ...)" and "feat." only dilute the results.
        val query = "${VideoMatch.coreTitle(song.title)} ${song.artist.split(',', '&').first().trim()}".trim()
        fun lookup(filter: String) = runCatching {
            retry {
                val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
                ex.fetchPage()
                ex.initialPage.items.filterIsInstance<StreamInfoItem>().filterNot { it.isShortFormContent }
            }
        }
        // Music-video search often answers with the Topic audio or nothing (empty in some regions), so
        // plain videos are searched in parallel and the best match is picked from both.
        val (music, plain) = coroutineScope {
            val m = async(Dispatchers.IO) { lookup(MUSIC_VIDEOS) }
            val p = async(Dispatchers.IO) { lookup(YoutubeSearchQueryHandlerFactory.VIDEOS) }
            m.await() to p.await()
        }
        // A failed search is not evidence that no video exists. If both searches failed, or
        // every successful search returned no candidates while another search failed, propagate
        // the error so the caller can retry instead of caching a false negative for 24 hours.
        (music.exceptionOrNull() as? InterruptedException)?.let { throw it }
        (plain.exceptionOrNull() as? InterruptedException)?.let { throw it }
        val hits = (music.getOrDefault(emptyList()) + plain.getOrDefault(emptyList())).distinctBy { it.url }
        if (hits.isEmpty() && (music.isFailure || plain.isFailure)) {
            throw music.exceptionOrNull() ?: plain.exceptionOrNull()!!
        }
        val candidates = hits.map { VideoMatch.Candidate(it.name, it.uploaderName.orEmpty(), it.duration, it.viewCount) }
        // Strict first; if nothing passes, a looser second pass (covers, live takes and remixes are still
        // rejected) so fewer songs end up with a greyed-out Video button.
        val best = VideoMatch.pick(song.title, song.artist, song.durationMs / 1000, candidates)
            ?: VideoMatch.pick(song.title, song.artist, song.durationMs / 1000, candidates, relaxed = true)
        // If the successful search produced no acceptable match but the other search failed,
        // the result is inconclusive rather than a confirmed no-video result.
        if (best == null && (music.isFailure || plain.isFailure)) {
            throw music.exceptionOrNull() ?: plain.exceptionOrNull()!!
        }
        val id = best?.let { runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(hits[it].url) }.getOrNull() }
        videos.put(song.fileName, id ?: "")
        if (id != null) prefs?.edit()?.putString(song.fileName, id)?.apply()
        id
    }

    private fun cacheKey(videoId: String, q: StreamQuality) = "yt:$videoId:$q"

    override fun isCached(context: Context, song: SongEntity): Boolean =
        isDownloaded(context, song) || StreamQuality.values().any { StreamCache.isComplete(context, cacheKey(song.fileName, it)) }

    override fun cacheBytes(context: Context): Long =
        StreamCache.get(context).cacheSpace + StreamCache.videos(context).cacheSpace

    override fun clearCache(context: Context) {
        StreamCache.clear(context)
        pinned.clear()
    }

    // ---- Downloads ---------------------------------------------------------------------------

    override fun isDownloaded(context: Context, song: SongEntity): Boolean {
        val saved = StreamCache.downloads(context)
        return StreamQuality.values().any { StreamCache.isComplete(saved, cacheKey(song.fileName, it)) }
    }

    override fun downloadedIds(context: Context): Set<String> {
        val saved = StreamCache.downloads(context)
        val matches = context.getSharedPreferences("video_download_matches", Context.MODE_PRIVATE).all
        val companionVideoIds = matches.values.filterIsInstance<String>().toSet()
        val songIds = matches.keys
        return saved.keys.filter { StreamCache.isComplete(saved, it) }
            .mapNotNull { it.split(':').getOrNull(1) }
            .filterTo(HashSet()) { it !in companionVideoIds || it in songIds }
    }

    override fun downloadedVideoIds(context: Context): Set<String> {
        val saved = StreamCache.videoDownloads(context)
        val mapping = context.getSharedPreferences("video_download_matches", Context.MODE_PRIVATE).all
        val result = HashSet<String>()
        for ((songId, value) in mapping) {
            val videoId = value as? String ?: continue
            if (StreamQuality.values().any { q -> StreamCache.isComplete(saved, videoCacheKey(videoId, q, VideoQuality.AUTO)) }) result += songId
        }
        return result
    }

    override fun downloadBytes(context: Context): Long = StreamCache.downloads(context).cacheSpace + StreamCache.videoDownloads(context).cacheSpace

    override fun removeDownload(context: Context, videoId: String) {
        val saved = StreamCache.downloads(context)
        val videoSaved = StreamCache.videoDownloads(context)
        val matches = context.getSharedPreferences("video_download_matches", Context.MODE_PRIVATE)
        val matchedVideoId = matches.getString(videoId, null)
        StreamQuality.values().forEach { q ->
            saved.removeResource(cacheKey(videoId, q))
            videoSaved.removeResource(videoCacheKey(videoId, q, VideoQuality.AUTO))
            if (matchedVideoId != null) {
                saved.removeResource(cacheKey(matchedVideoId, q))
                videoSaved.removeResource(videoCacheKey(matchedVideoId, q, VideoQuality.AUTO))
            }
        }
        matches.edit().remove(videoId).apply()
    }

    override fun removeVideoDownload(context: Context, songId: String) {
        val matches = context.getSharedPreferences("video_download_matches", Context.MODE_PRIVATE)
        val matchedVideoId = matches.getString(songId, null) ?: return
        StreamCache.videoDownloads(context).let { saved ->
            StreamQuality.values().forEach { saved.removeResource(videoCacheKey(matchedVideoId, it, VideoQuality.AUTO)) }
        }
        if (matchedVideoId != songId) StreamCache.downloads(context).let { saved ->
            StreamQuality.values().forEach { saved.removeResource(cacheKey(matchedVideoId, it)) }
        }
        matches.edit().remove(songId).apply()
    }

    override fun setCacheLimit(context: Context, bytes: Long) = StreamCache.setLimit(context, bytes)

    override suspend fun fetchM4a(song: SongEntity, into: java.io.File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        init
        val info = retry { StreamInfo.getInfo(ServiceList.YouTube, watchUrl(song.fileName)) }
        val stream = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format == org.schabi.newpipe.extractor.MediaFormat.M4A }
            .maxByOrNull(::kbps) ?: throw IOException("No M4A audio for ${song.fileName}")
        NewPipeDownloader.downloadTo(stream.content, into, progress)
    }

    override suspend fun download(context: Context, song: SongEntity, quality: StreamQuality, progress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val id = song.fileName
            // The quality it's already playing or streamed at, so those bytes are reused instead of fetched again.
            val q = pinned[id]
                ?: StreamQuality.values().reversed().firstOrNull { StreamCache.isComplete(app, cacheKey(id, it)) }
                ?: quality
            pinned.putIfAbsent(id, q)
            val key = cacheKey(id, q)
            val streamed = CacheDataSource.Factory()
                .setCache(StreamCache.get(app))
                .setCacheWriteDataSinkFactory(null) // read what's there, don't fill the stream cache too
                .setUpstreamDataSourceFactory(resolving { q })
                .setCacheKeyFactory { key }
            val source = CacheDataSource.Factory()
                .setCache(StreamCache.downloads(app))
                .setUpstreamDataSourceFactory(streamed)
                .setCacheKeyFactory { key }
                .createDataSource()
            val spec = DataSpec.Builder().setUri(mediaUri(song)).setKey(key).build()
            val writer = CacheWriter(source, spec, null) { length, done, _ -> if (length > 0) progress(done.toFloat() / length) }
            coroutineScope {
                val work = async(Dispatchers.IO) { writer.cache() }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    writer.cancel() // CacheWriter blocks its thread; this makes it stop
                    throw e
                }
            }
            if (!StreamCache.isComplete(StreamCache.downloads(app), key)) throw IOException("Download of $id didn't finish")
        }
    }

    override suspend fun downloadVideo(context: Context, song: SongEntity, quality: StreamQuality, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        appContext = app
        val videoId = videoFor(song) ?: throw IOException("No matching video for ${song.title}")
        app.getSharedPreferences("video_download_matches", Context.MODE_PRIVATE).edit().putString(song.fileName, videoId).apply()
        val q = qualityFor(app, videoId) { quality }
        val videoKey = videoCacheKey(videoId, q, VideoQuality.AUTO)
        val videoSpec = DataSpec.Builder().setUri(videoUri(videoId)).setKey(videoKey).build()
        val videoSource = CacheDataSource.Factory()
            .setCache(StreamCache.videoDownloads(app))
            .setUpstreamDataSourceFactory(videoDataSourceFactory(app, videoId, q, VideoQuality.AUTO))
            .setCacheKeyFactory { videoKey }
            .createDataSource()
        val videoWriter = CacheWriter(videoSource, videoSpec, null) { length, done, _ ->
            if (length > 0) progress((done.toFloat() / length * 0.5f).coerceIn(0f, 0.5f))
        }
        try {
            coroutineScope {
                val work = async(Dispatchers.IO) { videoWriter.cache() }
                try { work.await() } catch (e: CancellationException) { videoWriter.cancel(); throw e }
            }
        } finally { videoSource.close() }

        // Video playback merges the picture with that video's own audio track. Persist that audio
        // under the same key used by audioUri(videoId), so the merged source works offline too.
        val audioKey = cacheKey(videoId, q)
        val audioSource = audioDownloadDataSourceFactory(app, videoId, q)
        val audioSpec = DataSpec.Builder().setUri(audioUri(videoId)).setKey(audioKey).build()
        val audioWriter = CacheWriter(audioSource, audioSpec, null) { length, done, _ ->
            if (length > 0) progress((0.5f + done.toFloat() / length * 0.5f).coerceIn(0.5f, 1f))
        }
        try {
            coroutineScope {
                val work = async(Dispatchers.IO) { audioWriter.cache() }
                try { work.await() } catch (e: CancellationException) { audioWriter.cancel(); throw e }
            }
        } finally { audioSource.close() }
        if (!StreamCache.isComplete(StreamCache.videoDownloads(app), videoKey)) throw IOException("Video download of $videoId didn't finish")
        if (!StreamCache.isComplete(StreamCache.downloads(app), audioKey)) throw IOException("Video audio download of $videoId didn't finish")
        progress(1f)
    }

    /** Which quality to use for [videoId]: already pinned, else one downloaded or fully cached (plays offline), else the setting. */
    private fun qualityFor(context: Context, videoId: String, setting: () -> StreamQuality): StreamQuality =
        pinned.getOrPut(videoId) {
            val saved = StreamCache.downloads(context)
            StreamQuality.values().reversed().firstOrNull { StreamCache.isComplete(saved, cacheKey(videoId, it)) }
                ?: StreamQuality.values().reversed().firstOrNull { StreamCache.isComplete(context, cacheKey(videoId, it)) }
                ?: setting()
        }

    private val http by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent(NewPipeDownloader.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
    }

    /** Our Uris become the real stream URL only when bytes are actually fetched, so URLs are always fresh. */
    private fun resolving(quality: (String) -> StreamQuality) = ResolvingDataSource.Factory(http) { spec ->
        val id = spec.uri.schemeSpecificPart
        spec.withUri(Uri.parse(streamUrl(id, quality(id))))
    }

    override fun dataSourceFactory(context: Context, quality: () -> StreamQuality): DataSource.Factory {
        val app = context.applicationContext
        appContext = app
        val key = { spec: DataSpec -> spec.uri.schemeSpecificPart.let { cacheKey(it, qualityFor(app, it, quality)) } }
        // Downloads first (read only), then the stream cache, then the network.
        val streamed = CacheDataSource.Factory()
            .setCache(StreamCache.get(app))
            .setUpstreamDataSourceFactory(resolving { qualityFor(app, it, quality) })
            .setCacheKeyFactory(key)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val cached = CacheDataSource.Factory()
            .setCache(StreamCache.downloads(app))
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(streamed)
            .setCacheKeyFactory(key)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Video has its own bounded cache; prefetched startup bytes are reused by playback and
        // cannot evict audio. The rest of the clip streams through the same cache transparently.
        val video = videoDataSourceFactory(app, quality)
        val local = DefaultDataSource.Factory(app)
        return DataSource.Factory {
            RoutingDataSource(mapOf(SCHEME to cached.createDataSource(), SCHEME_VIDEO to video.createDataSource()), local.createDataSource())
        }
    }

    /** Called on the player's loading thread, so blocking here is fine. */
    private fun streamUrl(videoId: String, quality: StreamQuality): String {
        val key = "$videoId|$quality"
        streamUrls[key]?.let { if (System.currentTimeMillis() - it.at < URL_TTL_MS) return it.url }
        try {
            init
            val info = infoFor(videoId)
            // Audio-only streams here; the picture is only fetched by [videoStreamUrl], when the player is in video mode.
            val streams = info.audioStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
                .sortedBy(::kbps)
            if (streams.isEmpty()) throw IOException("No playable audio stream")
            val pick = when (quality) {
                StreamQuality.LOW -> streams.first()
                StreamQuality.HIGH -> streams.last()
                StreamQuality.NORMAL -> streams.lastOrNull { kbps(it) in 1..140 } ?: streams.first()
            }
            return pick.content.also { putResolved(streamUrls, key, it) }
        } catch (e: Exception) {
            throw friendlyStreamError(e, "Couldn't load audio stream") // the player only reports IOExceptions as load errors
        }
    }

    /**
     * The picture only (no sound): YouTube now serves almost nothing with picture and sound in one
     * file, so video mode plays this merged with the song's own audio (see PlaybackService).
     * Data saver takes the smallest. Called on the player's loading thread, so blocking is fine.
     */
    private fun videoStreamUrl(videoId: String, quality: StreamQuality, tier: VideoQuality = videoTier()): String {
        val key = "$videoId|$quality|${tier.name}"
        videoUrls[key]?.let { if (System.currentTimeMillis() - it.at < URL_TTL_MS) return it.url }
        try {
            init
            val info = infoFor(videoId)
            fun height(s: org.schabi.newpipe.extractor.stream.VideoStream) = s.resolution.substringBefore('p').toIntOrNull() ?: 0
            // Picture-only streams first; a muxed one (rare now) works too, its own sound is just not used.
            val all = (info.videoOnlyStreams + info.videoStreams)
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            if (all.isEmpty()) throw IOException("No playable video stream")
            // MP4 (H.264) plays on every phone's hardware decoder; WebM only when it's all there is.
            val usable = all.filter { it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 }.ifEmpty { all }
                .sortedBy(::height)
            val pick = if (tier == VideoQuality.AUTO) {
                when (quality) {
                    StreamQuality.LOW -> usable.first()
                    else -> usable.lastOrNull { height(it) <= 720 } ?: usable.first()
                }
            } else {
                // The listener's choice; the mobile-data saver still caps it at 360p.
                val cap = if (quality == StreamQuality.LOW) minOf(tier.maxHeight, 360) else tier.maxHeight
                usable.lastOrNull { height(it) <= cap } ?: usable.first()
            }
            return pick.content.also { putResolved(videoUrls, key, it) }
        } catch (e: Exception) {
            throw friendlyStreamError(e, "Couldn't load video stream")
        }
    }

    /** Average bitrate in kbps; some streams only report the peak, in bits per second. */
    private fun kbps(s: AudioStream): Int = if (s.averageBitrate > 0) s.averageBitrate else s.bitrate / 1000

    private fun StreamInfoItem.toSong(tidy: Boolean, query: String = ""): SongEntity? {
        val videoId = runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(url) }.getOrNull() ?: return null
        val uploader = uploaderName.orEmpty()
        val topic = uploader.endsWith(" - Topic")
        val (title, artist) = if (tidy) SongMatch.clean(name, uploader, query) else name to uploader.removeSuffix(" - Topic")
        return SongEntity(
            id = idFor(videoId),
            title = title,
            artist = artist.ifBlank { "Unknown artist" },
            album = if (topic) this@YouTubeMusic.name else VIDEO_ALBUM,
            albumId = 0,
            durationMs = duration.coerceAtLeast(0) * 1000,
            track = 0,
            year = 0,
            folder = "",
            fileName = videoId,
            volume = ONLINE_VOLUME,
            mimeType = "audio/opus",
            sizeBytes = 0,
            dateAddedSec = System.currentTimeMillis() / 1000,
            bitrate = 0,
            artUrl = art(thumbnails, square = topic),
        )
    }

    /** The largest image; YouTube Music art can be asked for as a large square. */
    private fun art(images: List<Image>, square: Boolean): String? =
        images.maxByOrNull { it.height }?.url?.let { if (square) squareArt(it) else it }

    /** YouTube Music art URLs end in a size like "=w60-h60-l90-rj"; ask for a large square instead. */
    private fun squareArt(url: String): String =
        if ("googleusercontent.com" in url) url.replace(Regex("=w\\d+-h\\d+.*$"), "=w1080-h1080-l90-rj") else url

    internal fun idFor(videoId: String): Long = onlineId(videoId)
}
