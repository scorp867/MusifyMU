package com.musify.mu.util

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository

/**
 * Convert Track to MediaItem for ExoPlayer.
 * Includes pre-extracted artwork URI for UI components.
 */
fun Track.toMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(mediaId) // mediaId stores the content:// URI string
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                // Include pre-extracted artwork URI so UI components can use it
                .setArtworkUri(artUri?.let { android.net.Uri.parse(it) })
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

/**
 * Resolve a full Track for a MediaItem by consulting the repository first,
 * then falling back to converting the MediaItem metadata.
 */
fun resolveTrack(repo: LibraryRepository, item: MediaItem): Track {
    return repo.getTrackByMediaId(item.mediaId) ?: item.toTrack()
}
