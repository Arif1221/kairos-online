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
import androidx.media3.common.C
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet
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
    /** Set from Settings > Downloads & storage; applies at once (trimming if it shrank). */
    @Volatile var maxBytes = 1024L * 1024 * 1024
    private val evictor = AdjustableLruEvictor { maxBytes }

    fun setLimit(context: Context, bytes: Long) {
        maxBytes = bytes
        val cache = get(context)
        synchronized(cache) { evictor.trim(cache) } // SimpleCache's own lock, as its callbacks hold
    }

    @Volatile private var db: StandaloneDatabaseProvider? = null
    @Volatile private var streams: SimpleCache? = null
    @Volatile private var saved: SimpleCache? = null

    /** One database for both caches; each SimpleCache is one per folder per process, as it requires. */
    private fun db(context: Context) = db ?: synchronized(this) {
        db ?: StandaloneDatabaseProvider(context.applicationContext).also { db = it }
    }

    fun get(context: Context): SimpleCache = streams ?: synchronized(this) {
        streams ?: SimpleCache(File(context.applicationContext.cacheDir, "streams"), evictor, db(context))
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

/**
 * Least recently played go first, like Media3's LeastRecentlyUsedCacheEvictor, but the limit
 * can change while the app runs (that one fixes it at construction).
 */
@OptIn(UnstableApi::class)
internal class AdjustableLruEvictor(private val max: () -> Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it })
    private var size = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun trim(cache: Cache) = evict(cache, 0)

    private fun evict(cache: Cache, required: Long) {
        while (size + required > max() && spans.isNotEmpty()) cache.removeSpan(spans.first())
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
