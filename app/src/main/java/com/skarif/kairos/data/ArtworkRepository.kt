package com.skarif.kairos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.db.isOnline
import com.skarif.kairos.data.db.contentUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two colours every screen takes from the album art. Stored as ARGB ints. */
data class AlbumColors(val accent: Int, val onAccent: Int)

class ArtworkRepository(private val context: Context) {
    /** Sized in bytes (32 MB), keyed by song and size, so list thumbnails and full art coexist. */
    private val bitmaps = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val colors = LruCache<String, AlbumColors>(400)

    /** The cached bitmap, if already decoded; never touches the disk. */
    fun peek(song: SongEntity, sizePx: Int = 720): Bitmap? =
        bitmaps.get("${if (song.albumId > 0) "a${song.albumId}" else "s${song.id}"}@$sizePx")

    suspend fun load(song: SongEntity, sizePx: Int = 720): Bitmap? = withContext(Dispatchers.IO) {
        // Songs on one album share art, so cache per album: far fewer decodes in long lists.
        val key = "${if (song.albumId > 0) "a${song.albumId}" else "s${song.id}"}@$sizePx"
        bitmaps.get(key)?.let { return@withContext it }
        val bmp = runCatching {
            if (song.isOnline) {
                song.artUrl?.let { loadRemote(it, sizePx) }
            } else if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(song.contentUri(), Size(sizePx, sizePx), null)
            } else {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, song.contentUri())
                        embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } finally {
                        release()
                    }
                }
            }
        }.getOrNull()
        bmp?.also { bitmaps.put(key, it) }
    }

    /** Art at [url] (online albums, playlists, artists), cached like song art. */
    fun peekUrl(url: String, sizePx: Int): Bitmap? = bitmaps.get("u$url@$sizePx")

    suspend fun loadUrl(url: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "u$url@$sizePx"
        bitmaps.get(key) ?: runCatching { loadRemote(url, sizePx) }.getOrNull()?.also { bitmaps.put(key, it) }
    }

    /**
     * Downloads a cover (online songs) and crops it to a centred square, like local album art.
     * Video thumbnails are asked for at their largest first (1280×720): the default ones are
     * 480×360 with black bars, which looked soft and dark on the full-screen player.
     */
    private fun loadRemote(url: String, sizePx: Int): Bitmap? {
        for (candidate in candidates(url)) {
            val bmp = runCatching { fetch(candidate) }.getOrNull() ?: continue
            return squareCrop(bmp, sizePx)
        }
        return null
    }

    private fun candidates(url: String): List<String> {
        // YouTube Music art: ask for a bigger square than older rows stored.
        if ("googleusercontent.com" in url) return listOf(url.replace(Regex("=w\\d+-h\\d+"), "=w1080-h1080"), url)
        val id = Regex("""i\.ytimg\.com/vi(?:_webp)?/([\w-]{6,})/""").find(url)?.groupValues?.get(1) ?: return listOf(url)
        return listOf("https://i.ytimg.com/vi/$id/maxresdefault.jpg", "https://i.ytimg.com/vi/$id/hq720.jpg", url)
    }

    private fun fetch(url: String): Bitmap? {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) return null // maxres doesn't exist for every video
            val bytes = conn.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun squareCrop(src: Bitmap, sizePx: Int): Bitmap {
        // 4:3 thumbnails hold a 16:9 picture between black bars: keep only the picture.
        val h = if (src.width * 3 == src.height * 4) src.width * 9 / 16 else src.height
        val top = (src.height - h) / 2
        val side = minOf(src.width, h)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, top + (h - side) / 2, side, side)
        return if (side > sizePx) Bitmap.createScaledBitmap(square, sizePx, sizePx, true) else square
    }

    /** The accent for a song's cover (cached per album, like the art itself). */
    suspend fun colorsFor(song: SongEntity, bitmap: Bitmap): AlbumColors = withContext(Dispatchers.Default) {
        val key = if (song.albumId > 0) "a${song.albumId}" else "s${song.id}"
        colors.get(key)?.let { return@withContext it }
        val accent = pickAccent(Palette.from(softwareCopy(bitmap)).maximumColorCount(16).generate())
        AlbumColors(accent, onColorFor(accent)).also { colors.put(key, it) }
    }

    companion object {
        const val DEFAULT_ACCENT = 0xFFBD3657.toInt()
        private const val INK = 0xFF151515.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** The Palette accent of any bitmap (used to tint cards by their cover). */
        suspend fun paletteAccent(bitmap: Bitmap): Int = withContext(Dispatchers.Default) {
            pickAccent(Palette.from(softwareCopy(bitmap)).maximumColorCount(16).generate())
        }

        /** Thumbnails can come back as GPU-only (HARDWARE) bitmaps, whose pixels can't be read. */
        private fun softwareCopy(b: Bitmap): Bitmap =
            if (Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b

        /**
         * The accent is the colour that covers the most of the cover.
         * Similar shades (hues within 30°) are added together first, so flames drawn in five
         * yellows count as one big yellow. Greys, near-black and near-white are skipped.
         * From the winning colour family, the clearest shade is used.
         * Covers with no real colour (black-and-white, grey, sepia) get their dominant tone.
         */
        fun pickAccent(p: Palette): Int {
            val swatches = p.swatches
            if (swatches.isEmpty()) return DEFAULT_ACCENT
            val total = swatches.sumOf { it.population }.toFloat()
            data class Shade(val rgb: Int, val hue: Float, val sat: Float, val light: Float, val share: Float)
            val shades = swatches.map { sw ->
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(sw.rgb, hsl)
                Shade(sw.rgb, hsl[0], hsl[1], hsl[2], sw.population / total)
            }.filter { it.sat >= 0.2f && it.light in 0.12f..0.88f }
                .sortedByDescending { it.share }
            if (shades.isEmpty()) return p.dominantSwatch?.rgb ?: DEFAULT_ACCENT

            fun hueDistance(a: Float, b: Float): Float { val d = kotlin.math.abs(a - b); return minOf(d, 360f - d) }
            val families = mutableListOf<MutableList<Shade>>()
            for (sh in shades) {
                families.firstOrNull { hueDistance(it.first().hue, sh.hue) <= 30f }?.add(sh) ?: families.add(mutableListOf(sh))
            }
            // Share counts, but a vivid family beats a larger washed-out one (a grey-ish mass shouldn't win).
            val biggest = families.maxBy { f -> f.sumOf { (it.share * (0.35f + it.sat)).toDouble() } }
            return biggest.maxBy { it.share * it.sat * (1f - kotlin.math.abs(it.light - 0.5f)) }.rgb
        }

        /**
         * Keeps an accent readable against the background: lightness 0.32-0.58 in the light theme,
         * 0.45-0.70 in the dark one. Hue and saturation are left as the cover has them.
         */
        fun tuneAccent(argb: Int, dark: Boolean): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(argb, hsl)
            hsl[2] = if (dark) hsl[2].coerceIn(0.45f, 0.70f) else hsl[2].coerceIn(0.32f, 0.58f)
            return ColorUtils.HSLToColor(hsl)
        }

        /** Black or white text, whichever reads better on [accent]. */
        fun onColorFor(accent: Int): Int {
            val solid = ColorUtils.setAlphaComponent(accent, 255)
            return if (ColorUtils.calculateContrast(INK, solid) >= ColorUtils.calculateContrast(WHITE, solid)) INK else WHITE
        }
    }
}
