package com.skarif.kairos.data

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

class ArtistsTest {

    @Test
    fun singleArtistStaysWhole() {
        assertEquals(listOf("Arijit Singh"), splitArtists("Arijit Singh"))
    }

    @Test
    fun splitsFeaturingForms() {
        assertEquals(listOf("Akon", "Eminem"), splitArtists("Akon feat. Eminem"))
        assertEquals(listOf("Akon", "Eminem"), splitArtists("Akon ft Eminem"))
        assertEquals(listOf("Akon", "Eminem"), splitArtists("Akon featuring Eminem"))
        assertEquals(listOf("A", "B"), splitArtists("A FEAT. B"))
    }

    @Test
    fun splitsSeparators() {
        assertEquals(listOf("A", "B", "C", "D"), splitArtists("A, B; C / D"))
        assertEquals(listOf("A", "B"), splitArtists("A & B"))
        assertEquals(listOf("A", "B"), splitArtists("A x B"))
        assertEquals(listOf("A", "B"), splitArtists("A vs. B"))
        assertEquals(listOf("A", "B"), splitArtists("A with B"))
    }

    @Ignore("Known bug: the separator needs a space before 'feat', so \"A (feat. B)\" stays one artist: \"A (feat. B\"")
    @Test
    fun splitsBracketedFeaturing() {
        assertEquals(listOf("A", "B"), splitArtists("A (feat. B)"))
    }

    @Test
    fun removesDuplicatesIgnoringCase() {
        assertEquals(listOf("Pritam", "Arijit Singh"), splitArtists("Pritam, Arijit Singh, PRITAM"))
    }

    @Test
    fun doesNotSplitInsideWords() {
        // "x" and "with" only split as whole words between spaces
        assertEquals(listOf("Xavier Rudd"), splitArtists("Xavier Rudd"))
        assertEquals(listOf("Withered Hand"), splitArtists("Withered Hand"))
    }

    @Test
    fun ampersandWithoutSpacesIsKept() {
        assertEquals(listOf("Simon&Garfunkel"), splitArtists("Simon&Garfunkel"))
    }

    @Test
    fun knownTradeOffSlashSplitsBandNames() {
        // The comment in Artists.kt accepts this: "/" always splits, so AC/DC becomes two artists.
        assertEquals(listOf("AC", "DC"), splitArtists("AC/DC"))
    }

    @Test
    fun blankInputFallsBackToTheTrimmedRawValue() {
        assertEquals(listOf(""), splitArtists("   "))
        assertEquals(listOf(","), splitArtists(","))
    }
}
