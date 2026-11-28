package com.skarif.kairos.online

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reads a public Spotify playlist or album from its link, with no login: Spotify's embed page
 * (the one websites use to show a playlist) carries the song list as JSON. It holds the first 100
 * songs, and only titles and artists: the audio comes from this phone or YouTube Music.
 *
 * Longer playlists: the same page also carries a short-lived anonymous token, which the web player's
 * own playlist query ([fetchAll]) accepts, 100 songs a page, until the whole list is read. If any
 * step of that fails (Spotify rotates the query hash now and then) the first 100 are used and
 * [Source.maybeCut] stays true, so the import never gets worse than before.
 *
 * Unofficial: if Spotify changes that page, [parse] fails and the import says so.
 */
object SpotifyImport {
    data class Track(val title: String, val artist: String, val durationMs: Long)
    data class Source(
        val name: String, val owner: String?, val cover: String?, val tracks: List<Track>,
        /** True once the whole list was read past the embed's 100-song limit. */
        val full: Boolean = false,
    ) {
        /** The embed stops at 100; a longer playlist comes in cut short unless [full]. */
        val maybeCut get() = !full && tracks.size >= 100
    }

    private val LINK = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?(?:embed/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{22})""")

    /** ("playlist" or "album", id) from a link or URI, or null if it isn't one. */
    fun parseLink(text: String): Pair<String, String>? = LINK.find(text.trim())?.let { it.groupValues[1] to it.groupValues[2] }

    fun embedUrl(kind: String, id: String) = "https://open.spotify.com/embed/$kind/$id"

    /** Throws IllegalStateException when the page isn't what it used to be. */
    fun parse(html: String): Source {
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("Spotify's page has changed (no data)")
        val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: error("Spotify's page has changed (no playlist)")
        val list = entity.optJSONArray("trackList") ?: JSONArray()
        val tracks = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").trim()
            if (title.isEmpty()) null else Track(title, t.optString("subtitle").trim(), t.optLong("duration"))
        }
        val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
        return Source(
            name = entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Spotify playlist" },
            owner = entity.optString("subtitle").takeIf { it.isNotBlank() },
            cover = cover,
            tracks = tracks,
        )
    }


    // ---- Whole playlists (past the embed's 100) --------------------------------------------------

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    private const val PAGE = 100
    private const val MAX_PAGES = 100

    /** The persisted-query hash of the web player's playlist query; read from its script, kept for the session. */
    @Volatile private var cachedHash: String? = null

    /** The anonymous access token carried by the embed page, or null. */
    fun accessToken(html: String): String? {
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        return runCatching {
            JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
                ?.optJSONObject("settings")?.optJSONObject("session")?.optString("accessToken")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** The playlist query's sha256 hash inside a web-player script, or null. */
    fun findPlaylistHash(script: String): String? =
        Regex("""["']fetchPlaylist["']\s*,\s*["']query["']\s*,\s*["']([a-f0-9]{64})["']""").find(script)?.groupValues?.get(1)

    class Page(val tracks: List<Track>, val total: Int)

    /** One page of the playlist query's answer. Tolerant: anything missing is skipped, never thrown. */
    fun parsePage(body: String): Page? {
        val content = runCatching {
            JSONObject(body).optJSONObject("data")?.optJSONObject("playlistV2")?.optJSONObject("content")
        }.getOrNull() ?: return null
        val items = content.optJSONArray("items") ?: JSONArray()
        val tracks = (0 until items.length()).mapNotNull { i ->
            val d = items.optJSONObject(i)?.optJSONObject("itemV2")?.optJSONObject("data") ?: return@mapNotNull null
            val title = d.optString("name").trim()
            if (title.isEmpty()) return@mapNotNull null
            val artistItems = d.optJSONObject("artists")?.optJSONArray("items")
                ?: d.optJSONObject("albumOfTrack")?.optJSONObject("artists")?.optJSONArray("items")
            val artists = (0 until (artistItems?.length() ?: 0)).mapNotNull { k ->
                artistItems?.optJSONObject(k)?.optJSONObject("profile")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            }.joinToString(", ")
            val ms = d.optJSONObject("trackDuration")?.optLong("totalMilliseconds") ?: 0L
            Track(title, artists, ms)
        }
        return Page(tracks, content.optInt("totalCount", tracks.size))
    }

    private fun http(url: String, token: String? = null, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("User-Agent", UA)
            if (token != null) {
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("app-platform", "WebPlayer")
            }
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    /** Finds the query hash in the web player's own scripts (it changes whenever Spotify ships a new build). */
    private fun discoverHash(): String? {
        val home = http("https://open.spotify.com/")
        val scripts = Regex("""<script[^>]+src="([^"]+\.js)"""").findAll(home).map { it.groupValues[1] }
            .filter { "web-player" in it || "webplayer" in it }.distinct().take(8).toList()
        for (src in scripts) {
            val url = if (src.startsWith("http")) src else "https://open.spotify.com$src"
            val hash = runCatching { findPlaylistHash(http(url)) }.getOrNull()
            if (hash != null) return hash
        }
        return null
    }

    private fun queryPage(token: String, hash: String, id: String, offset: Int): String {
        val vars = JSONObject().put("uri", "spotify:playlist:$id").put("offset", offset).put("limit", PAGE)
            .put("enableWatchFeedEntrypoint", false)
        val ext = JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash))
        val post = JSONObject().put("variables", vars).put("operationName", "fetchPlaylist").put("extensions", ext).toString()
        // The newer endpoint takes a POST; the older one a GET. Either answers the same query.
        return runCatching { http("https://api-partner.spotify.com/pathfinder/v2/query", token, post) }.getOrElse {
            fun enc(x: String) = URLEncoder.encode(x, "UTF-8")
            http(
                "https://api-partner.spotify.com/pathfinder/v1/query?operationName=fetchPlaylist" +
                    "&variables=${enc(vars.toString())}&extensions=${enc(ext.toString())}",
                token,
            )
        }
    }

    /**
     * The whole playlist behind [first] (what the embed page gave). Blocking; call it off the main thread.
     * Any failure returns [first] unchanged, so a longer playlist still comes in as its first 100.
     */
    fun fetchAll(id: String, embedHtml: String, first: Source, onProgress: (Int, Int) -> Unit = { _, _ -> }): Source {
        if (first.tracks.size < PAGE) return first.copy(full = true) // the embed held it all
        return try {
            val token = accessToken(embedHtml) ?: return first
            var hash = cachedHash ?: discoverHash()?.also { cachedHash = it } ?: return first
            val all = ArrayList<Track>()
            var total = Int.MAX_VALUE
            var offset = 0
            var pages = 0
            var retried = false
            while (offset < total && pages < MAX_PAGES) {
                val page = try {
                    parsePage(queryPage(token, hash, id, offset))
                } catch (e: Exception) {
                    // A stale hash: look it up again once, then give up.
                    if (retried || pages > 0) throw e
                    retried = true
                    cachedHash = null
                    hash = discoverHash()?.also { cachedHash = it } ?: throw e
                    parsePage(queryPage(token, hash, id, offset))
                } ?: break
                if (page.tracks.isEmpty() && page.total <= offset) break
                total = page.total
                all += page.tracks
                offset += PAGE
                pages++
                onProgress(all.size, total)
                Thread.sleep(120)
            }
            // Only trust it if it really went past the embed's list.
            if (all.size > first.tracks.size) first.copy(tracks = all, full = all.size >= total) else first
        } catch (e: Exception) {
            first
        }
    }

    /** Lower-case title without "(feat. …)", "- Remastered 2011" and the like, for matching. */
    fun key(title: String): String = title.lowercase()
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|live|mix|from).*$"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** First artist of "A, B & C". */
    fun firstArtist(artists: String) = artists.split(',', '&').first().trim()
}
