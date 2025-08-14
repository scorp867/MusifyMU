package com.musify.mu.util

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.musify.mu.data.db.entities.Track

fun Track.toMediaItem(): MediaItem {
    val artwork = artUri?.let { android.net.Uri.parse(it) }

    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(mediaId) // mediaId stores the content:// URI string
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artwork)
                .build()
        )
        .build()
}

fun MediaItem.toTrack(): Track =
    Track(
        mediaId = mediaId,
        title = mediaMetadata.title?.toString() ?: "",
        artist = mediaMetadata.artist?.toString() ?: "",
        album = mediaMetadata.albumTitle?.toString() ?: "",
        durationMs = 0L,
        artUri = mediaMetadata.artworkUri?.toString(),
        albumId = null
    )
