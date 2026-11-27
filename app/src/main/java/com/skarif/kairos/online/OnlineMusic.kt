package com.skarif.kairos.online

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import com.skarif.kairos.data.StreamQuality
import com.skarif.kairos.data.db.SongEntity

/** Search tabs. */
enum class OnlineKind(val label: String) {
    SONGS("Songs"), VIDEOS("Videos"), ARTISTS("Artists"), PLAYLISTS("Albums & playlists")
}

/** One search or shelf result. */
sealed interface OnlineItem {
    val key: String

    data class Track(val song: SongEntity) : OnlineItem {
        override val key get() = "t:${song.fileName}"
    }

    /** [id] is the channel URL. */
    data class Artist(val id: String, val name: String, val art: String?, val detail: String?) : OnlineItem {
        override val key get() = "a:$id"
    }

    /** An album, playlist or mix; [id] is its URL. */
    data class Collection(
        val id: String,
        val title: String,
        val owner: String?,
        val art: String?,
        val isAlbum: Boolean,
        val count: Int,
    ) : OnlineItem {
        override val key get() = "c:$id"
    }
}

/** A page of results; pass it back to [OnlineMusic.search] to get the next one. */
class OnlinePage internal constructor(val items: List<OnlineItem>, internal val cursor: Any?) {
    val hasMore get() = cursor != null
}

class OnlineCollection(
    val title: String,
    val owner: String?,
    val art: String?,
    val isAlbum: Boolean,
    val songs: List<SongEntity>,
)

class OnlineArtist(
    val name: String,
    val art: String?,
    val songs: List<SongEntity>,
    val collections: List<OnlineItem.Collection>,
)

/** One line of the "Online check" in settings. */
class OnlineCheck(val name: String, val ok: Boolean, val detail: String)

/**
 * A streaming source, present only in the "online" build flavor.
 * Shared code reaches it through [Flavor.online], which is null in the Play Store build.
 *
 * Online songs are ordinary [SongEntity] rows with a negative id and volume [com.skarif.kairos.data.db.ONLINE_VOLUME],
 * so favourites, playlists, stats and the queue work for them unchanged.
 *
 * Every suspend function runs on IO itself and throws on network errors.
 */
interface OnlineMusic {
    /** Shown in the UI, e.g. "YouTube Music". */
    val name: String

    /** Gives the online backend an application context at startup before any cache/prefs call. */
    fun init(context: Context) {}

    /** Search-as-you-type completions. Never throws; empty on failure. */
    suspend fun suggestions(query: String): List<String>

    /** First page for [query], or the page after [after]. */
    suspend fun search(query: String, kind: OnlineKind, after: OnlinePage? = null): OnlinePage

    /** Songs of an album, playlist or mix. */
    suspend fun collection(id: String): OnlineCollection

    /** An artist's songs and releases, best effort. [name] helps when the channel page is empty. */
    suspend fun artist(id: String, name: String): OnlineArtist

    /** Songs that go well after [song] (YouTube's mix for it); [song] itself is excluded. */
    suspend fun radio(song: SongEntity): List<SongEntity>

    /** The best online match for a local song, or null. Used to seed radio and Explore from the library. */
    suspend fun findSong(title: String, artist: String): SongEntity?

    /** Tries each kind of request once and reports what works on this network. */
    suspend fun diagnose(): List<OnlineCheck>

    /** The Uri handed to the player; [dataSourceFactory] turns it into a real stream. */
    fun mediaUri(song: SongEntity): Uri

    /**
     * The YouTube video id that shows [song] as a video: its own id when it already is a video,
     * else the matching music video found by search; null when there is none. Throws on network errors.
     */
    suspend fun videoFor(song: SongEntity): String?

    /** Like [mediaUri], but the player gets the picture too. Streams the video's own sound. */
    fun videoUri(videoId: String): Uri

    /** Resolve and cache a small startup range for the current song's video while audio continues. */
    suspend fun prefetchVideo(context: Context, song: SongEntity, quality: StreamQuality) {}

    /** Resolve the next song's video match and stream metadata without downloading media bytes. */
    suspend fun prefetchVideoMetadata(song: SongEntity, quality: StreamQuality) {}

    /** The sound of [videoId] alone (cached like the song's), merged with the picture for video mode. */
    fun audioUri(videoId: String): Uri

    /**
     * Forgets the stream address saved for the song or video behind [uri], so the next open asks
     * YouTube for a fresh one. Addresses stop working when the network changes or they age out.
     */
    fun forgetStream(uri: Uri)

    /** True when the whole song is in the stream cache, so it plays without a connection. */
    fun isCached(context: Context, song: SongEntity): Boolean

    /** Bytes the stream cache uses on disk. */
    fun cacheBytes(context: Context): Long

    /** Empties the stream cache. Call off the main thread. */
    fun clearCache(context: Context)

    /**
     * Saves the whole song on the phone until [removeDownload]; unlike the stream cache, nothing
     * evicts it. Reuses bytes already streamed. [progress] runs on a background thread (0..1).
     * Cancelling the coroutine stops it; calling again resumes where it stopped.
     */
    suspend fun download(context: Context, song: SongEntity, quality: StreamQuality, progress: (Float) -> Unit)

    /** Downloads the matched video picture and its own audio for offline video playback. */
    suspend fun downloadVideo(context: Context, song: SongEntity, quality: StreamQuality, progress: (Float) -> Unit) {
        throw UnsupportedOperationException("Video downloads are not supported by this source")
    }

    fun isDownloaded(context: Context, song: SongEntity): Boolean

    /** Video ids ([SongEntity.fileName]) of every finished download. Call off the main thread. */
    fun downloadedIds(context: Context): Set<String>

    /** Video ids with a complete persistent picture download. */
    fun downloadedVideoIds(context: Context): Set<String> = emptySet()

    fun downloadBytes(context: Context): Long

    /** Deletes a download, finished or partial, by video id. Call off the main thread. */
    fun removeDownload(context: Context, videoId: String)

    /** Removes only a partially/fully saved video companion, preserving the song audio download. */
    fun removeVideoDownload(context: Context, songId: String) { removeDownload(context, songId) }

    /** How much room recently streamed songs may take; trims at once if it shrank. Call off the main thread. */
    fun setCacheLimit(context: Context, bytes: Long)

    /**
     * Fetches the song as an M4A (AAC) file into [into], untagged, for saving to the phone.
     * [progress] runs on a background thread (0..1). Cancelling the coroutine stops it.
     */
    suspend fun fetchM4a(song: SongEntity, into: java.io.File, progress: (Float) -> Unit)

    /**
     * Plays both local files and this source's Uris. [quality] is asked each time a stream is
     * opened, so a settings or network change applies from the next song.
     */
    fun dataSourceFactory(context: Context, quality: () -> StreamQuality): DataSource.Factory
}

/** The id an online song gets: a stable negative hash of its video id (MediaStore ids are positive). */
fun onlineId(videoId: String): Long {
    var h = -0x340d631b7bdddcdbL // 64-bit FNV-1a offset basis
    for (ch in videoId) {
        h = h xor ch.code.toLong()
        h *= 0x100000001b3L
    }
    return h or Long.MIN_VALUE
}
