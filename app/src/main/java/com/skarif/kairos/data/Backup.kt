package com.skarif.kairos.data

import com.skarif.kairos.data.db.FavoriteEntity
import com.skarif.kairos.data.db.LyricsOffsetEntity
import com.skarif.kairos.data.db.PlayEventEntity
import com.skarif.kairos.data.db.PlayerDatabase
import com.skarif.kairos.data.db.ONLINE_VOLUME
import com.skarif.kairos.data.db.SongEntity
import com.skarif.kairos.data.db.isOnline
import com.skarif.kairos.online.Flavor
import com.skarif.kairos.online.onlineId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup and restore of everything you made in Kairos: favourites, playlists (with custom
 * order), listening history, lyrics timing fixes, hidden/snoozed songs and your settings.
 *
 * Songs are stored by path, title, artist and length, never by Android's id, which changes on
 * a new phone or after reinstalling. On restore each one is looked up with [SongMatcher].
 * Online songs are stored with their YouTube id, album and cover, because a new phone or a fresh
 * install has no row for them: restore recreates the row, so their favourites, playlists and history
 * come back too. Restoring merges: nothing you already have is deleted, and restoring twice adds
 * nothing twice.
 */
class Backup(private val db: PlayerDatabase, private val settings: SettingsStore) {

    data class Result(val favourites: Int, val playlists: Int, val plays: Int, val missingSongs: Int)

    suspend fun export(songs: List<SongEntity>): String {
        val byId = songs.associateBy { it.id }
        // Each song once; everything else points at its index.
        val index = LinkedHashMap<Long, Int>()
        val songArr = JSONArray()
        fun ref(id: Long): Int? {
            index[id]?.let { return it }
            val s = byId[id] ?: return null
            val entry = JSONArray().put(s.folder + s.fileName).put(s.title).put(s.artist).put(s.durationMs)
            // Online songs: the path is the YouTube id; keep what's needed to rebuild the row on another phone.
            if (s.isOnline) entry.put(ONLINE_VOLUME).put(s.album).put(s.artUrl ?: "")
            songArr.put(entry)
            index[id] = songArr.length() - 1
            return index[id]
        }
        val favs = JSONArray().apply { db.favorites().all().forEach { f -> ref(f.songId)?.let { put(JSONArray().put(it).put(f.addedAt)) } } }
        val items = db.playlists().allItems().groupBy { it.playlistId }
        val lists = JSONArray().apply {
            db.playlists().allLists().forEach { p ->
                put(JSONObject().put("name", p.name).put("createdAt", p.createdAt)
                    .put("songs", JSONArray().apply { items[p.id].orEmpty().sortedBy { it.position }.forEach { i -> ref(i.songId)?.let { put(it) } } }))
            }
        }
        val plays = JSONArray().apply {
            db.playEvents().all().forEach { e -> ref(e.songId)?.let { put(JSONArray().put(it).put(e.startedAt).put(e.listenedMs).put(e.completed)) } }
        }
        val offsets = JSONArray().apply { db.lyricsOffsets().all().forEach { o -> ref(o.songId)?.let { put(JSONArray().put(it).put(o.offsetMs)) } } }
        val s = settings.load()
        fun refs(ids: Set<String>) = JSONArray().apply { ids.mapNotNull { it.toLongOrNull() }.forEach { id -> ref(id)?.let { put(it) } } }
        val snoozed = JSONArray().apply {
            s.snoozedSongs.forEach { e -> val (id, until) = e.split(':').let { it[0] to it.getOrNull(1) }; id.toLongOrNull()?.let(::ref)?.let { put(JSONArray().put(it).put(until)) } }
        }
        val prefs = JSONObject()
            .put("theme", s.themeMode.name).put("colorFromArt", s.colorFromArt).put("fixedColor", s.fixedColor)
            .put("blur", s.blurBackground).put("pureBlack", s.pureBlack).put("autoplay", s.autoplay).put("smartShuffle", s.smartShuffle)
            .put("replayGain", s.replayGain.name).put("songSort", s.songSort.name).put("sortDescending", s.sortDescending)
            .put("eqEnabled", s.eqEnabled).put("eqGains", JSONArray(s.eqGains.map { it.toDouble() })).put("bassBoost", s.bassBoost)
            .put("virtualizer", s.virtualizer).put("resumeQueue", s.resumeQueue).put("keepScreenOnLyrics", s.keepScreenOnLyrics)
            .put("ignoreArticles", s.ignoreArticles)
            .put("skipShortFiles", s.skipShortFiles)
            .put("readEmbeddedLyrics", s.readEmbeddedLyrics).put("readLrcFiles", s.readLrcFiles)
            .put("lyricsPreference", s.lyricsPreference.name)
            .put("fetchLyricsOnline", s.fetchLyricsOnline).put("onlineLyricsType", s.onlineLyricsType.name)
            .put("outputMode", s.outputMode.name)
            .put("streamQuality", s.streamQuality.name).put("dataSaverOnMobile", s.dataSaverOnMobile)
            .put("downloadMode", s.downloadMode.name).put("rememberDownloadChoice", s.rememberDownloadChoice)
            .put("streamCacheMb", s.streamCacheMb)
            .put("glow", s.backgroundGlow.toDouble()).put("videoQuality", s.videoQuality.name)
            .put("videoSubtitles", s.videoSubtitles).put("miniLyrics", s.miniLyrics)
            .put("searchVideosFirst", s.searchVideosFirst)
        return JSONObject()
            .put("app", "Kairos").put("version", 1).put("createdAt", System.currentTimeMillis())
            .put("songs", songArr).put("favourites", favs).put("playlists", lists).put("plays", plays).put("lyricsOffsets", offsets)
            .put("hidden", refs(s.hiddenSongs)).put("snoozed", snoozed).put("tasteExcluded", refs(s.tasteExcluded)).put("settings", prefs)
            .toString()
    }

