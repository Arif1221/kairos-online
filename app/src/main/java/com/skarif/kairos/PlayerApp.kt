package com.skarif.kairos

import android.app.Application
import com.skarif.kairos.data.ArtworkRepository
import com.skarif.kairos.data.LyricsRepository
import com.skarif.kairos.data.MediaScanner
import com.skarif.kairos.data.OnlineLyrics
import com.skarif.kairos.data.SettingsStore
import com.skarif.kairos.data.db.PlayerDatabase
import com.skarif.kairos.playback.PlayerConnection
import com.skarif.kairos.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PlayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Keep the last crash's stack trace so the app can show it on the next launch;
        // there is no other way to see it on a phone without a computer.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "Kairos ${packageManager.getPackageInfo(packageName, 0).versionName}\n" +
                        "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n" +
                        android.util.Log.getStackTraceString(error),
                )
            }
            previous?.uncaughtException(thread, error)
        }
        container = AppContainer(this)
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}

/**
 * Hand-written dependency container. A DI library (Hilt) would add build setup without
 * adding anything this small app needs yet; swap it in if the graph grows.
 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = PlayerDatabase.create(app)
    val settings = SettingsStore(app, scope)
    val scanner = MediaScanner(app, db.songs())
    val lyrics = LyricsRepository(
        app, db.lyricsOffsets(),
        OnlineLyrics(app, "Kairos/${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"} (https://github.com/Arif1221/kairos)"),
    )
    val artwork = ArtworkRepository(app)
    val player = PlayerConnection(app, scope)
    val sleep = SleepTimer(player, scope)
}
