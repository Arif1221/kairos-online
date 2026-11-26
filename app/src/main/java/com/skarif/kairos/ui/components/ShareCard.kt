package com.skarif.kairos.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.skarif.kairos.R
import java.io.File

/**
 * Shareable images (a lyric line, a Wrapped card), drawn with plain android.graphics: no extra
 * library, no off-screen Compose. 1080 x 1350 (Instagram's 4:5), the cover blurred behind,
 * the album colour as an accent, the Urbanist type of the app.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1350

    /**
     * @param headline the big text (a lyric line, "126 hours"). Long text gets smaller to fit.
     * @param kicker small caps above it ("LYRICS", "YOUR NOVEMBER").
     */
    fun render(
        context: Context,
        cover: Bitmap?,
        accent: Int,
        kicker: String,
        headline: String,
        title: String,
        subtitle: String,
    ): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val font = runCatching { ResourcesCompat.getFont(context, R.font.urbanist) }.getOrNull() ?: Typeface.DEFAULT

        // Background: the cover, blurred (same blur as the app's backdrop), darkened toward the bottom.
        cv.drawColor(0xFF111112.toInt())
        if (cover != null) {
            val blurred = blurredTiny(cover).asAndroidBitmap()
            val side = maxOf(W, H) * 1.2f
            val dst = RectF((W - side) / 2, (H - side) / 2, (W + side) / 2, (H + side) / 2)
            cv.drawBitmap(blurred, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0x33000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        })

        val pad = 84f
        // Accent bar and kicker.
        cv.drawRoundRect(RectF(pad, 150f, pad + 96f, 162f), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(font, Typeface.BOLD); color = 0xE6FFFFFF.toInt(); textSize = 34f; letterSpacing = 0.12f
        }
        cv.drawText(kicker.uppercase(), pad, 230f, small)

        // Headline: as big as fits in the middle block.
        val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(font, Typeface.BOLD); color = 0xFFFFFFFF.toInt() }
        val boxW = (W - 2 * pad).toInt()
        val boxH = 640
        var size = 104f
        var layout: StaticLayout
        do {
            big.textSize = size
            layout = StaticLayout.Builder.obtain(headline, 0, headline.length, big, boxW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).setMaxLines(9).build()
            size -= 6f
        } while (layout.height > boxH && size > 40f)
        cv.save()
        cv.translate(pad, 300f + (boxH - layout.height) / 2f)
        layout.draw(cv)
        cv.restore()

        // Footer: cover thumbnail, title and subtitle, and the app's name.
        val thumb = 132f
        val ty = H - pad - thumb
        if (cover != null) {
            val r = RectF(pad, ty, pad + thumb, ty + thumb)
            cv.save()
            cv.clipPath(Path().apply { addRoundRect(r, 26f, 26f, Path.Direction.CW) })
            val s = minOf(cover.width, cover.height)
            cv.drawBitmap(cover, Rect((cover.width - s) / 2, (cover.height - s) / 2, (cover.width + s) / 2, (cover.height + s) / 2), r, Paint(Paint.FILTER_BITMAP_FLAG))
            cv.restore()
        }
        val tx = if (cover != null) pad + thumb + 32f else pad
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(font, Typeface.BOLD); color = 0xFFFFFFFF.toInt(); textSize = 44f }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; color = 0xB3FFFFFF.toInt(); textSize = 34f }
        val maxTextW = W - pad - tx - 180f
        cv.drawText(ellipsize(title, name, maxTextW), tx, ty + 58f, name)
        cv.drawText(ellipsize(subtitle, sub, maxTextW), tx, ty + 108f, sub)
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(font, Typeface.BOLD); color = accent; textSize = 38f }
        cv.drawText("Kairos", W - pad - brand.measureText("Kairos"), ty + 108f, brand)
        return out
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END).toString()

    /** Saves [bmp] to the app's share folder and opens the system share sheet. */
    fun share(context: Context, bmp: Bitmap, fileName: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Only ever keep the latest card.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + ".png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share"))
    }
}