    suspend fun restore(json: String, songs: List<SongEntity>): Result {
        val root = JSONObject(json)
        require(root.optString("app") == "Kairos") { "Not a Kairos backup" }
        val matcher = SongMatcher(songs)
        val arr = root.getJSONArray("songs")
        var missing = 0
        val rebuilt = ArrayList<SongEntity>()
        val local = (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            if (a.optString(4) == ONLINE_VOLUME) {
                // An online song: its id is a hash of the YouTube id, so it is the same on every phone.
                val videoId = a.getString(0)
                if (Flavor.online == null || videoId.isBlank()) { missing++; null }
                else SongEntity(
                    id = onlineId(videoId), title = a.getString(1), artist = a.getString(2), album = a.optString(5),
                    albumId = 0, durationMs = a.optLong(3), track = 0, year = 0, folder = "", fileName = videoId,
                    volume = ONLINE_VOLUME, mimeType = "audio/opus", sizeBytes = 0, dateAddedSec = System.currentTimeMillis() / 1000,
                    bitrate = 0, artUrl = a.optString(6).takeIf { it.isNotEmpty() && it != "null" },
                ).also { rebuilt += it }
            } else matcher.find(a.getString(0), a.getString(1), a.getString(2), a.optLong(3)).also { if (it == null) missing++ }
        }
        // Only the online songs this phone doesn't have yet; the ones it has keep their row.
        val known = songs.mapTo(HashSet()) { it.id }
        rebuilt.filter { it.id !in known }.distinctBy { it.id }.let { if (it.isNotEmpty()) db.songs().upsertAll(it) }
        fun song(i: Int) = local.getOrNull(i)

        // Favourites.
        val haveFavs = db.favorites().all().mapTo(HashSet()) { it.songId }
        var favCount = 0
        root.optJSONArray("favourites")?.let { f ->
            for (i in 0 until f.length()) {
                val e = f.getJSONArray(i)
                val s = song(e.getInt(0)) ?: continue
                if (s.id in haveFavs) continue
                db.favorites().add(FavoriteEntity(s.id, e.optLong(1, System.currentTimeMillis())))
                favCount++
            }
        }

        // Playlists: a playlist with the same name and songs is already here, so it is skipped.
        val existing = db.playlists().allLists()
        val existingItems = db.playlists().allItems().groupBy { it.playlistId }
        val have = existing.map { p -> p.name to existingItems[p.id].orEmpty().sortedBy { it.position }.map { it.songId } }.toSet()
        var listCount = 0
        root.optJSONArray("playlists")?.let { l ->
            for (i in 0 until l.length()) {
                val p = l.getJSONObject(i)
                val ids = p.getJSONArray("songs").let { s -> (0 until s.length()).mapNotNull { song(s.getInt(it))?.id } }
                val name = p.getString("name")
                if ((name to ids) in have) continue
                val finalName = if (existing.any { it.name == name }) "$name (restored)" else name
                db.playlists().create(finalName, ids)
                listCount++
            }
        }

        // Listening history: a play already here (same song, same start) is not added again.
        val seen = db.playEvents().all().mapTo(HashSet()) { it.songId to it.startedAt }
        val newPlays = ArrayList<PlayEventEntity>()
        root.optJSONArray("plays")?.let { p ->
            for (i in 0 until p.length()) {
                val e = p.getJSONArray(i)
                val s = song(e.getInt(0)) ?: continue
                val started = e.getLong(1)
                if (!seen.add(s.id to started)) continue
                newPlays += PlayEventEntity(songId = s.id, startedAt = started, listenedMs = e.getLong(2), completed = e.optBoolean(3))
            }
        }
        newPlays.chunked(500).forEach { db.playEvents().insertAll(it) }

        root.optJSONArray("lyricsOffsets")?.let { o ->
            for (i in 0 until o.length()) {
                val e = o.getJSONArray(i)
                song(e.getInt(0))?.let { db.lyricsOffsets().upsert(LyricsOffsetEntity(it.id, e.getLong(1))) }
            }
        }

        fun ids(name: String): Set<String> = root.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { song(a.getInt(it))?.id?.toString() }.toSet() }.orEmpty()
        val hidden = ids("hidden")
        val excluded = ids("tasteExcluded")
        val snoozed = root.optJSONArray("snoozed")?.let { a ->
            (0 until a.length()).mapNotNull { i -> val e = a.getJSONArray(i); song(e.getInt(0))?.let { "${it.id}:${e.optString(1)}" } }.toSet()
        }.orEmpty()
        val p = root.optJSONObject("settings")
        settings.update { s ->
            var n = s.copy(
                hiddenSongs = s.hiddenSongs + hidden,
                tasteExcluded = s.tasteExcluded + excluded,
                snoozedSongs = s.snoozedSongs + snoozed,
            )
            if (p != null) {
                fun <E : Enum<E>> enum(key: String, values: Array<E>, d: E) = p.optString(key).let { k -> values.firstOrNull { it.name == k } } ?: d
                n = n.copy(
                    themeMode = enum("theme", ThemeMode.entries.toTypedArray(), n.themeMode),
                    colorFromArt = p.optBoolean("colorFromArt", n.colorFromArt),
                    fixedColor = p.optInt("fixedColor", n.fixedColor),
                    blurBackground = p.optBoolean("blur", n.blurBackground),
                    pureBlack = p.optBoolean("pureBlack", n.pureBlack),
                    autoplay = p.optBoolean("autoplay", n.autoplay),
                    smartShuffle = p.optBoolean("smartShuffle", n.smartShuffle),
                    replayGain = enum("replayGain", ReplayGainMode.entries.toTypedArray(), n.replayGain),
                    songSort = enum("songSort", SongSort.entries.toTypedArray(), n.songSort),
                    sortDescending = p.optBoolean("sortDescending", n.sortDescending),
                    eqEnabled = p.optBoolean("eqEnabled", n.eqEnabled),
                    eqGains = p.optJSONArray("eqGains")?.let { g -> (0 until g.length()).map { g.getDouble(it).toFloat() } }?.takeIf { it.size == 10 } ?: n.eqGains,
                    bassBoost = p.optInt("bassBoost", n.bassBoost),
                    virtualizer = p.optInt("virtualizer", n.virtualizer),
                    resumeQueue = p.optBoolean("resumeQueue", n.resumeQueue),
                    keepScreenOnLyrics = p.optBoolean("keepScreenOnLyrics", n.keepScreenOnLyrics),
                    ignoreArticles = p.optBoolean("ignoreArticles", n.ignoreArticles),
                    skipShortFiles = p.optBoolean("skipShortFiles", n.skipShortFiles),
                    readEmbeddedLyrics = p.optBoolean("readEmbeddedLyrics", n.readEmbeddedLyrics),
                    readLrcFiles = p.optBoolean("readLrcFiles", n.readLrcFiles),
                    lyricsPreference = enum("lyricsPreference", LyricsPreference.entries.toTypedArray(), n.lyricsPreference),
                    fetchLyricsOnline = p.optBoolean("fetchLyricsOnline", n.fetchLyricsOnline),
                    onlineLyricsType = enum("onlineLyricsType", OnlineLyricsType.entries.toTypedArray(), n.onlineLyricsType),
                    outputMode = enum("outputMode", OutputMode.entries.toTypedArray(), n.outputMode),
                    streamQuality = enum("streamQuality", StreamQuality.entries.toTypedArray(), n.streamQuality),
                    dataSaverOnMobile = p.optBoolean("dataSaverOnMobile", n.dataSaverOnMobile),
                    downloadMode = enum("downloadMode", DownloadMode.entries.toTypedArray(), n.downloadMode),
                    rememberDownloadChoice = p.optBoolean("rememberDownloadChoice", n.rememberDownloadChoice),
                    streamCacheMb = p.optInt("streamCacheMb", n.streamCacheMb).coerceIn(256, 10240),
                    backgroundGlow = p.optDouble("glow", n.backgroundGlow.toDouble()).toFloat().coerceIn(0f, 1f),
                    videoQuality = enum("videoQuality", VideoQuality.entries.toTypedArray(), n.videoQuality),
                    videoSubtitles = p.optBoolean("videoSubtitles", n.videoSubtitles),
                    miniLyrics = p.optBoolean("miniLyrics", n.miniLyrics),
                    searchVideosFirst = p.optBoolean("searchVideosFirst", n.searchVideosFirst),
                )
            }
            n
        }
        return Result(favCount, listCount, newPlays.size, missing)
    }
}
