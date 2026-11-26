package com.skarif.kairos.online

/**
 * Turns raw YouTube video results into song results: tidier titles and an order that puts
 * official audio first and covers, reactions and jukeboxes last.
 *
 * Pure Kotlin so it is unit-tested without Android or the network.
 */
object SongMatch {
    /** A video as the search returned it. */
    data class Raw(val title: String, val uploader: String, val durationSec: Long, val views: Long = -1, val verified: Boolean = false)

    /** Words that mark something other than the song itself, unless the query asks for them. */
    private val OFF = listOf(
        "mashup", "reaction", "react", "cover", "tutorial", "lesson", "karaoke", "instrumental",
        "remix", "slowed", "reverb", "lofi", "lo-fi", "8d", "live", "jukebox", "nonstop", "non-stop",
        "status", "ringtone", "shorts", "chords", "guitar", "piano", "dance", "choreography",
        "making", "behind the scenes", "teaser", "trailer", "review", "explained", "podcast",
        "interview", "unplugged", "acoustic", "sped up", "bass boosted", "whatsapp", "edit", "edits",
        "fmv", "432hz", "version", "rendition", "fingerstyle", "saxophone", "violin", "flute", "club",
        "parody", "beginner", "mashups", "x",
    )

    /** Bracketed or trailing tags that are noise in a title. */
    private val NOISE = Regex(
        """\s*[(\[](?:[^)\]]*\b(?:official|video|audio|lyric|lyrics|full|hd|4k|hq|visualizer|song|music)\b[^)\]]*)[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /** "Full Video: Kesariya" → "Kesariya". */
    private val PREFIX = Regex("""^(?:full video|full song|lyrical|lyric video|official video|video|audio)\s*:\s*""", RegexOption.IGNORE_CASE)

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun mentions(text: String, word: String): Boolean =
        if (' ' in word || '-' in word) word in text else word in words(text)

    /** Higher is more likely the plain song the user wanted. */
    fun score(r: Raw, query: String): Double {
        val q = query.lowercase()
        val title = r.title.lowercase()
        var s = 0.0
        if (r.uploader.endsWith(" - Topic")) s += 3.0 // auto-generated official audio
        if ("official audio" in title) s += 1.0
        else if ("official" in title) s += 0.5
        if ("lyric" in title) s += 0.2
        if (r.verified) s += 0.5
        if (r.views > 0) s += 0.35 * kotlin.math.log10(r.views.toDouble()) // ~2.5 at 10M views
        // The artist's own channel, when the query names the artist.
        val up = words(r.uploader.removeSuffix(" - Topic"))
        if (up.isNotEmpty() && up.all { it in words(query) }) s += 1.0
        when (r.durationSec) {
            in 90..480 -> s += 1.0
            in 60..89, in 481..720 -> s += 0.0
            in 1..59, in 721..Long.MAX_VALUE -> s -= 1.5
            else -> s -= 2.0 // no duration: usually not a playable song
        }
        val off = OFF.count { mentions(title, it) && !mentions(q, it) }
        s -= 2.0 * off.coerceAtMost(2)
        val qWords = words(query)
        if (qWords.isNotEmpty()) {
            val hay = words(r.title + " " + r.uploader).toSet()
            s += 2.0 * qWords.count { it in hay } / qWords.size
        }
        return s
    }

    /**
     * Best first. Drops Shorts-length clips and hour-long compilations unless the query asks
     * for a mix; ties keep YouTube's order.
     */
    fun <T> rank(items: List<T>, query: String, raw: (T) -> Raw): List<T> {
        val wantsLong = listOf("mix", "jukebox", "full album", "nonstop", "non-stop").any { it in query.lowercase() }
        return items.withIndex()
            .filter { (_, it) ->
                val d = raw(it).durationSec
                d <= 0 || (d >= 45 && (wantsLong || d <= 900))
            }
            .sortedByDescending { (i, it) -> score(raw(it), query) - i * 0.01 }
            .map { it.value }
    }

    /**
     * A readable title and artist from a video's title and channel. "Arijit Singh - Kesariya
     * (Official Video) | Brahmāstra" by "T-Series" becomes "Kesariya" by "Arijit Singh".
     */
    fun clean(title: String, uploader: String, query: String = ""): Pair<String, String> {
        val topic = uploader.endsWith(" - Topic")
        val channel = uploader.removeSuffix(" - Topic").trim()
        if (topic) return title.trim() to channel // already clean
        var t = NOISE.replace(title, "").replace(Regex("""#\S+"""), "").trim()
        t = t.split('|', '｜').first().trim()
        t = PREFIX.replace(t, "").trim()
        var artist = channel
        val squashed = channel.lowercase().filter { it.isLetterOrDigit() }
        val dash = Regex("""\s+[-–—]\s+""").find(t)
        if (dash != null) {
            val left = t.substring(0, dash.range.first).trim()
            val right = t.substring(dash.range.last + 1).trim()
            // "Artist - Song" from the artist's own channel; from a label, the order is anyone's guess, so keep it whole.
            val q = words(query).toSet()
            fun hits(p: String) = words(p).count { it in q }
            // Lyric channels write "Artist - Song (Lyrics)": the part the query names is the song. When
            // the query is exactly one side, it could be the song or the artist, so don't guess.
            val rightIsQuery = words(right).toSet() == q
            val leftIsArtist = words(left).any { it.length > 3 && it in squashed } ||
                q.isNotEmpty() && !rightIsQuery && hits(right) > hits(left)
            if (left.isNotEmpty() && right.isNotEmpty() && leftIsArtist) {
                artist = left
                t = right
            }
        }
        t = t.removeSurrounding("\"").trim().ifEmpty { title.trim() }
        return t to artist.ifEmpty { "Unknown artist" }
    }
}
