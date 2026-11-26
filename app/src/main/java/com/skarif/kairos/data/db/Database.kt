package com.skarif.kairos.data.db

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One audio file. The id is MediaStore's id, so it stays stable across rescans. */
@Entity(tableName = "songs", indices = [Index("albumId"), Index("folder")])
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val year: Int,
    /** Folder relative to the storage root, e.g. "Music/Coastal Hours/". */
    val folder: String,
    val fileName: String,
    /** MediaStore volume, e.g. "external_primary" or an SD card id. */
    val volume: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val bitrate: Int,
    /** Genre tag as MediaStore reports it; empty when the file has none. */
    @ColumnInfo(defaultValue = "") val genre: String = "",
    /** Cover image URL, for online songs only (local ones read art from the file). */
    val artUrl: String? = null,
)

/** [SongEntity.volume] of songs streamed from an online source. */
const val ONLINE_VOLUME = "online"

/** Streamed rather than stored on the device. For these, [SongEntity.fileName] holds the source's track id. */
val SongEntity.isOnline: Boolean get() = volume == ONLINE_VOLUME

fun SongEntity.contentUri(): Uri =
    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

/** "FLAC", "MP3", "M4A"... for the format chip. */
fun SongEntity.formatLabel(): String =
    fileName.substringAfterLast('.', "").uppercase().ifEmpty { mimeType.substringAfter('/').uppercase() }

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val songId: Long, val addedAt: Long)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("songId")],
)
data class PlaylistSongEntity(val playlistId: Long, val position: Int, val songId: Long)

/** One listening session of one song. Powers Stats and the "Most played" mixes. */
@Entity(tableName = "play_events", indices = [Index("songId"), Index("startedAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val startedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
)

/** Per-song lyrics timing fix set from the Lyrics screen. */
@Entity(tableName = "lyrics_offsets")
data class LyricsOffsetEntity(@PrimaryKey val songId: Long, val offsetMs: Long)

@Dao
interface SongDao {
    /** Local and online songs (online ones are kept once played, favourited or added to a playlist). */
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<SongEntity>>

    /** Songs on this device: the library. */
    @Query("SELECT * FROM songs WHERE volume != '$ONLINE_VOLUME' ORDER BY title COLLATE NOCASE")
    fun observeLocal(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observe(id: Long): Flow<SongEntity?>

    @Query("SELECT id FROM songs WHERE volume != '$ONLINE_VOLUME'")
    suspend fun localIds(): List<Long>

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)
}

@Dao
interface FavoriteDao {
    @Query("SELECT songId FROM favorites")
    fun observeIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun remove(songId: Long)

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>
}

@Dao
abstract class PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<PlaylistEntity>>

    @Insert
    abstract suspend fun insert(p: PlaylistEntity): Long

    @Insert
    abstract suspend fun insertSongs(items: List<PlaylistSongEntity>)

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId, position")
    abstract fun observeAllItems(): Flow<List<PlaylistSongEntity>>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_songs WHERE playlistId = :playlistId")
    abstract suspend fun nextPosition(playlistId: Long): Int

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    abstract suspend fun delete(playlistId: Long)

    @Query("SELECT * FROM playlists ORDER BY createdAt")
    abstract suspend fun allLists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId, position")
    abstract suspend fun allItems(): List<PlaylistSongEntity>

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    abstract suspend fun rename(playlistId: Long, name: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    abstract suspend fun clear(playlistId: Long)

    /** Rewrites the whole order; positions are the primary key, so they can't be shifted in place. */
    @Transaction
    open suspend fun replaceSongs(playlistId: Long, songIds: List<Long>) {
        clear(playlistId)
        insertSongs(songIds.mapIndexed { i, s -> PlaylistSongEntity(playlistId, i, s) })
    }

    @Transaction
    open suspend fun append(playlistId: Long, songIds: List<Long>) {
        val start = nextPosition(playlistId)
        insertSongs(songIds.mapIndexed { i, s -> PlaylistSongEntity(playlistId, start + i, s) })
    }

    @Transaction
    open suspend fun create(name: String, songIds: List<Long>): Long {
        val id = insert(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
        insertSongs(songIds.mapIndexed { i, s -> PlaylistSongEntity(id, i, s) })
        return id
    }
}

@Dao
interface PlayEventDao {
    /** When a song was first played, for the "first played" memory in song info. */
    @Query("SELECT MIN(startedAt) FROM play_events WHERE songId = :songId")
    suspend fun firstPlayedAt(songId: Long): Long?

    @Insert
    suspend fun insert(e: PlayEventEntity): Long

    @androidx.room.Update
    suspend fun update(e: PlayEventEntity)

    @Query("SELECT * FROM play_events ORDER BY startedAt")
    fun observeAll(): Flow<List<PlayEventEntity>>

    @Query("SELECT * FROM play_events ORDER BY startedAt")
    suspend fun all(): List<PlayEventEntity>

    @Insert
    suspend fun insertAll(e: List<PlayEventEntity>)

    @Query("DELETE FROM play_events")
    suspend fun clear()
}

@Dao
interface LyricsOffsetDao {
    @Query("SELECT offsetMs FROM lyrics_offsets WHERE songId = :songId")
    fun observe(songId: Long): Flow<Long?>

    @Upsert
    suspend fun upsert(o: LyricsOffsetEntity)

    @Query("SELECT * FROM lyrics_offsets")
    suspend fun all(): List<LyricsOffsetEntity>
}

@Database(
    entities = [
        SongEntity::class, FavoriteEntity::class, PlaylistEntity::class,
        PlaylistSongEntity::class, PlayEventEntity::class, LyricsOffsetEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun favorites(): FavoriteDao
    abstract fun playlists(): PlaylistDao
    abstract fun playEvents(): PlayEventDao
    abstract fun lyricsOffsets(): LyricsOffsetDao

    companion object {
        fun create(context: Context): PlayerDatabase =
            Room.databaseBuilder(context, PlayerDatabase::class.java, "player.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** 0.6.0 adds genres. The next scan fills the column in. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3: songs.artUrl, for online songs (online flavor). */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN artUrl TEXT")
            }
        }
    }
}
