package com.skarif.kairos.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.graphics.scale
import com.skarif.kairos.MainActivity
import com.skarif.kairos.R
import com.skarif.kairos.playback.PlaybackService

/**
 * Home-screen widget: the song's cover, blurred, fills the card, with the cover, title and
 * controls on top. RemoteViews can't blur, so the blur is made once per song by shrinking the
 * cover to 12 px and stretching it back (smooth scaling smears it); cheap, and no extra library.
 */
class KairosWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Added to the home screen, or Android asked: show the last known song until playback updates it.
        val p = prefs(context)
        push(context, p.getString("title", null), p.getString("artist", null), cover = null, playing = false, live = false)
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("widget", Context.MODE_PRIVATE)

        private fun ids(context: Context) =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, KairosWidget::class.java))

        /** True when at least one widget is on the home screen (skip the work otherwise). */
        fun active(context: Context) = ids(context).isNotEmpty()

        /** Called by the playback service when the song or play state changes. */
        /**
         * [live] = the playback service is running, so the buttons can control it directly. When it
         * isn't (app closed), they open Kairos instead: starting the service from a widget with an
         * empty player would have nothing to play, and Android would stop it with an error.
         */
        fun push(context: Context, title: String?, artist: String?, cover: Bitmap?, playing: Boolean, live: Boolean = true) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            prefs(context).edit().putString("title", title).putString("artist", artist).apply()
            // Keep the last cover, so the widget still shows it after the app closes or the phone restarts.
            val file = java.io.File(context.filesDir, "widget_cover.png")
            val art = cover?.scale(216, 216)?.also { bmp -> runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
                ?: if (!live) runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() else null
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
                setTextViewText(R.id.widget_title, title ?: "Kairos")
                setTextViewText(R.id.widget_artist, artist ?: "Tap play to start")
                setImageViewResource(R.id.widget_play, if (playing) R.drawable.widget_pause else R.drawable.widget_play)
                setContentDescription(R.id.widget_play, if (playing) "Pause" else "Play")
                if (art != null) {
                    setImageViewBitmap(R.id.widget_blur, blur(art))
                    setImageViewBitmap(R.id.widget_cover, rounded(art, 36f))
                } else {
                    setImageViewResource(R.id.widget_cover, R.drawable.ic_launcher_foreground)
                }
                val open = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                setOnClickPendingIntent(R.id.widget_prev, if (live) mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) else open)
                setOnClickPendingIntent(R.id.widget_play, if (live) mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else open)
                setOnClickPendingIntent(R.id.widget_next, if (live) mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) else open)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        /** A media-button press sent to the playback service, as a headset button would. */
        private fun mediaKey(context: Context, code: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(context, PlaybackService::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return PendingIntent.getForegroundService(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun blur(src: Bitmap): Bitmap = src.scale(12, 12, filter = true).scale(240, 240, filter = true)

        private fun rounded(src: Bitmap, radius: Float): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), radius, radius, paint)
            return out
        }
    }
}
