package com.skarif.kairos.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/** Edge cases for LrcParser and Lyrics that LrcParserTest does not cover. */
public class LyricsEdgeCasesTest {

    @Test
    public void nullTextGivesEmptyPlainLyrics() {
        Lyrics l = LrcParser.parse(null, "t");
        assertFalse(l.synced);
        assertTrue(l.isEmpty());
    }

    @Test
    public void handlesWindowsAndOldMacLineEndingsAndBom() {
        Lyrics l = LrcParser.parse("﻿[00:01.00]a\r\n[00:02.00]b\r[00:03.00]c", "t");
        assertEquals(3, l.lines.size());
        assertEquals("c", l.lines.get(2).text);
    }

    @Test
    public void oneTwoAndThreeDigitFractions() {
        Lyrics l = LrcParser.parse("[00:01.5]a\n[00:02.50]b\n[00:03.500]c\n[00:04]d", "t");
        assertEquals(1500L, l.lines.get(0).timeMs);
        assertEquals(2500L, l.lines.get(1).timeMs);
        assertEquals(3500L, l.lines.get(2).timeMs);
        assertEquals(4000L, l.lines.get(3).timeMs);
    }

    @Test
    public void colonFractionSeparatorAndLongMinutes() {
        Lyrics l = LrcParser.parse("[01:02:30]a\n[100:00.00]b", "t");
        assertEquals(62_300L, l.lines.get(0).timeMs);
        assertEquals(6_000_000L, l.lines.get(1).timeMs);
    }

    @Test
    public void sortsOutOfOrderLines() {
        Lyrics l = LrcParser.parse("[00:03.00]three\n[00:01.00]one\n[00:02.00]two", "t");
        assertEquals("one", l.lines.get(0).text);
        assertEquals("two", l.lines.get(1).text);
        assertEquals("three", l.lines.get(2).text);
    }

    @Test
    public void negativeOffsetDelaysLines() {
        Lyrics l = LrcParser.parse("[offset:-250]\n[00:01.00]a", "t");
        assertEquals(1250L, l.lines.get(0).timeMs);
    }

    @Test
    public void offsetNeverPushesALineBeforeZero() {
        Lyrics l = LrcParser.parse("[offset:+5000]\n[00:01.00]a", "t");
        assertEquals(0L, l.lines.get(0).timeMs);
    }

    @Test
    public void metadataOnlyIsEmptyPlainLyrics() {
        Lyrics l = LrcParser.parse("[ar:Artist]\n[ti:Title]\n[by:me]", "t");
        assertFalse(l.synced);
        assertTrue(l.isEmpty());
    }

    @Test
    public void emptyTimedLinesAreKeptAsGaps() {
        Lyrics l = LrcParser.parse("[00:01.00]a\n[00:02.00]\n[00:03.00]b", "t");
        assertEquals(3, l.lines.size());
        assertEquals("", l.lines.get(1).text);
    }

    @Test
    public void bracketTextLaterInALineIsNotATimestamp() {
        Lyrics l = LrcParser.parse("[00:01.00]see you at [00:02.00]", "t");
        assertEquals(1, l.lines.size());
        assertEquals("see you at [00:02.00]", l.lines.get(0).text);
    }

    @Test
    public void looksSynced() {
        assertTrue(LrcParser.looksSynced("x\n[00:01.00]y"));
        assertFalse(LrcParser.looksSynced("just words"));
        assertFalse(LrcParser.looksSynced(null));
    }

    @Test
    public void fromTimedSortsAndTrims() {
        Lyrics l = LrcParser.fromTimed(Arrays.asList(new LyricLine(2000, " b "), new LyricLine(1000, "a")), "sylt");
        assertTrue(l.synced);
        assertEquals("a", l.lines.get(0).text);
        assertEquals("b", l.lines.get(1).text);
        assertEquals("sylt", l.source);
    }

    @Test
    public void indexAtOnPlainOrEmptyLyricsIsMinusOne() {
        assertEquals(-1, LrcParser.parse("plain", "t").indexAt(10_000));
        assertEquals(-1, LrcParser.parse("", "t").indexAt(10_000));
    }

    @Test
    public void indexAtExactlyOnATimestampSelectsThatLine() {
        Lyrics l = LrcParser.parse("[00:01.00]a\n[00:02.00]b", "t");
        assertEquals(0, l.indexAt(1000));
        assertEquals(1, l.indexAt(2000));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void linesCannotBeModified() {
        LrcParser.parse("[00:01.00]a", "t").lines.add(new LyricLine(0, "x"));
    }
}
