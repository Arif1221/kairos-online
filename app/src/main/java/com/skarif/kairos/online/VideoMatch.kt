package com.skarif.kairos.online

/**
 * Finds the music video that goes with a song. A song from YouTube Music is an auto-generated
 * "Topic" upload with only a still picture; the real video is a different video id, so it has
 * to be searched for and checked: same title, same artist, nearly the same length.
 *
 * Pure Kotlin so it is unit-tested without Android or the network.
 */
object VideoMatch {
    /** A search result as it came back. */
    data class Candidate(val title: String, val uploader: String, val durationSec: Long, val views: Long = -1)

    /** Things that are not the song's own video, unless the song's title says so itself. */
    private val OFF = listOf(
        "reaction", "react", "cover", "karaoke", "instrumental", "remix", "slowed", "reverb", "8d",
        "sped up", "bass boosted", "tutorial", "lesson", "mashup", "live", "shorts", "status",
        "ringtone", "whatsapp", "trailer", "teaser", "making", "behind the scenes", "fmv", "edit",
        "parody", "jukebox", "nonstop", "non-stop", "acoustic", "unplugged",
    )

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * The song's own name without the extras a streaming title carries: "(From \"Brahmastra\")",
     * "(feat. X)", "[Remastered]", "- From the film ...". A video's title rarely repeats those.
     */
    internal fun coreTitle(title: String): String {
        var t = title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), " ")
        t = t.replace(Regex("""\s+[-–—]\s+(from|feat\.?|ft\.?|with|remaster|remastered|original|theme)\b.*$""", RegexOption.IGNORE_CASE), " ")
        t = t.trim()
        return t.ifEmpty { title.trim() }
    }

    private fun mentions(text: String, word: String): Boolean =
        if (' ' in word || '-' in word) word in text else word in words(text)

    /**
     * Index of the best match in [candidates], or null when none is good enough (better no
     * video than the wrong one). [durationSec] of 0 means unknown.
     */
    fun pick(title: String, artist: String, durationSec: Long, candidates: List<Candidate>, relaxed: Boolean = false): Int? {
        val want = words(coreTitle(title)).filter { it.length > 1 }
        if (want.isEmpty()) return null
        val artistWords = words(artist).filter { it.length > 2 }
        val songTitle = title.lowercase() // the full title: "(Live)" or "(Remix)" in it makes those versions fine
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        candidates.forEachIndexed { i, c ->
            val t = c.title.lowercase()
            val hay = words(c.title + " " + c.uploader).toSet()
            // Every word of the song's title must show up (allow one miss in a long title).
            val hits = want.count { it in hay }
            // [relaxed] (second pass, only when the strict one found nothing) allows a few more misses:
            // a video titled "Song | Official Music Video" with a different spelling still counts.
            val allowedMiss = if (relaxed) maxOf(1, want.size / 3) else if (want.size >= 3) 1 else 0
            if (hits < want.size - allowedMiss || hits == 0) return@forEachIndexed
            // Not a cover, remix, live take... unless the song itself is one.
            if (OFF.any { mentions(t, it) && !mentions(songTitle, it) }) return@forEachIndexed
            var score = 3.0 * hits / want.size
            if (durationSec > 0 && c.durationSec > 0) {
                val diff = kotlin.math.abs(c.durationSec - durationSec)
                // A video has an intro or an outro now and then; a different song is far off.
                val limit = if (relaxed) maxOf(75L, durationSec / 3) else maxOf(45L, durationSec / 4)
                if (diff > limit) return@forEachIndexed
                score -= diff / 45.0
            } else if (c.durationSec <= 0) {
                score -= 1.5 // no duration: a stream or a premiere, usually not playable as a clip
            }
            if (artistWords.isNotEmpty()) score += 2.0 * artistWords.count { it in hay } / artistWords.size
            if ("official" in t) score += 1.0
            // A "video" that is only the audio over a picture is what the Topic upload already is.
            if (mentions(t, "audio") && !mentions(t, "video")) score -= 1.5
            if (mentions(t, "lyric") || mentions(t, "lyrics")) score -= 0.5
            if (c.views > 0) score += 0.3 * kotlin.math.log10(c.views.toDouble())
            if (score > bestScore) { bestScore = score; best = i }
        }
        return if (best >= 0 && bestScore >= (if (relaxed) 1.4 else 2.0)) best else null
    }
}
