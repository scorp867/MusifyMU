package com.musify.mu.data.db

import androidx.room.*
import com.musify.mu.data.db.entities.*

@Dao
@JvmSuppressWildcards
interface AppDao {

    // Tracks (cached from MediaStore)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<Track>)

    @Query("SELECT * FROM track ORDER BY title COLLATE NOCASE")
    suspend fun getAllTracks(): List<Track>

    // Paging support
    @Query("SELECT * FROM track ORDER BY title COLLATE NOCASE LIMIT :limit OFFSET :offset")
    suspend fun getTracksPaged(limit: Int, offset: Int): List<Track>

    @Query("SELECT * FROM track WHERE title LIKE :q OR artist LIKE :q OR album LIKE :q ORDER BY title COLLATE NOCASE")
    suspend fun searchTracks(q: String): List<Track>

    @Query("SELECT * FROM track WHERE mediaId = :mediaId")
    suspend fun getTrack(mediaId: String): Track?

    // Recently added and play history
    @Query("SELECT * FROM track ORDER BY dateAddedSec DESC LIMIT :limit")
    suspend fun getRecentlyAdded(limit: Int): List<Track>

    // Play history insertion - record ONLY the first time a track is ever played
    @Query("""
        INSERT OR IGNORE INTO play_history (mediaId, playedAt)
        SELECT :mediaId, :playedAt
        WHERE NOT EXISTS (
            SELECT 1 FROM play_history WHERE mediaId = :mediaId
        )
    """)
    suspend fun insertPlayHistoryIfNotRecent(mediaId: String, playedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlayHistory(entry: PlayHistory)

    // Distinct recently played by latest play time per mediaId
    @Query(
        """
        SELECT t.* FROM track t
        JOIN (
            SELECT mediaId, MAX(playedAt) AS lastPlayed
            FROM play_history
            GROUP BY mediaId
            ORDER BY lastPlayed DESC
            LIMIT :limit
        ) h ON t.mediaId = h.mediaId
        ORDER BY h.lastPlayed DESC
        """
    )
    suspend fun getRecentlyPlayed(limit: Int): List<Track>

    // Clear entire play history (used by UI 'Clear' action)
    @Query("DELETE FROM play_history")
    suspend fun clearPlayHistory()


    // Playlists
    @Insert
    suspend fun createPlaylist(playlist: Playlist): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("UPDATE playlist SET imageUri = :imageUri WHERE id = :id")
    suspend fun updatePlaylistImage(id: Long, imageUri: String?)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItems(items: List<PlaylistItem>)

    @Query("DELETE FROM playlist_item WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun removeItem(playlistId: Long, mediaId: String)

    @Query("SELECT t.* FROM track t JOIN playlist_item p ON t.mediaId = p.mediaId WHERE p.playlistId = :playlistId ORDER BY p.position")
    suspend fun getPlaylistTracks(playlistId: Long): List<Track>

    @Query("SELECT * FROM playlist ORDER BY createdAt DESC")
    suspend fun getPlaylists(): List<Playlist>

    // Likes / Favorites
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun like(item: Like)

    @Query("DELETE FROM likes WHERE mediaId = :mediaId")
    suspend fun unlike(mediaId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM likes WHERE mediaId = :mediaId)")
    suspend fun isLiked(mediaId: String): Boolean

    // Favorites with optional manual order
    @Query(
        """
        SELECT t.* FROM track t
        JOIN likes l ON t.mediaId = l.mediaId
        LEFT JOIN favorites_order fo ON fo.mediaId = t.mediaId
        ORDER BY CASE WHEN fo.position IS NULL THEN 1 ELSE 0 END, fo.position ASC, l.likedAt DESC
        """
    )
    suspend fun getFavorites(): List<Track>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavoriteOrder(order: List<FavoritesOrder>)

    @Query("DELETE FROM favorites_order WHERE mediaId = :mediaId")
    suspend fun clearFavoriteOrderFor(mediaId: String)

    // Lyrics mapping
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyricsMap(map: LyricsMap)

    @Query("SELECT * FROM lyrics_map WHERE mediaId = :mediaId")
    suspend fun getLyricsMap(mediaId: String): LyricsMap?

    // Artwork helpers
    @Query("UPDATE track SET artUri = :art WHERE mediaId = :mediaId")
    suspend fun updateTrackArt(mediaId: String, art: String?)

    @Query("SELECT * FROM track WHERE artUri IS NULL OR artUri = ''")
    suspend fun getTracksMissingArt(): List<Track>

    // Album info helpers for embedded art extraction
    @Query("""
        SELECT album, artist, COUNT(*) as trackCount 
        FROM track 
        GROUP BY album, artist 
        HAVING COUNT(*) > 1
    """)
    suspend fun getAlbumsWithMultipleTracks(): List<AlbumInfo>

    @Query("SELECT * FROM track WHERE album = :album AND artist = :artist")
    suspend fun getTracksByAlbum(album: String, artist: String): List<Track>
}

// Simple data class for album info
data class AlbumInfo(
    val album: String,
    val artist: String,
    val trackCount: Int
)
