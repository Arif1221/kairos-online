package com.skarif.kairos.online

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates for the GitHub build: downloads the new APK and installs it over this one.
 *
 * Android never lets an app replace its code without a real install. What it does allow, on
 * Android 12+, is an app updating itself without the confirmation screen, once the user has let
 * Kairos install apps (asked once). Older versions, or when Android insists, show the
 * system's one-tap confirmation instead. Android checks the new APK is signed with the same key,
 * so a tampered download can't install.
 */
object SelfUpdate {
    sealed interface Status {
        data object Idle : Status
        /** [done] and [total] are bytes; [total] is 0 when the server didn't say. */
        data class Downloading(val progress: Float, val done: Long = 0L, val total: Long = 0L) : Status
        data object Installing : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    internal const val ACTION_STATUS = "com.skarif.kairos.UPDATE_STATUS"
    private const val CHANNEL = "updates"

    /** False until the user allows Kairos to install apps (Settings > Install unknown apps). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))

    fun reset() { _status.value = Status.Idle }

    /** Downloads [apkUrl] (GitHub release asset) and starts the install. Throws nothing: failures go to [status]. */
    suspend fun downloadAndInstall(context: Context, apkUrl: String) {
        val app = context.applicationContext
        try {
            val apk = File(app.cacheDir, "update/kairos-update.apk").apply { parentFile?.mkdirs(); delete() }
            _status.value = Status.Downloading(0f)
            download(apkUrl, apk)
            _status.value = Status.Installing
            install(app, apk)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _status.value = Status.Idle
            throw e
        } catch (e: Exception) {
            _status.value = Status.Failed("Couldn't update: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun download(url: String, into: File) = withContext(Dispatchers.IO) {
        var link = url
        // GitHub sends release assets through a redirect to its file host.
        repeat(5) {
            val conn = (URL(link).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                when (conn.responseCode) {
                    in 300..399 -> { link = conn.getHeaderField("Location") ?: throw IOException("Bad redirect"); return@repeat }
                    in 200..299 -> {
                        val total = conn.contentLengthLong
                        var done = 0L
                        var shown = 0f
                        conn.inputStream.use { input ->
                            into.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val p = done.toFloat() / total
                                        // Every half percent: the bar glides between updates, so it moves smoothly, not in steps.
                                        if (p - shown >= 0.005f) { shown = p; _status.value = Status.Downloading(p, done, total) }
                                    }
                                }
                            }
                        }
                        if (total > 0 && done != total) throw IOException("Download was cut short")
                        return@withContext
                    }
                    else -> throw IOException("HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+: an app updating itself may skip the confirmation screen.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("kairos.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS).setPackage(context.packageName)
            // Mutable: the installer fills in the result extras.
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
        apk.delete()
        // If it installs silently, Android stops this process now; Kairos opens again from the notification.
    }

    internal fun onResult(context: Context, intent: Intent) {
        when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _status.value = Status.Idle
            else -> _status.value = Status.Failed(
                if (code == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled"
                else "Couldn't install: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $code"}",
            )
        }
    }

    /** After an update the old process is gone; a tap on this brings Kairos back. */
    internal fun notifyUpdated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.skarif.kairos.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Kairos updated")
            .setContentText("Now on $version. Tap to open.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(4417, n) } // no notification permission: nothing shown, nothing breaks
    }
}

/** Install results, and "this app was just updated". Declared only in the online build's manifest. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SelfUpdate.ACTION_STATUS -> SelfUpdate.onResult(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> SelfUpdate.notifyUpdated(context)
        }
    }
}
