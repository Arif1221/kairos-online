package com.skarif.kairos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * STANDARD: Android mixer, 16-bit. Works everywhere.
 * HI_RES: 32-bit float output, so 24-bit files are not cut to 16 bits.
 * BIT_PERFECT: HI_RES plus Android 14's bit-perfect USB mixer. Volume and EQ are bypassed.
 */
enum class OutputMode { STANDARD, HI_RES, BIT_PERFECT }
enum class LyricsPreference { EMBEDDED, LRC_FILE }
/** What to ask LRCLIB for: synced lyrics (plain if there are none), or plain text only. */
enum class OnlineLyricsType { SYNCED, PLAIN }
enum class ReplayGainMode { OFF, TRACK, ALBUM }
/** Streaming quality for the online source. [kbps] is what YouTube typically serves at that tier. */
enum class StreamQuality(val label: String, val kbps: Int) { LOW("Data saver", 50), NORMAL("Normal", 128), HIGH("High", 160) }
/** Picture quality of online videos. [AUTO] follows the stream quality / data saver (about 720p, the smallest on data saver). */
enum class VideoQuality(val label: String, val maxHeight: Int) { AUTO("Auto", 0), P360("360p", 360), P480("480p", 480), P720("720p", 720), P1080("1080p", 1080) }
/** What the online Download action should save when the user chooses to remember it. */
enum class DownloadMode { AUDIO_ONLY, AUDIO_VIDEO, SAVE_TO_PHONE }
enum class SongSort(val label: String) {
    TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), DATE_ADDED("Date added"), LENGTH("Length"), MOST_PLAYED("Most played")
}

