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

    @Query("SELECT * FROM track WHERE title LIKE :q OR artist LIKE :q OR album LIKE :q ORDER BY title COLLATE NOCASE")
    suspend fun searchTracks(q: String): List<Track>

    @Query("SELECT * FROM track WHERE mediaId = :mediaId")
    suspend fun getTrack(mediaId: String): Track?

    // Recently added and play history
    @Query("SELECT * FROM track ORDER BY dateAddedSec DESC LIMIT :limit")
    suspend fun getRecentlyAdded(limit: Int): List<Track>

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

    // Playlists
    @Insert
    suspend fun createPlaylist(playlist: Playlist): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

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

    @Query("SELECT t.* FROM track t JOIN likes l ON t.mediaId = l.mediaId ORDER BY l.likedAt DESC")
    suspend fun getFavorites(): List<Track>

    // Lyrics mapping
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyricsMap(map: LyricsMap)

    @Query("SELECT * FROM lyrics_map WHERE mediaId = :mediaId")
    suspend fun getLyricsMap(mediaId: String): LyricsMap?
}
