package com.musify.mu.domain.model

import com.musify.mu.data.db.entities.Track

/**
 * Domain model that combines track data with playback state information
 */
data class TrackWithPlaybackInfo(
    val track: Track,
    val isLoading: Boolean = false,
    val hasArtwork: Boolean = false,
    val isPlaying: Boolean = false,
    val isCurrent: Boolean = false,
    val playCount: Int = 0,
    val isLiked: Boolean = false
)

/**
 * Extension to convert back to Track
 */
fun TrackWithPlaybackInfo.toTrack(): Track = track