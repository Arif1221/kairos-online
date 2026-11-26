package com.skarif.kairos.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.skarif.kairos.data.db.SongEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Lyrics from LRCLIB (lrclib.net): free, no account, no key. Only used when the user turns on
 * "Fetch lyrics online"; the request carries the song's title, artist, album and length, nothing else.
 *
 * Each answer is saved in the app's private storage, so a song is looked up once and its lyrics
 * then work offline. A miss is remembered for a week so a song with no lyrics isn't asked about
 * every time it plays.
 *
 * Blocking calls: run on Dispatchers.IO.
 */
class OnlineLyrics(context: Context, private val userAgent: String) {
    /** What LRCLIB has for a song. Either may be missing. */
    data class Found(val synced: String?, val plain: String?)

    private val dir = File(context.filesDir, "lyrics_online").apply { mkdirs() }

    /** How a fresh lookup asked for from the lyrics page went. */
    sealed interface Fetch {
        data class Got(val found: Found) : Fetch
        data object NotFound : Fetch
        data object Failed : Fetch
    }

    fun lookup(song: SongEntity): Found? {
        if (song.title.isBlank()) return null
        val file = File(dir, "${song.id}.json")
        readCache(file)?.let { return it.takeIf { f -> f.synced != null || f.plain != null } }

        val found = try {
            get(song) ?: search(song)
        } catch (e: Exception) {
            // Offline, timeout, server trouble: say nothing and don't cache, so it's tried again later.
            Log.w(TAG, "Lookup failed for ${song.id}", e)
            return null
        }
        writeCache(file, found)
        return found
    }

    /** Asks LRCLIB again, ignoring anything saved (the lyrics page's download button). */
    fun fetchFresh(song: SongEntity): Fetch {
        if (song.title.isBlank()) return Fetch.NotFound
        val file = File(dir, "${song.id}.json")
        val found = try {
            get(song) ?: search(song)
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for ${song.id}", e)
            return Fetch.Failed
        }
        writeCache(file, found)
        return if (found == null) Fetch.NotFound else Fetch.Got(found)
    }

    /** What is saved for a song, without asking the network. */
    fun saved(songId: Long): Found? =
        readCache(File(dir, "$songId.json"))?.takeIf { it.synced != null || it.plain != null }

    /**
     * Lyrics downloaded from the lyrics page are pinned: they're shown in the chosen form even
     * over the song's own lyrics and with "Fetch lyrics online" off, until removed.
     */
    fun pinned(songId: Long): OnlineLyricsType? =
        runCatching { OnlineLyricsType.valueOf(File(dir, "$songId.pin").readText().trim()) }.getOrNull()

    fun pin(songId: Long, type: OnlineLyricsType) {
        runCatching { File(dir, "$songId.pin").writeText(type.name) }
    }

    /** Drops a song's downloaded lyrics and remembers it as a miss, so auto-fetch leaves it alone for a while. */
    fun remove(songId: Long) {
        File(dir, "$songId.pin").delete()
        writeCache(File(dir, "$songId.json"), null)
    }

    /** Exact match on title, artist, album and duration (LRCLIB allows ±2 s). */
    private fun get(song: SongEntity): Found? {
        // LRCLIB refuses lengths outside 1 s..1 h (400); go straight to search for those.
        if (song.durationMs / 1000 !in 1L..3600L) return null
        val url = Uri.parse("$BASE/get").buildUpon()
            .appendQueryParameter("track_name", song.title)
            .appendQueryParameter("artist_name", song.artist)
            .appendQueryParameter("album_name", song.album)
            .appendQueryParameter("duration", (song.durationMs / 1000).toString())
            .build().toString()
        val body = request(url) ?: return null
        return parse(JSONObject(body))
    }

    /** Looser: title and artist, then the closest length within 3 s; synced results first. */
    private fun search(song: SongEntity): Found? {
        val url = Uri.parse("$BASE/search").buildUpon()
            .appendQueryParameter("track_name", song.title)
            .appendQueryParameter("artist_name", song.artist)
            .build().toString()
        val arr = JSONArray(request(url) ?: return null)
        val secs = song.durationMs / 1000.0
        val candidates = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { abs(it.optDouble("duration", -100.0) - secs) <= 3.0 }
            .sortedWith(compareBy({ it.isNull("syncedLyrics") }, { abs(it.optDouble("duration") - secs) }))
        return candidates.firstNotNullOfOrNull { parse(it) }
    }

    private fun parse(o: JSONObject): Found? {
        if (o.optBoolean("instrumental")) return null
        val synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() }
        val plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() }
        return if (synced == null && plain == null) null else Found(synced, plain)
    }

    /** The body on 200, null on 404 or 400 (not found or not askable). Throws on anything else so the miss isn't cached. */
    private fun request(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", userAgent) // LRCLIB asks clients to identify themselves
            c.setRequestProperty("Accept", "application/json")
            return when (c.responseCode) {
                200 -> c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                404, 400 -> null // not found / a title LRCLIB can't take
                else -> error("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun readCache(file: File): Found? {
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            if (o.optBoolean("miss")) {
                if (System.currentTimeMillis() - o.optLong("at") > MISS_TTL_MS) null else Found(null, null)
            } else {
                Found(o.optString("synced").ifEmpty { null }, o.optString("plain").ifEmpty { null })
            }
        }.getOrNull()
    }

    private fun writeCache(file: File, found: Found?) {
        runCatching {
            val o = if (found == null) JSONObject().put("miss", true).put("at", System.currentTimeMillis())
            else JSONObject().put("synced", found.synced ?: "").put("plain", found.plain ?: "")
            file.writeText(o.toString())
        }
    }

    /** Forgets every saved answer (Settings > Lyrics > Clear). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "OnlineLyrics"
        const val BASE = "https://lrclib.net/api"
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
