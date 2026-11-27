package com.skarif.kairos.online

/** Whether the playing song can be shown as a video. */
sealed interface VideoState {
    /** Not an online song (or the offline build): no Song/Video switch at all. */
    data object None : VideoState
    /** Looking for the song's video. */
    data object Checking : VideoState
    /** Search completed successfully, but no suitable video exists for this song. */
    data object Unavailable : VideoState
    /** Lookup failed temporarily; unlike Unavailable, this result must not be cached as a negative match. */
    data class Error(val reason: String? = null) : VideoState
    /** [videoId] is the YouTube video to play for the song [songId] (so a stale answer is never applied to the next song). */
    data class Available(val songId: Long, val videoId: String) : VideoState
}
