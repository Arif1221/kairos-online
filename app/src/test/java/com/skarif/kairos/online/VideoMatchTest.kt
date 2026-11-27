package com.skarif.kairos.online

import com.skarif.kairos.online.VideoMatch.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoMatchTest {
    private fun pick(title: String, artist: String, dur: Long, vararg c: Candidate) = VideoMatch.pick(title, artist, dur, c.toList())

    @Test fun picksTheOfficialVideoOverTheAudioUpload() {
        val i = pick(
            "Kesariya", "Arijit Singh", 268,
            Candidate("Kesariya (Audio)", "Arijit Singh - Topic", 268, 5_000_000),
            Candidate("Kesariya - Official Video | Brahmastra", "Sony Music India", 288, 400_000_000),
        )
        assertEquals(1, i)
    }

    @Test fun skipsCoversAndLiveTakes() {
        assertNull(
            pick(
                "Kesariya", "Arijit Singh", 268,
                Candidate("Kesariya cover by someone", "Some Singer", 270),
                Candidate("Kesariya | Live at a concert", "Arijit Singh", 270),
            ),
        )
    }

    @Test fun rejectsAnotherSongWithTheSameWords() {
        assertNull(pick("Shape of You", "Ed Sheeran", 233, Candidate("Shape of You (Slowed + Reverb)", "Ed Sheeran", 233)))
    }

    @Test fun rejectsAVideoOfVeryDifferentLength() {
        assertNull(pick("Tum Hi Ho", "Arijit Singh", 262, Candidate("Tum Hi Ho Full Video", "T-Series", 600)))
    }

    @Test fun acceptsAnIntro() {
        assertEquals(0, pick("Tum Hi Ho", "Arijit Singh", 262, Candidate("Tum Hi Ho - Official Video", "Arijit Singh", 290)))
    }

    @Test fun liveSongTitleMayMentionLive() {
        assertEquals(0, pick("Live Forever", "Oasis", 276, Candidate("Oasis - Live Forever (Official Video)", "Oasis", 280)))
    }

    @Test fun nothingWithoutATitle() {
        assertNull(pick("", "x", 100, Candidate("anything", "x", 100)))
    }

    @Test fun artistBreaksTies() {
        val i = pick(
            "Home", "Edward Sharpe", 300,
            Candidate("Home - Official Video", "Someone Else", 300),
            Candidate("Home - Official Video", "Edward Sharpe & The Magnetic Zeros", 300),
        )
        assertEquals(1, i)
    }

    @Test fun ignoresFromTheFilmInTheSongTitle() {
        assertEquals(
            0,
            pick(
                "Kesariya (From \"Brahmastra\")", "Arijit Singh", 268,
                Candidate("Kesariya - Official Video | Ranbir Kapoor, Alia Bhatt", "Sony Music India", 285, 400_000_000),
            ),
        )
    }

    @Test fun ignoresFeatInTheSongTitle() {
        assertEquals(
            0,
            pick("Love Me Like You Do (feat. Someone)", "Ellie Goulding", 252, Candidate("Ellie Goulding - Love Me Like You Do (Official Video)", "EllieGouldingVEVO", 254)),
        )
    }

    @Test fun allowsALongerIntro() {
        assertEquals(0, pick("Tum Hi Ho", "Arijit Singh", 262, Candidate("Tum Hi Ho - Official Video", "T-Series", 320)))
    }

    @Test fun coreTitleStripsExtras() {
        assertEquals("Kesariya", VideoMatch.coreTitle("Kesariya (From \"Brahmastra\")"))
        assertEquals("Song", VideoMatch.coreTitle("Song [Remastered 2011]"))
        assertEquals("Song", VideoMatch.coreTitle("Song - From the film X"))
    }
}
