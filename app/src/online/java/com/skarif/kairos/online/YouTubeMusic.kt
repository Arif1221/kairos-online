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
import kotlinx.coroutines.coroutineScope
import com.skarif.kairos.data.StreamQuality
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

    /** Stream URLs expire after about 6 hours; refresh well before that. */
    private const val URL_TTL_MS = 3 * 3600_000L

    override val name = "YouTube Music"

    private class Resolved(val url: String, val at: Long)
    private val streamUrls = ConcurrentHashMap<String, Resolved>()

    /**
     * The quality each song is played at, fixed the first time it is opened in this process.
     * Seeks reopen the stream at a byte offset, so switching quality mid-song would corrupt it.
     */
    private val pinned = ConcurrentHashMap<String, StreamQuality>()

    private val init by lazy {
        val locale = Locale.getDefault()
        NewPipe.init(NewPipeDownloader, Localization.fromLocale(locale), ContentCountry(locale.country.ifBlank { "US" }))
    }

    /** YouTube sometimes fails a request that works a moment later. */
    private inline fun <T> retry(times: Int = 2, block: () -> T): T {
        var last: Exception? = null
        repeat(times) { attempt ->
            try { return block() } catch (e: Exception) {
                last = e
                if (attempt < times - 1) Thread.sleep(600)
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
    }
    private val searches = Lru<OnlinePage>(40, 10 * 60_000L)
    private val collections = Lru<OnlineCollection>(30, 30 * 60_000L)
    private val radios = Lru<List<SongEntity>>(20, 30 * 60_000L)
    private val found = Lru<SongEntity?>(200, 24 * 3600_000L)

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
            OnlineKind.PLAYLISTS -> {
                val albums = runCatching { first(q, MUSIC_ALBUMS, kind) }.getOrNull()
                val lists = first(q, MUSIC_PLAYLISTS, kind)
                val merged = (albums?.items.orEmpty() + lists.items).distinctBy { it.key }
                    .sortedByDescending { (it as? OnlineItem.Collection)?.isAlbum == true }
                OnlinePage(merged, lists.cursor)
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

    private fun cacheKey(videoId: String, q: StreamQuality) = "yt:$videoId:$q"

    override fun isCached(context: Context, song: SongEntity): Boolean =
        isDownloaded(context, song) || StreamQuality.entries.any { StreamCache.isComplete(context, cacheKey(song.fileName, it)) }

    override fun cacheBytes(context: Context): Long = StreamCache.get(context).cacheSpace

    override fun clearCache(context: Context) {
        StreamCache.clear(context)
        pinned.clear()
    }

    // ---- Downloads ---------------------------------------------------------------------------

    override fun isDownloaded(context: Context, song: SongEntity): Boolean {
        val saved = StreamCache.downloads(context)
        return StreamQuality.entries.any { StreamCache.isComplete(saved, cacheKey(song.fileName, it)) }
    }

    override fun downloadedIds(context: Context): Set<String> {
        val saved = StreamCache.downloads(context)
        return saved.keys.filter { StreamCache.isComplete(saved, it) }.mapNotNullTo(HashSet()) { it.split(':').getOrNull(1) }
    }

    override fun downloadBytes(context: Context): Long = StreamCache.downloads(context).cacheSpace

    override fun removeDownload(context: Context, videoId: String) {
        val saved = StreamCache.downloads(context)
        StreamQuality.entries.forEach { saved.removeResource(cacheKey(videoId, it)) }
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
                ?: StreamQuality.entries.reversed().firstOrNull { StreamCache.isComplete(app, cacheKey(id, it)) }
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

    /** Which quality to use for [videoId]: already pinned, else one downloaded or fully cached (plays offline), else the setting. */
    private fun qualityFor(context: Context, videoId: String, setting: () -> StreamQuality): StreamQuality =
        pinned.getOrPut(videoId) {
            val saved = StreamCache.downloads(context)
            StreamQuality.entries.reversed().firstOrNull { StreamCache.isComplete(saved, cacheKey(videoId, it)) }
                ?: StreamQuality.entries.reversed().firstOrNull { StreamCache.isComplete(context, cacheKey(videoId, it)) }
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
        val local = DefaultDataSource.Factory(app)
        return DataSource.Factory { RoutingDataSource(SCHEME, local.createDataSource(), cached.createDataSource()) }
    }

    /** Called on the player's loading thread, so blocking here is fine. */
    private fun streamUrl(videoId: String, quality: StreamQuality): String {
        val key = "$videoId|$quality"
        streamUrls[key]?.let { if (System.currentTimeMillis() - it.at < URL_TTL_MS) return it.url }
        try {
            init
            val info = retry { StreamInfo.getInfo(ServiceList.YouTube, watchUrl(videoId)) }
            // Audio-only streams; video streams are never fetched, so videos cost the same data as songs.
            val streams = info.audioStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
                .sortedBy(::kbps)
            if (streams.isEmpty()) throw IOException("No playable audio for $videoId")
            val pick = when (quality) {
                StreamQuality.LOW -> streams.first()
                StreamQuality.HIGH -> streams.last()
                StreamQuality.NORMAL -> streams.lastOrNull { kbps(it) in 1..140 } ?: streams.first()
            }
            return pick.content.also { streamUrls[key] = Resolved(it, System.currentTimeMillis()) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Couldn't load $videoId", e) // the player only reports IOExceptions as load errors
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
            album = if (topic) this@YouTubeMusic.name else "YouTube",
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
