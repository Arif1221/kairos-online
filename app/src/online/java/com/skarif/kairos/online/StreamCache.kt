package com.skarif.kairos.online

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Two caches. Songs streamed once are kept (up to [MAX_BYTES], least recently played go first),
 * so they replay without a connection and seeking doesn't re-download. Songs you download go to
 * a second cache that never evicts, in app storage (not the cache folder Android may clear).
 */
@OptIn(UnstableApi::class)
internal object StreamCache {
    private const val MAX_BYTES = 1024L * 1024 * 1024 // 1 GB

    @Volatile private var db: StandaloneDatabaseProvider? = null
    @Volatile private var streams: SimpleCache? = null
    @Volatile private var saved: SimpleCache? = null

    /** One database for both caches; each SimpleCache is one per folder per process, as it requires. */
    private fun db(context: Context) = db ?: synchronized(this) {
        db ?: StandaloneDatabaseProvider(context.applicationContext).also { db = it }
    }

    fun get(context: Context): SimpleCache = streams ?: synchronized(this) {
        streams ?: SimpleCache(File(context.applicationContext.cacheDir, "streams"), LeastRecentlyUsedCacheEvictor(MAX_BYTES), db(context))
            .also { streams = it }
    }

    fun downloads(context: Context): SimpleCache = saved ?: synchronized(this) {
        saved ?: SimpleCache(File(context.applicationContext.filesDir, "downloads"), NoOpCacheEvictor(), db(context))
            .also { saved = it }
    }

    /** Fully cached under [key]: the length is known and every byte is on disk. */
    fun isComplete(cache: Cache, key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    fun isComplete(context: Context, key: String): Boolean = isComplete(get(context), key)

    fun clear(context: Context) {
        val cache = get(context)
        cache.keys.toList().forEach { cache.removeResource(it) }
    }
}

/** Sends our "kairos-yt:" Uris to [online] (cached YouTube) and everything else, like local files, to [local]. */
@OptIn(UnstableApi::class)
internal class RoutingDataSource(
    private val scheme: String,
    private val local: DataSource,
    private val online: DataSource,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        online.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == scheme) online else local
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

    override fun getUri() = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try { current?.close() } finally { current = null }
    }
}
