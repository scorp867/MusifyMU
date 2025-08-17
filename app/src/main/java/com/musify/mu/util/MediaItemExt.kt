package com.musify.mu.util

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.musify.mu.data.db.entities.Track

/**
 * Convert Track to MediaItem for ExoPlayer.
 * ExoPlayer will automatically extract additional metadata from the audio file.
 */
fun Track.toMediaItem(): MediaItem {
    // Don't provide artwork URI - let ExoPlayer extract it automatically
    // This ensures ExoPlayer uses its optimized extraction methods
    
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(mediaId) // mediaId stores the content:// URI string
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                // Let ExoPlayer extract artwork automatically - this is more reliable
                // .setArtworkUri(artwork) // Commented out to let ExoPlayer handle it
                .setGenre(genre)
                .setReleaseYear(year)
                .setTrackNumber(track)
                .setAlbumArtist(albumArtist)
                .build()
        )
        .build()
}

/**
 * Convert MediaItem back to Track.
 * This will include any metadata that ExoPlayer extracted from the file.
 */
fun MediaItem.toTrack(): Track =
    Track(
        mediaId = mediaId,
        title = mediaMetadata.title?.toString() ?: "Unknown",
        artist = mediaMetadata.artist?.toString() ?: "Unknown",
        album = mediaMetadata.albumTitle?.toString() ?: "Unknown",
        durationMs = 0L, // Duration will be available after ExoPlayer prepares the media
        artUri = mediaMetadata.artworkUri?.toString(),
        albumId = null, // Will be resolved from MediaStore if needed
        // Extract additional metadata that ExoPlayer found
        genre = mediaMetadata.genre?.toString(),
        year = mediaMetadata.releaseYear?.takeIf { it != null && it > 0 },
        track = mediaMetadata.trackNumber?.takeIf { it != null && it > 0 },
        albumArtist = mediaMetadata.albumArtist?.toString()
    )
