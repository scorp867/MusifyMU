package com.musify.mu.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track")
data class Track(
    @PrimaryKey val mediaId: String, // contentUri string
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: String?, // For lazy loading with Coil/Glide
    val albumId: Long?,
    val dateAddedSec: Long = 0, // unix seconds from MediaStore
    // Additional MediaStore fields for complete metadata
    val artistId: Long? = null,
    val genre: String? = null,
    val year: Int? = null,
    val track: Int? = null, // track number
    val mimeType: String? = null,
    val size: Long = 0,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val isMusic: Int = 1,
    val dateModified: Long = 0,
    val displayName: String? = null,
    val relativePath: String? = null,
    val albumArtist: String? = null,
    val hasEmbeddedArtwork: Boolean = false // True if track has embedded album art
)

@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val imageUri: String? = null
)

@Entity(tableName = "playlist_item", primaryKeys = ["playlistId", "mediaId"])
data class PlaylistItem(
    val playlistId: Long,
    val mediaId: String,
    val position: Int
)

@Entity(tableName = "likes")
data class Like(
    @PrimaryKey val mediaId: String,
    val likedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorites_order")
data class FavoritesOrder(
    @PrimaryKey val mediaId: String,
    val position: Int
)

@Entity(tableName = "lyrics_map")
data class LyricsMap(
    @PrimaryKey val mediaId: String,
    val type: String, // "lrc", "text", "embedded"
    val uriOrText: String,
    val offsetMs: Long = 0
)

@Entity(tableName = "play_history",
    primaryKeys = ["mediaId", "playedAt"]
)
data class PlayHistory(
    val mediaId: String,
    val playedAt: Long = System.currentTimeMillis()
)
