package com.skarif.kairos.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.util.LruCache
import com.skarif.kairos.data.db.LyricsOffsetDao
import com.skarif.kairos.data.db.LyricsOffsetEntity
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.db.isOnline
import com.skarif.kairos.data.db.contentUri
import com.skarif.kairos.lyrics.ByteSource
import com.skarif.kairos.lyrics.LrcParser
import com.skarif.kairos.lyrics.LyricLine
import com.skarif.kairos.lyrics.Lyrics
import com.skarif.kairos.lyrics.TagReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/**
 * Finds lyrics for a song: embedded in the file's tags, and/or a .lrc file next to it, and, only
 * if the user turned it on, from LRCLIB ([OnlineLyrics]).
 *
 * Why .lrc needs folder access: on Android 13+, READ_MEDIA_AUDIO only covers audio files, so a
 * plain .lrc file is invisible. The user grants the folder once through the system picker
 * (Settings > Lyrics), and we read the .lrc through that grant.
 */
class LyricsRepository(
    private val context: Context,
    private val offsets: LyricsOffsetDao,
    private val online: OnlineLyrics,
) {
    private val cache = LruCache<String, LyricsResult>(30)

    data class LyricsResult(val lyrics: Lyrics?)

    suspend fun load(song: SongEntity, s: Settings): Lyrics? = withContext(Dispatchers.IO) {
        val key = "${song.id}|${s.readEmbeddedLyrics}|${s.readLrcFiles}|${s.lyricsPreference}|${s.lrcFolderUris.hashCode()}|${s.fetchLyricsOnline}|${s.onlineLyricsType}"
        cache.get(key)?.let { return@withContext it.lyrics }

        // Online songs have no file to read tags or a .lrc from: always ask LRCLIB (that's the point
        // of the online build), synced if it has them, whatever "Fetch lyrics online" says.
        if (song.isOnline) {
            val pinnedType = online.pinned(song.id)
            val l = (pinnedType?.let { t -> online.saved(song.id)?.let { fromFound(it, t) } })
                ?: withOnline(song, null, s.onlineLyricsType)
            cache.put(key, LyricsResult(l))
            return@withContext l
        }

        // Downloaded on purpose from the lyrics page: that choice wins.
        online.pinned(song.id)?.let { type ->
            online.saved(song.id)?.let { fromFound(it, type) }?.let { pinned ->
                cache.put(key, LyricsResult(pinned))
                return@withContext pinned
            }
        }

        val embedded = if (s.readEmbeddedLyrics) readEmbedded(song) else null
        val lrc = if (s.readLrcFiles) readLrcFile(song, s.lrcFolderUris) else null
        val pick = when {
            embedded == null || embedded.isEmpty -> lrc
            lrc == null || lrc.isEmpty -> embedded
            s.lyricsPreference == LyricsPreference.EMBEDDED -> embedded
            else -> lrc
        }?.takeUnless { it.isEmpty }
        val result = if (s.fetchLyricsOnline) withOnline(song, pick, s.onlineLyricsType) else pick
        cache.put(key, LyricsResult(result))
        result
    }

    /**
     * Your own lyrics come first. LRCLIB is asked only when the song has none, or (when synced is
     * wanted) when it only has plain text: then synced lyrics from LRCLIB replace it.
     */
    private fun withOnline(song: SongEntity, local: Lyrics?, type: OnlineLyricsType): Lyrics? {
        val wantSynced = type == OnlineLyricsType.SYNCED
        if (local != null && (local.synced || !wantSynced)) return local
        val found = online.lookup(song) ?: return local
        val fetched = when {
            wantSynced && found.synced != null -> LrcParser.parse(found.synced, Lyrics.SOURCE_ONLINE)
            local != null -> return local // plain from the file beats plain from the internet
            found.plain != null -> LrcParser.parse(found.plain, Lyrics.SOURCE_ONLINE)
            // Plain only, but LRCLIB has just synced: show its words without the timing.
            found.synced != null -> LrcParser.parse(found.synced, Lyrics.SOURCE_ONLINE).let { l -> Lyrics(false, l.lines.map { LyricLine(-1, it.text) }, Lyrics.SOURCE_ONLINE) }
            else -> null
        }
        return fetched?.takeUnless { it.isEmpty } ?: local
    }

    /** Synced or plain lyrics from an LRCLIB answer, falling back to the other form if that's all there is. */
    private fun fromFound(found: OnlineLyrics.Found, type: OnlineLyricsType): Lyrics? {
        val synced = found.synced?.let { LrcParser.parse(it, Lyrics.SOURCE_ONLINE) }?.takeUnless { it.isEmpty }
        val plain = found.plain?.let { LrcParser.parse(it, Lyrics.SOURCE_ONLINE) }?.takeUnless { it.isEmpty }
            ?: synced?.let { l -> Lyrics(false, l.lines.map { LyricLine(-1, it.text) }, Lyrics.SOURCE_ONLINE) }
        return if (type == OnlineLyricsType.SYNCED) synced ?: plain else plain
    }

    /** Result of [download], for the message the lyrics page shows. */
    enum class Download { SYNCED, PLAIN, ONLY_PLAIN, NOT_FOUND, FAILED }

    /** Fetches this song's lyrics now in the chosen form and pins them. */
    suspend fun download(song: SongEntity, type: OnlineLyricsType): Download = withContext(Dispatchers.IO) {
        when (val r = online.fetchFresh(song)) {
            OnlineLyrics.Fetch.Failed -> Download.FAILED
            OnlineLyrics.Fetch.NotFound -> Download.NOT_FOUND
            is OnlineLyrics.Fetch.Got -> {
                val l = fromFound(r.found, type) ?: return@withContext Download.NOT_FOUND
                online.pin(song.id, type)
                cache.evictAll()
                when {
                    l.synced -> Download.SYNCED
                    type == OnlineLyricsType.SYNCED -> Download.ONLY_PLAIN
                    else -> Download.PLAIN
                }
            }
        }
    }

    enum class SaveLrc { SAVED, NEED_FOLDER, NOTHING }

    /**
     * Writes the downloaded lyrics as "<song name>.lrc" next to the song, through a folder the user
     * allowed with write access (the only place Android lets an app write beside your music).
     * Overwrites an existing .lrc rather than making "name (1).lrc".
     */
    suspend fun saveLrc(song: SongEntity): SaveLrc = withContext(Dispatchers.IO) {
        val found = online.saved(song.id) ?: return@withContext SaveLrc.NOTHING
        val text = found.synced ?: found.plain ?: return@withContext SaveLrc.NOTHING
        val base = song.fileName.substringBeforeLast('.')
        val volume = if (song.volume == "external_primary" || song.volume == "external") "primary" else song.volume.uppercase()
        val dirId = "$volume:${song.folder}".trimEnd('/')
        val fileId = "$volume:${song.folder}$base.lrc"
        val resolver = context.contentResolver
        val trees = resolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri }
        for (tree in trees) {
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            if (!dirId.startsWith(treeId)) continue
            val ok = runCatching {
                val existing = DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
                val overwrote = runCatching { resolver.openOutputStream(existing, "wt")?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
                if (!overwrote) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
                    // octet-stream so the provider keeps the ".lrc" name as given.
                    val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "$base.lrc") ?: error("not created")
                    resolver.openOutputStream(created, "wt")!!.use { it.write(text.toByteArray()) }
                }
            }.isSuccess
            if (ok) { cache.evictAll(); return@withContext SaveLrc.SAVED }
        }
        SaveLrc.NEED_FOLDER
    }

    /** Back to the song's own lyrics (or none). */
    suspend fun removeDownloaded(song: SongEntity) = withContext(Dispatchers.IO) {
        online.remove(song.id)
        cache.evictAll()
    }

    /** Forgets downloaded lyrics; the next play asks LRCLIB again. */
    fun clearOnline() {
        online.clear()
        cache.evictAll()
    }

    fun observeOffset(songId: Long): Flow<Long> = offsets.observe(songId).map { it ?: 0L }

    suspend fun setOffset(songId: Long, offsetMs: Long) = offsets.upsert(LyricsOffsetEntity(songId, offsetMs))

    /** Reads tags once; also used for ReplayGain by the playback service. */
    fun readTags(song: SongEntity): TagReader.Result? = if (song.isOnline) null else readTags(context, song.contentUri())

    private fun readEmbedded(song: SongEntity): Lyrics? {
        val r = readTags(song) ?: return null
        return when {
            r.syncedLines.isNotEmpty() -> LrcParser.fromTimed(r.syncedLines, Lyrics.SOURCE_EMBEDDED)
            r.lyricsText != null -> LrcParser.parse(r.lyricsText, Lyrics.SOURCE_EMBEDDED)
            else -> null
        }
    }

    private fun readLrcFile(song: SongEntity, trees: Set<String>): Lyrics? {
        if (trees.isEmpty()) return null
        val base = song.fileName.substringBeforeLast('.')
        // Documents provider ids: "primary:Music/Album/song.lrc" or "1A2B-3C4D:Music/...".
        val volume = if (song.volume == "external_primary" || song.volume == "external") "primary" else song.volume.uppercase()
        val docId = "$volume:${song.folder}$base.lrc"
        for (t in trees) {
            val tree = Uri.parse(t)
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            if (!docId.startsWith(treeId)) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: continue
            return LrcParser.parse(text, Lyrics.SOURCE_LRC_FILE)
        }
        return null
    }

    companion object {
        fun readTags(context: Context, uri: Uri): TagReader.Result? = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch -> TagReader.read(ByteSource.OfChannel(ch)) }
            }
        } catch (e: Exception) {
            Log.w("Lyrics", "Could not read tags of $uri", e)
            null
        }
    }
}
