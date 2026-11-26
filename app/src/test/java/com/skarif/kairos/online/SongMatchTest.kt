package com.skarif.kairos.online

import com.skarif.kairos.online.SongMatch.Raw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMatchTest {
    private fun rank(q: String, vararg r: Raw) = SongMatch.rank(r.toList(), q) { it }.map { it.title }

    @Test fun officialAudioBeatsCoversAndReactions() {
        val r = rank(
            "kesariya",
            Raw("Kesariya Reaction!!", "Some Reactor", 600),
            Raw("Kesariya cover by me", "Singer", 210),
            Raw("Kesariya", "Arijit Singh - Topic", 268),
            Raw("Kesariya - Brahmāstra | Official Video", "T-Series", 270),
        )
        assertEquals("Kesariya", r[0])
        assertEquals("Kesariya - Brahmāstra | Official Video", r[1])
        assertEquals(setOf("Kesariya cover by me", "Kesariya Reaction!!"), r.takeLast(2).toSet())
    }

    @Test fun queryWordsAreNotPenalised() {
        val r = rank("kesariya lofi", Raw("Kesariya", "Arijit Singh - Topic", 268), Raw("Kesariya Lofi (Slowed)", "Lofi Hub", 250))
        // the lofi version is what was asked for, so only "slowed" counts against it
        assertTrue(SongMatch.score(Raw("Kesariya Lofi (Slowed)", "Lofi Hub", 250), "kesariya lofi") >
            SongMatch.score(Raw("Kesariya Lofi (Slowed)", "Lofi Hub", 250), "kesariya"))
        assertEquals(2, r.size)
    }

    @Test fun shortsAndCompilationsAreDropped() {
        val r = rank("arijit", Raw("clip", "x", 20), Raw("Arijit 2 hour jukebox", "x", 7200), Raw("Song", "Arijit Singh - Topic", 200))
        assertEquals(listOf("Song"), r)
        val mix = rank("arijit mix", Raw("Arijit 2 hour mix", "x", 7200))
        assertEquals(1, mix.size)
    }

    @Test fun cleanTitles() {
        assertEquals("Kesariya" to "Arijit Singh", SongMatch.clean("Kesariya", "Arijit Singh - Topic"))
        assertEquals("Blinding Lights" to "The Weeknd", SongMatch.clean("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals("Kesariya - Brahmāstra" to "T-Series", SongMatch.clean("Full Video: Kesariya - Brahmāstra | Ranbir | Alia", "T-Series"))
        val (t, _) = SongMatch.clean("Tum Hi Ho [Official Lyric Video]", "Sony Music India")
        assertEquals("Tum Hi Ho", t)
        assertFalse(SongMatch.clean("(Official Video)", "x").first.isEmpty())
        // Lyric channel: the side the query names is the song.
        assertEquals("Kesariya" to "Arijit Singh", SongMatch.clean("Arijit Singh - Kesariya (Lyrics)", "7Echo", "kesariya song"))
        // The query is exactly one side: song or artist? Leave the title whole.
        assertEquals("Sanam Re - Arijit Singh" to "Music Hub", SongMatch.clean("Sanam Re - Arijit Singh", "Music Hub", "arijit singh"))
    }
}
