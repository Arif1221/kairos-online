package com.skarif.kairos.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyImportTest {
    @Test fun links() {
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", SpotifyImport.parseLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", SpotifyImport.parseLink("Check this https://open.spotify.com/intl-en/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("album" to "4aawyAB9vmqN3uQ7FjRGTy", SpotifyImport.parseLink("spotify:album:4aawyAB9vmqN3uQ7FjRGTy"))
        assertNull(SpotifyImport.parseLink("https://open.spotify.com/track/1o9mAOmNm3wT2NdyGV1Ard"))
    }

    @Test fun parsesEmbedPage() {
        val html = javaClass.classLoader!!.getResource("spotify_embed.html")!!.readText()
        val s = SpotifyImport.parse(html)
        assertEquals("Today’s Top Hits", s.name)
        assertEquals(2, s.tracks.size)
        assertEquals(SpotifyImport.Track("Babydoll", "Jamie Miller", 178822), s.tracks[0])
        assertEquals("Pitbull", SpotifyImport.firstArtist(s.tracks[1].artist))
    }

    @Test fun matchKeys() {
        assertEquals("global warming", SpotifyImport.key("Global Warming (feat. Sensato)"))
        assertEquals("yesterday", SpotifyImport.key("Yesterday - Remastered 2009"))
        assertEquals("tum hi ho", SpotifyImport.key("Tum Hi Ho"))
    }
}
