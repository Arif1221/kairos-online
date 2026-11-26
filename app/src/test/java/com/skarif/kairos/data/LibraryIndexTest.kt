package com.skarif.kairos.data

import com.skarif.kairos.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LibraryIndexTest {

    private var nextId = 1L
    private fun song(
        title: String, artist: String = "A", album: String = "Al", albumId: Long = 1, folder: String = "Music/",
        genre: String = "", track: Int = 0,
    ) = SongEntity(
        id = nextId++, title = title, artist = artist, album = album, albumId = albumId, durationMs = 200_000,
        track = track, year = 0, folder = folder, fileName = "$title.mp3", volume = "external_primary",
        mimeType = "audio/mpeg", sizeBytes = 1, dateAddedSec = 0, bitrate = 320, genre = genre,
    )

    @Test
    fun normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("beyonce", normalize("  Beyoncé! "))
        assertEquals("আমার সোনার বাংলা", normalize("আমার সোনার বাংলা"))
    }

    @Test
    fun searchNeedsEveryWordInAnyOrder() {
        val a = song("Shape of You", "Ed Sheeran", "Divide")
        val b = song("Perfect", "Ed Sheeran", "Divide")
        val c = song("Shape", "Someone else")
        val r = searchSongs(listOf(a, b, c), "sheeran shape")
        assertEquals(listOf(a), r)
    }

    @Test
    fun searchRanksTitleMatchesAboveArtistMatches() {
        val byArtist = song("Hello", "Love Club")
        val byTitle = song("Love Story", "Taylor Swift")
        assertEquals(listOf(byTitle, byArtist), searchSongs(listOf(byArtist, byTitle), "love"))
    }

    @Test
    fun albumSplitAcrossIdsIsMerged() {
        val s1 = song("One", "Band", "Night Visions", albumId = 10, folder = "Music/CD1/")
        val s2 = song("Two", "Band", "night visions ", albumId = 11, folder = "Music/CD2/")
        val g = groupAlbums(listOf(s1, s2))
        assertEquals(1, g.size)
        assertEquals(setOf(10L, 11L), g.single().albumIds)
    }

    @Test
    fun sameAlbumNameByDifferentArtistsStaysSeparate() {
        val a = song("x", "Artist One", "Greatest Hits", albumId = 1)
        val b = song("y", "Artist Two", "Greatest Hits", albumId = 2)
        assertEquals(2, groupAlbums(listOf(a, b)).size)
    }

    @Test
    fun compilationStaysOneAlbum() {
        val songs = (1..6).map { song("t$it", "Artist $it", "Now 99", albumId = if (it % 2 == 0) 5 else 6) }
        val g = groupAlbums(songs)
        assertEquals(1, g.size)
        assertEquals("Various artists", g.single().artist)
    }

    @Test
    fun untaggedSongsGroupByFolder() {
        val a = song("a", album = UNKNOWN_ALBUM, albumId = 1, folder = "Download/")
        val b = song("b", album = UNKNOWN_ALBUM, albumId = 1, folder = "WhatsApp/")
        // MediaStore gave them the same id; folders still differ, but they arrive as one id group.
        val c = song("c", album = UNKNOWN_ALBUM, albumId = 2, folder = "Recordings/")
        assertEquals(2, groupAlbums(listOf(a, b, c)).size)
    }

    @Test
    fun genresSplitAndMergeSpellings() {
        val a = song("a", genre = "Hip-Hop; Pop")
        val b = song("b", genre = "hip hop")
        val c = song("c", genre = "R&B")
        val g = groupGenres(listOf(a, b, c)).associate { genreKey(it.name) to it.songs.size }
        assertEquals(2, g["hiphop"])
        assertEquals(1, g["pop"])
        assertEquals(1, g["r&b"])
    }

    @Test
    fun spreadShuffleKeepsEverySongAndSpreadsArtists() {
        val songs = (1..10).map { song("a$it", "Alpha") } + (1..10).map { song("b$it", "Beta") } + (1..10).map { song("c$it", "Gamma") }
        repeat(20) { seed ->
            val r = spreadShuffle(songs, Random(seed))
            assertEquals(songs.toSet(), r.toSet())
            // A plain shuffle of 3 equal artists often gives runs of 4+; spreading should not.
            val longestRun = r.zipWithNext().fold(1 to 1) { (best, cur), (x, y) ->
                val n = if (x.artist == y.artist) cur + 1 else 1
                maxOf(best, n) to n
            }.first
            assertTrue("run of $longestRun with seed $seed", longestRun <= 3)
        }
    }

    @Test
    fun weightedSamplePrefersHeavyItems() {
        val items = (0 until 100).toList()
        var heavyPicked = 0
        repeat(200) { seed ->
            val picked = weightedSample(items, 10, Random(seed)) { if (it < 10) 20.0 else 1.0 }
            heavyPicked += picked.count { it < 10 }
        }
        // Uniform would give about 1 heavy item in 10; weighting should give far more.
        assertTrue("heavy picked $heavyPicked", heavyPicked > 200 * 5)
    }

    private val noTaste = Taste(emptyMap(), emptyMap(), emptySet(), emptyMap())

    @Test
    fun similarSongsPreferSameArtistAndGenre() {
        val seed = song("seed", "Arijit Singh", genre = "Bollywood", albumId = 100)
        val sameArtist = (1..5).map { song("a$it", "Arijit Singh", albumId = 200L + it) }
        val sameGenre = (1..5).map { song("g$it", "Someone $it", genre = "Bollywood", albumId = 300L + it) }
        val other = (1..40).map { song("o$it", "Metal Band $it", genre = "Metal", albumId = 400L + it) }
        val all = listOf(seed) + sameArtist + sameGenre + other
        val r = similarSongs(listOf(seed), all, noTaste, 10, Random(1))
        assertEquals(10, r.size)
        assertTrue(seed !in r)
        // Every same-genre song (all different artists) makes it; the seed's artist is capped at a
        // quarter of the list (2 of 10), so one artist can't take over a radio.
        assertTrue(r.containsAll(sameGenre))
        assertEquals(2, r.count { it in sameArtist })
    }

    @Test
    fun similarSongsCapAnAlbumButNotUnknownAlbum() {
        val seed = song("seed", "Band", albumId = 1)
        val sameAlbum = (1..6).map { song("al$it", "Band $it", genre = "Rock", albumId = 7) }
        val loose = (1..6).map { song("l$it", "Loose $it", album = UNKNOWN_ALBUM, albumId = 9, genre = "Rock") }
        val seedR = song("seedR", "Band", genre = "Rock", albumId = 1)
        val r = similarSongs(listOf(seed, seedR), listOf(seed, seedR) + sameAlbum + loose, noTaste, 8, Random(3))
        assertTrue(r.count { it in sameAlbum } <= 2)
        // Untagged files share one "Unknown album" id, and aren't treated as one album.
        assertTrue(r.count { it in loose } > 2)
    }

    @Test
    fun playedTogetherLinksNeighboursInASitting() {
        val t0 = 1_000_000_000L
        val links = playedTogether(listOf(1L to t0, 2L to t0 + 60_000, 3L to t0 + 120_000, 4L to t0 + 5 * 3_600_000))
        assertEquals(1f, links[1]?.get(2))
        assertTrue((links[1]?.get(3) ?: 0f) > 0f)
        // Hours later is another sitting: no link.
        assertEquals(null, links[3]?.get(4))
    }

    @Test
    fun similarSongsFavourWhatYouPlayTogether() {
        val seed = song("seed", "X", genre = "Pop", albumId = 1)
        val buddy = song("buddy", "Unrelated", genre = "Jazz", albumId = 2)
        val others = (1..30).map { song("o$it", "Other $it", genre = "Metal", albumId = 10L + it) }
        val taste = Taste(emptyMap(), emptyMap(), emptySet(), emptyMap(), together = mapOf(seed.id to mapOf(buddy.id to 1f)))
        val r = similarSongs(listOf(seed), listOf(seed, buddy) + others, taste, 3, Random(5), drift = true)
        // Played together beats unrelated songs, with nothing else in common.
        assertTrue(buddy in r)
    }

    @Test
    fun similarSongsRespectExclusions() {
        val seed = song("seed", "X")
        val hidden = song("hidden", "X")
        val ok = song("ok", "X")
        val r = similarSongs(listOf(seed), listOf(seed, hidden, ok), noTaste, 5, Random(2), exclude = setOf(hidden.id))
        assertEquals(listOf(ok), r)
    }

    @Test
    fun smartShuffleAddsOneSuggestionPerThree() {
        val list = (1..9).map { song("p$it", "Artist ${it % 3}", genre = "Pop") }
        val library = list + (1..20).map { song("x$it", "Other $it", genre = "Pop") }
        val r = smartShuffle(list, library, noTaste, Random(3), exclude = emptySet())
        assertEquals(12, r.size)
        assertTrue(r.containsAll(list))
    }

    @Test
    fun recapCountsOnlyThatYear() {
        val zone = java.time.ZoneOffset.UTC
        val a = song("a", "Old Friend")
        val b = song("b", "New Face", genre = "Indie")
        fun at(y: Int, m: Int) = java.time.LocalDate.of(y, m, 10).atStartOfDay(zone).toInstant().toEpochMilli()
        val ev = listOf(
            com.skarif.kairos.data.db.PlayEventEntity(songId = a.id, startedAt = at(2025, 5), listenedMs = 600_000, completed = true),
            com.skarif.kairos.data.db.PlayEventEntity(songId = a.id, startedAt = at(2026, 3), listenedMs = 120_000, completed = true),
            com.skarif.kairos.data.db.PlayEventEntity(songId = b.id, startedAt = at(2026, 7), listenedMs = 300_000, completed = true),
        )
        val r = yearRecap(2026, ev, mapOf(a.id to a, b.id to b), zone)!!
        assertEquals(7, r.minutes)
        assertEquals(b, r.topSongs.first().first)
        assertEquals(1, r.newArtists)
        assertEquals("Indie", r.topGenre)
        assertEquals(7, r.topMonth)
    }

    @Test
    fun m3uRoundTripsAndMatchesByPathOrTag() {
        val a = song("Alpha", "Ann", folder = "Music/A/")
        val b = song("Beta", "Bob", folder = "Music/B/")
        val (back, missing) = parseM3u(toM3u(listOf(b, a)), listOf(a, b))
        assertEquals(listOf(b, a), back)
        assertEquals(0, missing)
        val foreign = "#EXTM3U\n/storage/emulated/0/Music/A/Alpha.mp3\n#EXTINF:200,Bob - Beta\nD:\\elsewhere\\renamed.mp3\ngone.mp3\n"
        val (got, miss) = parseM3u(foreign, listOf(a, b))
        assertEquals(listOf(a, b), got)
        assertEquals(1, miss)
    }

    @Test
    fun searchIndexMatchesSearchSongsAndFiltersOtherLists() {
        val a = song("Shape of You", "Ed Sheeran", "Divide", albumId = 1)
        val b = song("Perfect", "Ed Sheeran", "Divide", albumId = 1)
        val c = song("Love Story", "Taylor Swift", "Fearless", albumId = 2)
        val all = listOf(a, b, c)
        val idx = SearchIndex(all, groupAlbums(all), listOf("Ed Sheeran", "Taylor Swift"), listOf("Pop"))
        assertEquals(searchSongs(all, "ed"), idx.songs("ed"))
        assertEquals(setOf(a.id, b.id), idx.songIds("sheeran"))
        assertEquals(listOf("Taylor Swift"), idx.artists("tay"))
        assertEquals(listOf("Fearless"), idx.albums("fear").map { it.title })
        assertEquals(3, idx.songIds("  ").size)
    }

    @Test
    fun songMatcherFindsByPathThenTags() {
        val a = song("Alpha", "Ann", folder = "Music/A/")
        val b = song("Beta", "Bob", folder = "Music/B/")
        val m = SongMatcher(listOf(a, b))
        assertEquals(a, m.find("music/a/Alpha.mp3", "x", "y", 0))
        assertEquals(b, m.find("Old/Path/b.mp3", "Beta", "Bob", 201_000))
        assertEquals(null, m.find("Old/Path/b.mp3", "Beta", "Bob", 260_000))
        assertEquals(b, m.find("nowhere.mp3", "beta", "Someone", 200_000))
    }

    @Test
    fun libraryOrderAndLettersAgree() {
        val titles = listOf("(When You Gonna) Give It Up", "...Baby One More Time", "2019", "21 Guns", "Élan", "আমার গান", "The Less I Know", "apple")
        val sorted = titles.sortedBy { librarySortKey(it) }
        val letters = sorted.map { libraryLetter(it) }
        // Letters never go backwards, so each header appears once, in order.
        assertEquals(letters.distinct(), letters.fold(mutableListOf<Char>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc })
        assertEquals('#', letters.first())
        assertEquals('W', libraryLetter("(When You Gonna) Give It Up"))
        assertEquals('B', libraryLetter("...Baby One More Time"))
        assertEquals('E', libraryLetter("Élan"))
        assertEquals('L', libraryLetter("The Less I Know", ignoreArticles = true))
        assertEquals('#', libraryLetter("আমার গান"))
    }
}
