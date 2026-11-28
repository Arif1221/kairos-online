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

    @Test fun readsTokenAndHash() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"settings":{"session":{"accessToken":"tok123"}}}}}}</script></html>"""
        assertEquals("tok123", SpotifyImport.accessToken(html))
        assertNull(SpotifyImport.accessToken("<html></html>"))
        val h = "a".repeat(64)
        assertEquals(h, SpotifyImport.findPlaylistHash("x=n(\"fetchPlaylist\",\"query\",\"$h\",null)"))
        assertNull(SpotifyImport.findPlaylistHash("nothing here"))
    }

    @Test fun parsesPlaylistPage() {
        val body = """{"data":{"playlistV2":{"content":{"totalCount":250,"items":[
            {"itemV2":{"data":{"name":"Babydoll","trackDuration":{"totalMilliseconds":178822},
              "artists":{"items":[{"profile":{"name":"Jamie Miller"}},{"profile":{"name":"X"}}]}}}},
            {"itemV2":{"data":{"name":"Old","albumOfTrack":{"artists":{"items":[{"profile":{"name":"Pitbull"}}]}}}}},
            {"itemV2":{"data":{}}}]}}}}"""
        val page = SpotifyImport.parsePage(body)!!
        assertEquals(250, page.total)
        assertEquals(2, page.tracks.size)
        assertEquals(SpotifyImport.Track("Babydoll", "Jamie Miller, X", 178822), page.tracks[0])
        assertEquals("Pitbull", page.tracks[1].artist)
        assertNull(SpotifyImport.parsePage("not json"))
    }
}
