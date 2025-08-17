package com.musify.mu.data.repo

import android.content.Context
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.db.entities.*
import com.musify.mu.data.media.SimpleBackgroundDataManager
import com.musify.mu.data.media.LoadingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class LibraryRepository private constructor(private val context: Context, private val db: AppDatabase) {

    // Create data manager instance - this will be initialized by the app
    val dataManager by lazy { 
        SimpleBackgroundDataManager.get(context, db)
    }

    // Background loading progress
    val loadingState: StateFlow<LoadingState> = dataManager.loadingState

    // Fast access to all tracks from cache - fallback to database if cache is empty
    fun getAllTracks(): List<Track> {
        val cachedTracks = dataManager.getAllTracks()
        return if (cachedTracks.isNotEmpty()) {
            cachedTracks
        } else {
            // Fallback to database if cache is empty (e.g., after app restart)
            try {
                runBlocking { db.dao().getAllTracks() }
            } catch (e: Exception) {
                android.util.Log.w("LibraryRepository", "Failed to get tracks from database", e)
                emptyList()
            }
        }
    }
    
    // Search in cached data - NO database query
    fun search(q: String): List<Track> = dataManager.searchTracks(q)
    
    // Get playlists from database (these are user-created, not cached)
    suspend fun playlists(): List<Playlist> = db.dao().getPlaylists()
    
    // Playlist management operations
    suspend fun createPlaylist(name: String, imageUri: String? = null): Long = db.dao().createPlaylist(Playlist(name = name, imageUri = imageUri))
    suspend fun renamePlaylist(id: Long, name: String) = db.dao().renamePlaylist(id, name)
    suspend fun deletePlaylist(id: Long) = db.dao().deletePlaylist(id)
    
    suspend fun addToPlaylist(playlistId: Long, mediaIds: List<String>) {
        val existing = db.dao().getPlaylistTracks(playlistId).map { it.mediaId }.toSet()
        val toInsert = mediaIds.filter { it !in existing }
        if (toInsert.isEmpty()) return
        val start = db.dao().getPlaylistTracks(playlistId).size
        val items = toInsert.mapIndexed { idx, id -> PlaylistItem(playlistId, id, start + idx) }
        db.dao().addItems(items)
    }
    
    suspend fun removeFromPlaylist(playlistId: Long, mediaId: String) = db.dao().removeItem(playlistId, mediaId)
    suspend fun playlistTracks(playlistId: Long): List<Track> {
        val databaseTracks = db.dao().getPlaylistTracks(playlistId)
        val currentTrackIds = dataManager.cachedTracks.value.map { it.mediaId }.toSet()
        return databaseTracks.filter { track -> currentTrackIds.contains(track.mediaId) }
    }
    
    // Like/unlike operations
    suspend fun like(mediaId: String) = db.dao().like(Like(mediaId))
    suspend fun unlike(mediaId: String) = db.dao().unlike(mediaId)
    suspend fun isLiked(mediaId: String): Boolean = db.dao().isLiked(mediaId)
    
    // Get favorites from database (these are user-created, not cached)
    // Filter out deleted tracks by checking against current cached tracks
    suspend fun favorites(): List<Track> {
        val databaseFavorites = db.dao().getFavorites()
        val currentTrackIds = dataManager.cachedTracks.value.map { it.mediaId }.toSet()
        return databaseFavorites.filter { track -> currentTrackIds.contains(track.mediaId) }
    }
    suspend fun saveFavoritesOrder(order: List<FavoritesOrder>) = db.dao().upsertFavoriteOrder(order)

    // Get track by media ID from cached data - optimized to avoid multiple getAllTracks calls
    fun getTrackByMediaId(mediaId: String): Track? {
        // Use the cached tracks directly from the data manager's StateFlow
        // This avoids calling getAllTracks() repeatedly
        return dataManager.cachedTracks.value.find { it.mediaId == mediaId }
    }

    // Get recently added/played from database (these track user interactions, not cached)
    // Filter out deleted tracks by checking against current cached tracks
    suspend fun recentlyAdded(limit: Int = 20): List<Track> {
        val databaseRecent = db.dao().getRecentlyAdded(limit)
        val currentTrackIds = dataManager.cachedTracks.value.map { it.mediaId }.toSet()
        return databaseRecent.filter { track -> currentTrackIds.contains(track.mediaId) }
    }
    
    suspend fun recentlyPlayed(limit: Int = 20): List<Track> {
        val databaseRecent = db.dao().getRecentlyPlayed(limit)
        val currentTrackIds = dataManager.cachedTracks.value.map { it.mediaId }.toSet()
        return databaseRecent.filter { track -> currentTrackIds.contains(track.mediaId) }
    }
    suspend fun recordPlayed(mediaId: String) = db.dao().insertPlayHistoryIfNotRecent(mediaId)

    companion object {
        @Volatile private var INSTANCE: LibraryRepository? = null
        fun get(context: Context): LibraryRepository =
            INSTANCE ?: synchronized(this) {
                val db = DatabaseProvider.get(context)
                INSTANCE ?: LibraryRepository(context.applicationContext, db).also { 
                    INSTANCE = it 
                }
            }
    }
}
