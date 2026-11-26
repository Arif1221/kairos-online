package com.skarif.kairos.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.skarif.kairos.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Saves an online song as an ordinary M4A in Music/Kairos, tagged with its title, artist and
 * cover, so every music app and file manager sees it (and Kairos lists it in your Library).
 */
object PhoneSaver {
    const val FOLDER = "Kairos"

    /** Android 9 and older need the storage permission to write to Music; 10+ need none for their own files. */
    val needsPermission get() = Build.VERSION.SDK_INT < 29

    fun fileName(song: SongEntity): String =
        "${song.artist} - ${song.title}".replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").take(120).trim() + ".m4a"

    /** Tags [m4a] and writes it to Music/Kairos. Returns where it went. Throws on failure. */
    suspend fun save(context: Context, song: SongEntity, m4a: File, cover: Bitmap?): Uri = withContext(Dispatchers.IO) {
        val jpeg = cover?.let { bmp ->
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
        val album = song.album.takeIf { it.isNotBlank() && it != "YouTube" && it != "YouTube Music" } ?: ""
        val bytes = Mp4Tags.write(m4a.readBytes(), Mp4Tags.Tags(song.title, song.artist, album, jpeg))
        val name = fileName(song)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(MediaStore.Audio.Media.TITLE, song.title)
                put(MediaStore.Audio.Media.ARTIST, song.artist)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Couldn't create $name")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Couldn't write $name")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }
            val file = File(dir, name)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("audio/mp4"), null)
            Uri.fromFile(file)
        }
    }
}
