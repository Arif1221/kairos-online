package com.skarif.kairos.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Writes title, artist, album and cover tags into an M4A (MP4) file, the way iTunes does:
 * moov > udta > meta > ilst. Files saved from YouTube come with no tags, so without this every
 * music app would show them as "Unknown artist".
 *
 * Pure Kotlin on bytes, so it is unit-tested without Android. An M4A of a song is a few MB,
 * small enough to edit in memory.
 */
object Mp4Tags {
    class Tags(val title: String, val artist: String, val album: String, val coverJpeg: ByteArray? = null)

    private class Box(val type: String, val start: Int, val headerSize: Int, val size: Int) {
        val end get() = start + size
        val bodyStart get() = start + headerSize
    }

    /** Top-level boxes (or the children of a container between [from] and [to]). */
    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = ByteBuffer.wrap(b, p, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            when (size) {
                1L -> { size = ByteBuffer.wrap(b, p + 8, 8).long; header = 16 }
                0L -> size = (to - p).toLong()
            }
            if (size < header || p + size > to) break // damaged; stop rather than guess
            out += Box(type, p, header, size.toInt())
            p += size.toInt()
        }
        return out
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, x -> a + x }
        return ByteBuffer.allocate(8).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).array() + body
    }

    private fun int32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    /** One ilst item: [type] holding a data box ([flags] 1 = UTF-8 text, 13 = JPEG). */
    private fun item(type: String, flags: Int, payload: ByteArray) = box(type, box("data", int32(flags), int32(0), payload))

    private fun udta(t: Tags): ByteArray {
        val items = buildList {
            add(item("©nam", 1, t.title.toByteArray()))
            add(item("©ART", 1, t.artist.toByteArray()))
            add(item("aART", 1, t.artist.toByteArray()))
            if (t.album.isNotBlank()) add(item("©alb", 1, t.album.toByteArray()))
            t.coverJpeg?.let { add(item("covr", 13, it)) }
        }
        val hdlr = box("hdlr", int32(0), int32(0), "mdir".toByteArray(), "appl".toByteArray(), ByteArray(8), ByteArray(1))
        val meta = box("meta", int32(0), hdlr, box("ilst", *items.toTypedArray()))
        return box("udta", meta)
    }

    /** The file with [tags] in it. An existing udta (old tags) is replaced. Throws if there is no moov. */
    fun write(file: ByteArray, tags: Tags): ByteArray {
        val top = boxes(file, 0, file.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: throw IllegalArgumentException("Not an MP4 file: no moov box")
        val kept = boxes(file, moov.bodyStart, moov.end).filter { it.type != "udta" }
        val body = ByteArrayOutputStream()
        kept.forEach { body.write(file, it.start, it.size) }
        body.write(udta(tags))
        val newMoov = (int32(8 + body.size()) + "moov".toByteArray() + body.toByteArray())

        // Chunk offsets (stco/co64) are absolute file positions: if moov sits before the audio
        // data, growing it moves the data, so every offset shifts by the same amount.
        val delta = newMoov.size - moov.size
        val dataAfter = top.any { it.type == "mdat" && it.start > moov.start }
        if (delta != 0 && dataAfter) shiftChunkOffsets(newMoov, 8, newMoov.size, delta)

        val out = ByteArrayOutputStream(file.size + delta.coerceAtLeast(0))
        out.write(file, 0, moov.start)
        out.write(newMoov)
        out.write(file, moov.end, file.size - moov.end)
        return out.toByteArray()
    }

    private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl")

    private fun shiftChunkOffsets(b: ByteArray, from: Int, to: Int, delta: Int) {
        for (bx in boxes(b, from, to)) {
            when (bx.type) {
                in CONTAINERS -> shiftChunkOffsets(b, bx.bodyStart, bx.end, delta)
                "stco" -> {
                    val buf = ByteBuffer.wrap(b)
                    val n = buf.getInt(bx.bodyStart + 4)
                    for (i in 0 until n) {
                        val at = bx.bodyStart + 8 + i * 4
                        buf.putInt(at, buf.getInt(at) + delta)
                    }
                }
                "co64" -> {
                    val buf = ByteBuffer.wrap(b)
                    val n = buf.getInt(bx.bodyStart + 4)
                    for (i in 0 until n) {
                        val at = bx.bodyStart + 8 + i * 8
                        buf.putLong(at, buf.getLong(at) + delta)
                    }
                }
            }
        }
    }

    /** Reads the title back; for tests and for checking a saved file. */
    fun readTitle(file: ByteArray): String? {
        val moov = boxes(file, 0, file.size).firstOrNull { it.type == "moov" } ?: return null
        val udta = boxes(file, moov.bodyStart, moov.end).firstOrNull { it.type == "udta" } ?: return null
        val meta = boxes(file, udta.bodyStart, udta.end).firstOrNull { it.type == "meta" } ?: return null
        val ilst = boxes(file, meta.bodyStart + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return null
        val nam = boxes(file, ilst.bodyStart, ilst.end).firstOrNull { it.type == "©nam" } ?: return null
        val data = boxes(file, nam.bodyStart, nam.end).firstOrNull { it.type == "data" } ?: return null
        return String(file, data.bodyStart + 8, data.end - data.bodyStart - 8)
    }
}
