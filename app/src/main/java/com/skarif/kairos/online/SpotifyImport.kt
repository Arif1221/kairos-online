package com.skarif.kairos.online

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a public Spotify playlist or album from its link, with no login: Spotify's embed page
 * (the one websites use to show a playlist) carries the song list as JSON. It holds the first 100
 * songs, and only titles and artists: the audio comes from this phone or YouTube Music.
 *
 * Unofficial: if Spotify changes that page, [parse] fails and the import says so.
 */
object SpotifyImport {
    data class Track(val title: String, val artist: String, val durationMs: Long)
    data class Source(val name: String, val owner: String?, val cover: String?, val tracks: List<Track>) {
        /** The embed stops at 100; a longer playlist comes in cut short. */
        val maybeCut get() = tracks.size >= 100
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

    /** Lower-case title without "(feat. …)", "- Remastered 2011" and the like, for matching. */
    fun key(title: String): String = title.lowercase()
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|live|mix|from).*$"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** First artist of "A, B & C". */
    fun firstArtist(artists: String) = artists.split(',', '&').first().trim()
}
