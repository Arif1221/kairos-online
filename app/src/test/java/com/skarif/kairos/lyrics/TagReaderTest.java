package com.skarif.kairos.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Builds small ID3v2, FLAC and MP4 tags byte by byte and checks what TagReader gets out of them.
 * No real audio files are needed.
 */
public class TagReaderTest {

    // ------------------------------------------------------------------ byte helpers

    private static final byte[] AUDIO = new byte[32]; // stand-in for audio after the tag

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] latin1(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] b(int... v) { byte[] o = new byte[v.length]; for (int i = 0; i < v.length; i++) o[i] = (byte) v[i]; return o; }

    private static byte[] be32(long v) { return b((int) (v >> 24), (int) (v >> 16), (int) (v >> 8), (int) v); }
    private static byte[] be24(long v) { return b((int) (v >> 16), (int) (v >> 8), (int) v); }
    private static byte[] le32(long v) { return b((int) v, (int) (v >> 8), (int) (v >> 16), (int) (v >> 24)); }
    private static byte[] syncsafe(long v) { return b((int) (v >> 21) & 0x7f, (int) (v >> 14) & 0x7f, (int) (v >> 7) & 0x7f, (int) v & 0x7f); }

    private static TagReader.Result read(byte[] file) throws IOException {
        return TagReader.read(new ByteSource.OfBytes(file));
    }

    // ------------------------------------------------------------------ ID3v2 builders

    /** ID3v2.3 frame: 4-char id, 32-bit size, 2 flag bytes. */
    private static byte[] frame23(String id, byte[] data) { return cat(latin1(id), be32(data.length), b(0, 0), data); }

    /** ID3v2.4 frame: sizes are syncsafe. */
    private static byte[] frame24(String id, byte[] data) { return cat(latin1(id), syncsafe(data.length), b(0, 0), data); }

    /** ID3v2.2 frame: 3-char id, 24-bit size, no flags. */
    private static byte[] frame22(String id, byte[] data) { return cat(latin1(id), be24(data.length), data); }

    private static byte[] id3(int major, int flags, byte[] body) {
        return cat(latin1("ID3"), b(major, 0, flags), syncsafe(body.length), body);
    }

    /** USLT / ULT payload: encoding, language, descriptor + terminator, text. */
    private static byte[] usltLatin1(String text) { return cat(b(0), latin1("eng"), b(0), latin1(text)); }

    private static byte[] usltUtf16(String text) {
        // encoding 1 = UTF-16 with BOM; empty descriptor is just the 2-byte terminator
        return cat(b(1), latin1("eng"), b(0xFF, 0xFE, 0, 0), b(0xFF, 0xFE), text.getBytes(StandardCharsets.UTF_16LE));
    }

    private static byte[] txxx(String key, String value) { return cat(b(0), latin1(key), b(0), latin1(value)); }

    /** SYLT payload with millisecond timestamps (format 2). */
    private static byte[] sylt(int format, Object... textAndTimes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = cat(b(0), latin1("eng"), b(format, 1), b(0)); // enc, lang, format, type, empty descriptor
        out.write(head, 0, head.length);
        for (int i = 0; i < textAndTimes.length; i += 2) {
            byte[] t = cat(latin1((String) textAndTimes[i]), b(0), be32((Integer) textAndTimes[i + 1]));
            out.write(t, 0, t.length);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ ID3v2 tests

    @Test
    public void tooShortFileGivesEmptyResult() throws IOException {
        TagReader.Result r = read(new byte[5]);
        assertEquals("unknown", r.container);
        assertFalse(r.hasLyrics());
    }

    @Test
    public void unknownContainerIsIgnored() throws IOException {
        assertEquals("unknown", read(new byte[64]).container);
    }

    @Test
    public void id3v23PlainLyrics() throws IOException {
        TagReader.Result r = read(cat(id3(3, 0, frame23("USLT", usltLatin1("Line one\nLine two"))), AUDIO));
        assertEquals("id3", r.container);
        assertEquals("Line one\nLine two", r.lyricsText);
        assertTrue(r.hasLyrics());
    }

    @Test
    public void id3v23Utf16LyricsWithBom() throws IOException {
        TagReader.Result r = read(cat(id3(3, 0, frame23("USLT", usltUtf16("তুমি Ünïcode"))), AUDIO));
        assertEquals("তুমি Ünïcode", r.lyricsText);
    }

    @Test
    public void prefersLrcTextWhenThereAreTwoLyricFrames() throws IOException {
        byte[] body = cat(frame23("USLT", usltLatin1("plain words")), frame23("USLT", usltLatin1("[00:01.00]timed words")));
        assertEquals("[00:01.00]timed words", read(cat(id3(3, 0, body), AUDIO)).lyricsText);
    }

    @Test
    public void keepsFirstLrcWhenALaterFrameIsPlain() throws IOException {
        byte[] body = cat(frame23("USLT", usltLatin1("[00:01.00]timed")), frame23("USLT", usltLatin1("plain")));
        assertEquals("[00:01.00]timed", read(cat(id3(3, 0, body), AUDIO)).lyricsText);
    }

    @Test
    public void readsSyltMillisecondLines() throws IOException {
        byte[] body = frame23("SYLT", sylt(2, "Hello", 1000, "v1: world", 2500));
        TagReader.Result r = read(cat(id3(3, 0, body), AUDIO));
        assertEquals(2, r.syncedLines.size());
        assertEquals(1000L, r.syncedLines.get(0).timeMs);
        assertEquals("Hello", r.syncedLines.get(0).text);
        assertEquals(2500L, r.syncedLines.get(1).timeMs);
        assertEquals("world", r.syncedLines.get(1).text); // voice tag stripped
        assertTrue(r.hasLyrics());
    }

    @Test
    public void ignoresSyltTimedInMpegFrames() throws IOException {
        byte[] body = frame23("SYLT", sylt(1, "Hello", 40));
        assertTrue(read(cat(id3(3, 0, body), AUDIO)).syncedLines.isEmpty());
    }

    @Test
    public void readsReplayGainFromTxxx() throws IOException {
        byte[] body = cat(
                frame23("TXXX", txxx("replaygain_track_gain", "-6.50 dB")),
                frame23("TXXX", txxx("REPLAYGAIN_ALBUM_GAIN", "+1.25 dB")));
        TagReader.Result r = read(cat(id3(3, 0, body), AUDIO));
        assertEquals(-6.5f, r.trackGainDb, 0.001f);
        assertEquals(1.25f, r.albumGainDb, 0.001f);
    }

    @Test
    public void readsLyricsFromTxxx() throws IOException {
        byte[] body = frame23("TXXX", txxx("LYRICS", "From a TXXX frame"));
        assertEquals("From a TXXX frame", read(cat(id3(3, 0, body), AUDIO)).lyricsText);
    }

    @Test
    public void id3v24UsesSyncsafeFrameSizes() throws IOException {
        // 200 bytes: a plain 32-bit size would be 0x000000C8, syncsafe is 0x00000148
        String text = repeat("la ", 66) + "x!";
        TagReader.Result r = read(cat(id3(4, 0, frame24("USLT", usltLatin1(text))), AUDIO));
        assertEquals(text, r.lyricsText);
    }

    @Test
    public void id3v22UsesThreeLetterFrames() throws IOException {
        byte[] body = cat(frame22("ULT", usltLatin1("old tag")), frame22("TXX", txxx("REPLAYGAIN_TRACK_GAIN", "-3 dB")));
        TagReader.Result r = read(cat(id3(2, 0, body), AUDIO));
        assertEquals("old tag", r.lyricsText);
        assertEquals(-3f, r.trackGainDb, 0.001f);
    }

    @Test
    public void undoesWholeTagUnsynchronisation() throws IOException {
        // 'ÿ' is 0xFF in Latin-1; an unsynchronised tag stores it as FF 00.
        byte[] clean = frame23("USLT", usltLatin1("aÿb"));
        ByteArrayOutputStream unsynced = new ByteArrayOutputStream();
        for (byte x : clean) {
            unsynced.write(x);
            if ((x & 0xff) == 0xFF) unsynced.write(0);
        }
        TagReader.Result r = read(cat(id3(3, 0x80, unsynced.toByteArray()), AUDIO));
        assertEquals("aÿb", r.lyricsText);
    }

    @Test
    public void stopsSafelyOnAFrameThatClaimsToBeTooBig() throws IOException {
        byte[] good = frame23("USLT", usltLatin1("kept"));
        byte[] broken = cat(latin1("TXXX"), be32(999_999), b(0, 0), latin1("short"));
        TagReader.Result r = read(cat(id3(3, 0, cat(good, broken)), AUDIO));
        assertEquals("kept", r.lyricsText);
    }

    @Test
    public void stopsAtPadding() throws IOException {
        byte[] body = cat(frame23("USLT", usltLatin1("before padding")), new byte[64]);
        assertEquals("before padding", read(cat(id3(3, 0, body), AUDIO)).lyricsText);
    }

    @Test
    public void blankLyricsDoNotCount() throws IOException {
        TagReader.Result r = read(cat(id3(3, 0, frame23("USLT", usltLatin1("   "))), AUDIO));
        assertNull(r.lyricsText);
        assertFalse(r.hasLyrics());
    }

    // ------------------------------------------------------------------ FLAC

    private static byte[] vorbisComment(String... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendor = utf8("test vendor");
        byte[] head = cat(le32(vendor.length), vendor, le32(entries.length));
        out.write(head, 0, head.length);
        for (String e : entries) {
            byte[] kv = cat(le32(utf8(e).length), utf8(e));
            out.write(kv, 0, kv.length);
        }
        return out.toByteArray();
    }

    private static byte[] flac(byte[] comment) {
        byte[] streamInfo = new byte[34];
        return cat(
                latin1("fLaC"),
                b(0x00), be24(streamInfo.length), streamInfo,     // STREAMINFO, not last
                b(0x80 | 4), be24(comment.length), comment,        // VORBIS_COMMENT, last block
                AUDIO);
    }

    @Test
    public void flacVorbisComments() throws IOException {
        TagReader.Result r = read(flac(vorbisComment(
                "TITLE=Song",
                "lyrics=[00:05.00]Synced in FLAC",
                "REPLAYGAIN_TRACK_GAIN=-8.10 dB",
                "REPLAYGAIN_ALBUM_GAIN=-7,90 dB")));
        assertEquals("flac", r.container);
        assertEquals("[00:05.00]Synced in FLAC", r.lyricsText);
        assertEquals(-8.1f, r.trackGainDb, 0.001f);
        assertEquals(-7.9f, r.albumGainDb, 0.001f);
    }

    @Test
    public void flacPrefersSyncedLyricsOverUnsynced() throws IOException {
        TagReader.Result r = read(flac(vorbisComment("UNSYNCEDLYRICS=plain", "SYNCEDLYRICS=[00:01.00]timed")));
        assertEquals("[00:01.00]timed", r.lyricsText);
    }

    @Test
    public void flacAfterAnId3TagIsStillRead() throws IOException {
        byte[] file = cat(id3(3, 0, frame23("TXXX", txxx("REPLAYGAIN_TRACK_GAIN", "-1 dB"))),
                flac(vorbisComment("LYRICS=from flac")));
        TagReader.Result r = read(file);
        assertEquals("flac", r.container);
        assertEquals("from flac", r.lyricsText);
        assertEquals(-1f, r.trackGainDb, 0.001f);
    }

    @Test
    public void flacWithBrokenCommentCountDoesNotThrow() throws IOException {
        byte[] bad = cat(le32(0), le32(5), le32(100), utf8("LYRICS=x")); // claims 5 entries, first one too long
        TagReader.Result r = read(flac(bad));
        assertEquals("flac", r.container);
        assertFalse(r.hasLyrics());
    }

    // ------------------------------------------------------------------ MP4

    private static byte[] atom(String type, byte[]... children) {
        byte[] body = cat(children);
        return cat(be32(8 + body.length), latin1(type), body);
    }

    private static byte[] atom(byte[] type, byte[]... children) {
        byte[] body = cat(children);
        return cat(be32(8 + body.length), type, body);
    }

    private static byte[] data(String text) {
        return atom("data", b(0, 0, 0, 1), b(0, 0, 0, 0), utf8(text)); // type 1 = UTF-8, locale 0
    }

    private static byte[] freeform(String name, String value) {
        return atom("----",
                atom("mean", b(0, 0, 0, 0), utf8("com.apple.iTunes")),
                atom("name", b(0, 0, 0, 0), utf8(name)),
                data(value));
    }

    private static byte[] mp4(byte[]... ilstItems) {
        byte[] hdlr = atom("hdlr", new byte[25]);
        byte[] meta = atom("meta", b(0, 0, 0, 0), hdlr, atom("ilst", ilstItems)); // ISO full box
        return cat(
                atom("ftyp", latin1("M4A "), b(0, 0, 0, 0)),
                atom("moov", atom("udta", meta)),
                atom("mdat", AUDIO));
    }

    @Test
    public void mp4LyricsAtom() throws IOException {
        byte[] lyr = atom(b(0xA9, 'l', 'y', 'r'), data("M4A lyrics\nsecond line"));
        TagReader.Result r = read(mp4(lyr));
        assertEquals("mp4", r.container);
        assertEquals("M4A lyrics\nsecond line", r.lyricsText);
    }

    @Test
    public void mp4ITunesReplayGain() throws IOException {
        TagReader.Result r = read(mp4(
                freeform("replaygain_track_gain", "-4.20 dB"),
                freeform("REPLAYGAIN_ALBUM_GAIN", "-3.00 dB")));
        assertEquals(-4.2f, r.trackGainDb, 0.001f);
        assertEquals(-3f, r.albumGainDb, 0.001f);
        assertFalse(r.hasLyrics());
    }

    @Test
    public void mp4WithAtomSizeOverrunStopsSafely() throws IOException {
        byte[] file = cat(atom("ftyp", latin1("M4A "), b(0, 0, 0, 0)), be32(1_000_000), latin1("moov"), AUDIO);
        TagReader.Result r = read(file);
        assertEquals("mp4", r.container);
        assertFalse(r.hasLyrics());
    }

    // ------------------------------------------------------------------ parseGain

    @Test
    public void parseGainHandlesSignsCommasAndJunk() {
        assertEquals(2.5f, TagReader.parseGain("+2.5 dB"), 0.001f);
        assertEquals(-12f, TagReader.parseGain("-12 dB"), 0.001f);
        assertEquals(0.75f, TagReader.parseGain("0,75"), 0.001f);
        assertNull(TagReader.parseGain("loud"));
        assertNull(TagReader.parseGain(""));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
