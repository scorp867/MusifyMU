package com.musify.mu.data.localfiles.proto

import android.net.Uri

/**
 * Protobuf-like data structures for local files query results
 * Based on Spotify's com.spotify.localfiles.proto.QueryResult architecture
 */
data class QueryResult(
    val localFiles: List<LocalFile> = emptyList(),
    val totalCount: Int = 0,
    val scanTimestamp: Long = System.currentTimeMillis()
) {
    fun toByteArray(): ByteArray {
        // Simple JSON serialization for now (could be replaced with actual protobuf)
        return toString().toByteArray()
    }
    
    companion object {
        fun fromByteArray(bytes: ByteArray): QueryResult? {
            return try {
                // Simple deserialization - in real Spotify this would be protobuf
                // For now, we'll just return a default instance
                QueryResult()
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Represents a single local audio file with metadata
 * Based on Spotify's LocalFile proto structure
 */
data class LocalFile(
    val path: String, // URI as string (content:// or file://)
    val metadata: LocalFileMetadata,
    val imageState: ImageState = ImageState.UNKNOWN
)

/**
 * Metadata extracted from audio files
 * Based on MediaMetadataRetriever keys used by Spotify
 */
data class LocalFileMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val duration: Long? = null, // in milliseconds
    val year: Int? = null,
    val trackNumber: Int? = null,
    val genre: String? = null,
    val mimeType: String? = null,
    val displayName: String? = null,
    val dateAdded: Long? = null, // timestamp
    val albumId: Long? = null,
    val isMusic: Boolean = true,
    val isAlarm: Boolean = false,
    val isRingtone: Boolean = false,
    val isNotification: Boolean = false,
    val isPodcast: Boolean = false,
    val isAudiobook: Boolean = false,
    val isPending: Boolean = false,
    val isTrashed: Boolean = false,
    val hasEmbeddedArtwork: Boolean = false // True if track has embedded album art (ID3/APIC)
)

/**
 * Album art availability state
 * Based on Spotify's image state tracking
 */
enum class ImageState {
    UNKNOWN,
    HAS_IMAGE,
    NO_IMAGE
}

/**
 * Response structure for the LocalFiles API
 * Based on Spotify's LocalTracksResponse
 */
data class LocalTracksResponse(
    val tracks: List<LocalTrack> = emptyList(),
    val albums: List<LocalAlbum> = emptyList(),
    val artists: List<LocalArtist> = emptyList(),
    val totalTracks: Int = 0
)

/**
 * Track model for UI consumption
 * Mapped from LocalFile via LocalFilesEsperantoMapper equivalent
 */
data class LocalTrack(
    val id: String, // URI as ID
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val duration: Long,
    val year: Int?,
    val trackNumber: Int?,
    val genre: String?,
    val albumId: Long?,
    val dateAdded: Long,
    val artworkUri: String? = null, // Will be populated on-demand
    val hasEmbeddedArtwork: Boolean = false
)

/**
 * Album model for UI consumption
 */
data class LocalAlbum(
    val id: Long,
    val name: String,
    val artist: String,
    val trackCount: Int,
    val year: Int?,
    val artworkUri: String? = null
)

/**
 * Artist model for UI consumption
 */
data class LocalArtist(
    val name: String,
    val albumCount: Int,
    val trackCount: Int
)

/**
 * Options for MediaStore scanning
 * Based on Spotify's MediaStoreReaderOptions
 */
data class MediaStoreReaderOptions(
    val durationMin: Long = 30000, // 30 seconds minimum
    val includeAlarms: Boolean = false,
    val includeRingtones: Boolean = false,
    val includeNotifications: Boolean = false,
    val includePodcasts: Boolean = true,
    val includeAudiobooks: Boolean = true,
    val enableDocumentTreeScanning: Boolean = true
)
