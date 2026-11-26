package com.skarif.kairos.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class Mp4TagsTest {
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, x -> a + x }
        return ByteBuffer.allocate(8).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).array() + body
    }
    private fun int(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    /** ftyp, moov (with a chunk offset pointing into mdat), mdat with known audio bytes. */
    private fun file(): Pair<ByteArray, ByteArray> {
        val audio = ByteArray(64) { it.toByte() }
        val ftyp = box("ftyp", "M4A ".toByteArray(), int(0))
        fun moov(offset: Int) = box("moov", box("mvhd", ByteArray(20)),
            box("trak", box("mdia", box("minf", box("stbl", box("stco", int(0), int(1), int(offset)))))))
        val moovSize = moov(0).size
        val dataStart = ftyp.size + moovSize + 8
        return (ftyp + moov(dataStart) + box("mdat", audio)) to audio
    }

    private fun stco(b: ByteArray): Int {
        // stco is the only one; find it and read its single entry.
        val i = String(b, Charsets.ISO_8859_1).indexOf("stco")
        return ByteBuffer.wrap(b).getInt(i + 4 + 8)
    }

    @Test fun writesTagsAndShiftsChunkOffsets() {
        val (src, audio) = file()
        val out = Mp4Tags.write(src, Mp4Tags.Tags("Kesariya", "Arijit Singh", "Brahmastra", byteArrayOf(1, 2, 3)))
        assertEquals("Kesariya", Mp4Tags.readTitle(out))
        // The chunk offset still points at the audio bytes.
        val at = stco(out)
        assertArrayEquals(audio, out.copyOfRange(at, at + audio.size))
    }

    @Test fun replacesOldTags() {
        val once = Mp4Tags.write(file().first, Mp4Tags.Tags("Old", "A", ""))
        val twice = Mp4Tags.write(once, Mp4Tags.Tags("New", "A", ""))
        assertEquals("New", Mp4Tags.readTitle(twice))
        assertEquals(1, String(twice, Charsets.ISO_8859_1).windowed(4).count { it == "udta" })
        val (_, audio) = file()
        val at = stco(twice)
        assertArrayEquals(audio, twice.copyOfRange(at, at + audio.size))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonMp4() {
        Mp4Tags.write(ByteArray(32), Mp4Tags.Tags("a", "b", ""))
    }
}