/** A little heavier veil than the old 0.5 so text stays easy to read over bright covers. */
const val DEFAULT_BACKGROUND_GLOW = 0.38f

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorFromArt: Boolean = true,
    val fixedColor: Int = 0xFFBD3657.toInt(),
    val blurBackground: Boolean = true,
    /** 0 = a heavy veil over the living background, 1 = almost none. [DEFAULT_BACKGROUND_GLOW] is the default look. */
    val backgroundGlow: Float = DEFAULT_BACKGROUND_GLOW,
    val pureBlack: Boolean = false,
    val readEmbeddedLyrics: Boolean = true,
    val readLrcFiles: Boolean = true,
    val lyricsPreference: LyricsPreference = LyricsPreference.EMBEDDED,
    /** Folders the user granted through the system picker, used to find .lrc files. */
    val lrcFolderUris: Set<String> = emptySet(),
    /** Look lyrics up on lrclib.net when the song has none (off by default: the app is offline otherwise). */
    val fetchLyricsOnline: Boolean = false,
    val onlineLyricsType: OnlineLyricsType = OnlineLyricsType.SYNCED,
    val outputMode: OutputMode = OutputMode.HI_RES,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val excludedFolders: Set<String> = emptySet(),
    val skipShortFiles: Boolean = true,
    val eqEnabled: Boolean = false,
    /** Ten gains in dB, 31 Hz to 16 kHz. */
    val eqGains: List<Float> = List(10) { 0f },
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val onboarded: Boolean = false,
    val songSort: SongSort = SongSort.TITLE,
    val sortDescending: Boolean = false,
    /** Keep playing similar songs from your library when the queue runs out. */
    val autoplay: Boolean = true,
    /** Shuffle adds a suggestion from your library after every few songs. */
    val smartShuffle: Boolean = false,
    /** Song ids kept out of mixes, shuffle-all, Smart Shuffle and autoplay. */
    val hiddenSongs: Set<String> = emptySet(),
    /** "songId:untilEpochMs": hidden like [hiddenSongs] until that time. */
    val snoozedSongs: Set<String> = emptySet(),
    /** Song ids whose plays don't shape mixes and suggestions (Stats still count them). */
    val tasteExcluded: Set<String> = emptySet(),
    /** Bring back the last queue, song and position when the app starts. */
    val resumeQueue: Boolean = true,
    /** Keep the screen on while the lyrics page is open. */
    val keepScreenOnLyrics: Boolean = true,
    /** Sort "The Weeknd" under W and "A Day to Remember" under D. */
    val ignoreArticles: Boolean = false,
    /** Online flavor only. */
    val streamQuality: StreamQuality = StreamQuality.HIGH,
    /** Online flavor only: picture quality of videos. */
    val videoQuality: VideoQuality = VideoQuality.AUTO,
    /** Show the song's synced lyrics as subtitles over a playing video. */
    val videoSubtitles: Boolean = true,
    /** Show the current synced lyric line in the mini player, in place of the artist. */
    val miniLyrics: Boolean = true,
    /** Online flavor only: use [StreamQuality.LOW] on metered (mobile) networks. */
    val dataSaverOnMobile: Boolean = true,
    /** Online flavor only: online search starts on Videos instead of Songs. */
    val searchVideosFirst: Boolean = false,
    /** Online flavor only: the Download button saves an M4A file to Music/Kairos instead of keeping it in the app. */
    val downloadToPhone: Boolean = false, // Legacy preference retained for migration compatibility.
    val downloadMode: DownloadMode = DownloadMode.AUDIO_ONLY,
    val rememberDownloadChoice: Boolean = false,
    /** Online flavor only: room for recently streamed songs, in MB. */
    val streamCacheMb: Int = 1024,
) {
    val minDurationMs: Long get() = if (skipShortFiles) 30_000L else 0L
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context, scope: CoroutineScope) {
    private val ds = context.applicationContext.dataStore

    private object K {
        val theme = stringPreferencesKey("theme")
        val colorFromArt = booleanPreferencesKey("color_from_art")
        val fixedColor = intPreferencesKey("fixed_color")
        val blur = booleanPreferencesKey("blur")
        val glow = androidx.datastore.preferences.core.floatPreferencesKey("bg_glow")
        val pureBlack = booleanPreferencesKey("pure_black")
        val embedded = booleanPreferencesKey("lyrics_embedded")
        val lrc = booleanPreferencesKey("lyrics_lrc")
        val lyricsPref = stringPreferencesKey("lyrics_pref")
        val lrcFolders = stringSetPreferencesKey("lrc_folders")
        val fetchOnline = booleanPreferencesKey("lyrics_fetch_online")
        val onlineType = stringPreferencesKey("lyrics_online_type")
        val output = stringPreferencesKey("output_mode")
        val replayGain = stringPreferencesKey("replay_gain")
        val excluded = stringSetPreferencesKey("excluded_folders")
        val skipShort = booleanPreferencesKey("skip_short")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqGains = stringPreferencesKey("eq_gains")
        val bass = intPreferencesKey("bass_boost")
        val virt = intPreferencesKey("virtualizer")
        val onboarded = booleanPreferencesKey("onboarded")
        val sort = stringPreferencesKey("song_sort")
        val sortDesc = booleanPreferencesKey("sort_desc")
        val autoplay = booleanPreferencesKey("autoplay")
        val smartShuffle = booleanPreferencesKey("smart_shuffle")
        val hidden = stringSetPreferencesKey("hidden_songs")
        val snoozed = stringSetPreferencesKey("snoozed_songs")
        val tasteExcluded = stringSetPreferencesKey("taste_excluded")
        val resumeQueue = booleanPreferencesKey("resume_queue")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on_lyrics")
        val ignoreArticles = booleanPreferencesKey("ignore_articles")
        val streamQuality = stringPreferencesKey("stream_quality")
        val videoQuality = stringPreferencesKey("video_quality")
        val videoSubtitles = booleanPreferencesKey("video_subtitles")
        val miniLyrics = booleanPreferencesKey("mini_lyrics")
        val dataSaver = booleanPreferencesKey("data_saver_mobile")
        val videosFirst = booleanPreferencesKey("search_videos_first")
        val downloadToPhone = booleanPreferencesKey("download_to_phone")
        val downloadMode = stringPreferencesKey("download_mode")
        val rememberDownloadChoice = booleanPreferencesKey("remember_download_choice")
        val streamCacheMb = androidx.datastore.preferences.core.intPreferencesKey("stream_cache_mb")
    }

    val settings: StateFlow<Settings> = ds.data
        .map(::read)
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    /** Reads the stored settings directly, for decisions that must not see the defaults. */
    suspend fun load(): Settings = read(ds.data.first())

    suspend fun update(change: (Settings) -> Settings) {
        ds.edit { p -> write(p, change(read(p))) }
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            themeMode = enumOr(p[K.theme], d.themeMode),
            colorFromArt = p[K.colorFromArt] ?: d.colorFromArt,
            fixedColor = p[K.fixedColor] ?: d.fixedColor,
            blurBackground = p[K.blur] ?: d.blurBackground,
            backgroundGlow = (p[K.glow] ?: d.backgroundGlow).coerceIn(0f, 1f),
            pureBlack = p[K.pureBlack] ?: d.pureBlack,
            readEmbeddedLyrics = p[K.embedded] ?: d.readEmbeddedLyrics,
            readLrcFiles = p[K.lrc] ?: d.readLrcFiles,
            lyricsPreference = enumOr(p[K.lyricsPref], d.lyricsPreference),
            lrcFolderUris = p[K.lrcFolders] ?: d.lrcFolderUris,
            fetchLyricsOnline = p[K.fetchOnline] ?: d.fetchLyricsOnline,
            onlineLyricsType = enumOr(p[K.onlineType], d.onlineLyricsType),
            outputMode = enumOr(p[K.output], d.outputMode),
            replayGain = enumOr(p[K.replayGain], d.replayGain),
            excludedFolders = p[K.excluded] ?: d.excludedFolders,
            skipShortFiles = p[K.skipShort] ?: d.skipShortFiles,
            eqEnabled = p[K.eqEnabled] ?: d.eqEnabled,
            eqGains = p[K.eqGains]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 } ?: d.eqGains,
            bassBoost = p[K.bass] ?: d.bassBoost,
            virtualizer = p[K.virt] ?: d.virtualizer,
            onboarded = p[K.onboarded] ?: d.onboarded,
            songSort = enumOr(p[K.sort], d.songSort),
            sortDescending = p[K.sortDesc] ?: d.sortDescending,
            autoplay = p[K.autoplay] ?: d.autoplay,
            smartShuffle = p[K.smartShuffle] ?: d.smartShuffle,
            hiddenSongs = p[K.hidden] ?: d.hiddenSongs,
            snoozedSongs = p[K.snoozed] ?: d.snoozedSongs,
            tasteExcluded = p[K.tasteExcluded] ?: d.tasteExcluded,
            resumeQueue = p[K.resumeQueue] ?: d.resumeQueue,
            keepScreenOnLyrics = p[K.keepScreenOn] ?: d.keepScreenOnLyrics,
            ignoreArticles = p[K.ignoreArticles] ?: d.ignoreArticles,
            streamQuality = enumOr(p[K.streamQuality], d.streamQuality),
            videoQuality = enumOr(p[K.videoQuality], d.videoQuality),
            videoSubtitles = p[K.videoSubtitles] ?: d.videoSubtitles,
            miniLyrics = p[K.miniLyrics] ?: d.miniLyrics,
            dataSaverOnMobile = p[K.dataSaver] ?: d.dataSaverOnMobile,
            searchVideosFirst = p[K.videosFirst] ?: d.searchVideosFirst,
            downloadToPhone = p[K.downloadToPhone] ?: d.downloadToPhone,
            downloadMode = enumOr(p[K.downloadMode], if (p[K.downloadToPhone] == true) DownloadMode.SAVE_TO_PHONE else d.downloadMode),
            rememberDownloadChoice = p[K.rememberDownloadChoice] ?: (p[K.downloadToPhone] != null),
            streamCacheMb = p[K.streamCacheMb] ?: d.streamCacheMb,
        )
    }

    private fun write(p: MutablePreferences, s: Settings) {
        p[K.theme] = s.themeMode.name
        p[K.colorFromArt] = s.colorFromArt
        p[K.fixedColor] = s.fixedColor
        p[K.blur] = s.blurBackground
        p[K.glow] = s.backgroundGlow.coerceIn(0f, 1f)
        p[K.pureBlack] = s.pureBlack
        p[K.embedded] = s.readEmbeddedLyrics
        p[K.lrc] = s.readLrcFiles
        p[K.lyricsPref] = s.lyricsPreference.name
        p[K.lrcFolders] = s.lrcFolderUris
        p[K.fetchOnline] = s.fetchLyricsOnline
        p[K.onlineType] = s.onlineLyricsType.name
        p[K.output] = s.outputMode.name
        p[K.replayGain] = s.replayGain.name
        p[K.excluded] = s.excludedFolders
        p[K.skipShort] = s.skipShortFiles
        p[K.eqEnabled] = s.eqEnabled
        p[K.eqGains] = s.eqGains.joinToString(",")
        p[K.bass] = s.bassBoost
        p[K.virt] = s.virtualizer
        p[K.onboarded] = s.onboarded
        p[K.sort] = s.songSort.name
        p[K.sortDesc] = s.sortDescending
        p[K.autoplay] = s.autoplay
        p[K.smartShuffle] = s.smartShuffle
        p[K.hidden] = s.hiddenSongs
        p[K.snoozed] = s.snoozedSongs
        p[K.tasteExcluded] = s.tasteExcluded
        p[K.resumeQueue] = s.resumeQueue
        p[K.keepScreenOn] = s.keepScreenOnLyrics
        p[K.ignoreArticles] = s.ignoreArticles
        p[K.streamQuality] = s.streamQuality.name
        p[K.videoQuality] = s.videoQuality.name
        p[K.videoSubtitles] = s.videoSubtitles
        p[K.miniLyrics] = s.miniLyrics
        p[K.dataSaver] = s.dataSaverOnMobile
        p[K.videosFirst] = s.searchVideosFirst
        p[K.downloadMode] = s.downloadMode.name
        p[K.rememberDownloadChoice] = s.rememberDownloadChoice
        p[K.streamCacheMb] = s.streamCacheMb
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
