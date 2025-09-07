package com.musify.mu.data.repo

import android.content.Context
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.*
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.data.media.LoadingState
import com.musify.mu.data.cache.CacheManager
import com.musify.mu.data.cache.CacheStrategy
import com.musify.mu.domain.service.MediaScanningService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced repository that follows proper repository pattern with:
 * - Clean separation between data sources
 * - Intelligent caching strategy
 * - Reactive data streams
 * - Error handling and fallback mechanisms
 */
@Singleton
class EnhancedLibraryRepository @Inject constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val dataManager: SpotifyStyleDataManager,
    private val cacheManager: CacheManager,
    private val cacheStrategy: CacheStrategy,
    private val mediaScanningService: MediaScanningService
) {
    
    // Expose reactive data streams
    val tracks: StateFlow<List<Track>> = dataManager.tracks
    val loadingState: StateFlow<LoadingState> = dataManager.loadingState
    
    /**
     * Get all tracks with intelligent caching
     */
    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        try {
            // Try cache first
            val cachedTracks = cacheManager.getCachedTracks()
            if (cachedTracks.isNotEmpty()) {
                return@withContext cachedTracks
            }
            
            // Fallback to data manager
            val tracks = dataManager.getAllTracks()
            if (tracks.isNotEmpty()) {
                cacheManager.cacheTracks(tracks)
                return@withContext tracks
            }
            
            // Final fallback to database
            database.dao().getAllTracks()
            
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting all tracks", e)
            emptyList()
        }
    }
    
    /**
     * Search tracks with caching
     */
    suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            // Check cache first
            val cachedResults = cacheManager.getCachedSearchResults(query)
            if (cachedResults != null) {
                return@withContext cachedResults
            }
            
            // Perform search
            val results = dataManager.searchTracks(query)
            
            // Cache results
            cacheManager.cacheSearchResults(query, results)
            
            results
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error searching tracks", e)
            emptyList()
        }
    }
    
    /**
     * Get track by media ID with caching
     */
    suspend fun getTrackByMediaId(mediaId: String): Track? = withContext(Dispatchers.IO) {
        try {
            // Try cache first
            val cachedTrack = cacheManager.getCachedTrack(mediaId)
            if (cachedTrack != null) {
                return@withContext cachedTrack
            }
            
            // Try data manager
            val track = dataManager.getAllTracks().find { it.mediaId == mediaId }
            if (track != null) {
                cacheManager.cacheTrack(track)
                return@withContext track
            }
            
            // Fallback to database
            database.dao().getTrack(mediaId)?.also {
                cacheManager.cacheTrack(it)
            }
            
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting track by media ID", e)
            null
        }
    }
    
    /**
     * Get tracks by album with caching
     */
    suspend fun getTracksByAlbum(albumId: Long?): List<Track> = withContext(Dispatchers.IO) {
        try {
            val allTracks = getAllTracks()
            allTracks.filter { it.albumId == albumId }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting tracks by album", e)
            emptyList()
        }
    }
    
    /**
     * Get tracks by artist
     */
    suspend fun getTracksByArtist(artistName: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val allTracks = getAllTracks()
            allTracks.filter { 
                it.artist.equals(artistName, ignoreCase = true) ||
                it.albumArtist?.equals(artistName, ignoreCase = true) == true
            }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting tracks by artist", e)
            emptyList()
        }
    }
    
    // Playlist operations with proper error handling
    
    suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            database.dao().getPlaylists()
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting playlists", e)
            emptyList()
        }
    }
    
    suspend fun createPlaylist(name: String, imageUri: String? = null): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val playlistId = database.dao().createPlaylist(Playlist(name = name, imageUri = imageUri))
            Result.success(playlistId)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error creating playlist", e)
            Result.failure(e)
        }
    }
    
    suspend fun deletePlaylist(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().deletePlaylist(id)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error deleting playlist", e)
            Result.failure(e)
        }
    }
    
    suspend fun renamePlaylist(id: Long, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().renamePlaylist(id, name)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error renaming playlist", e)
            Result.failure(e)
        }
    }
    
    suspend fun addToPlaylist(playlistId: Long, mediaIds: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = database.dao().getPlaylistTracks(playlistId).map { it.mediaId }.toSet()
            val toInsert = mediaIds.filter { it !in existing }
            if (toInsert.isEmpty()) return@withContext Result.success(Unit)
            
            val start = database.dao().getPlaylistTracks(playlistId).size
            val items = toInsert.mapIndexed { idx, id -> PlaylistItem(playlistId, id, start + idx) }
            database.dao().addItems(items)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error adding to playlist", e)
            Result.failure(e)
        }
    }
    
    suspend fun removeFromPlaylist(playlistId: Long, mediaId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().removeItem(playlistId, mediaId)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error removing from playlist", e)
            Result.failure(e)
        }
    }
    
    suspend fun getPlaylistTracks(playlistId: Long): List<Track> = withContext(Dispatchers.IO) {
        try {
            val databaseTracks = database.dao().getPlaylistTracks(playlistId)
            val currentTrackIds = tracks.value.map { it.mediaId }.toSet()
            
            // Filter out tracks that no longer exist
            if (currentTrackIds.isEmpty()) {
                databaseTracks // Return DB results if cache is empty
            } else {
                databaseTracks.filter { track -> currentTrackIds.contains(track.mediaId) }
            }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting playlist tracks", e)
            emptyList()
        }
    }
    
    suspend fun reorderPlaylist(playlistId: Long, orderedMediaIds: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (orderedMediaIds.isEmpty()) return@withContext Result.success(Unit)
            
            val items = orderedMediaIds.mapIndexed { index, mediaId ->
                PlaylistItem(playlistId = playlistId, mediaId = mediaId, position = index)
            }
            database.dao().addItems(items)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error reordering playlist", e)
            Result.failure(e)
        }
    }
    
    // Favorites operations
    
    suspend fun likeTrack(mediaId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().like(Like(mediaId))
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error liking track", e)
            Result.failure(e)
        }
    }
    
    suspend fun unlikeTrack(mediaId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().unlike(mediaId)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error unliking track", e)
            Result.failure(e)
        }
    }
    
    suspend fun isTrackLiked(mediaId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            database.dao().isLiked(mediaId)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error checking if track is liked", e)
            false
        }
    }
    
    suspend fun getFavorites(): List<Track> = withContext(Dispatchers.IO) {
        try {
            val databaseFavorites = database.dao().getFavorites()
            val currentTrackIds = tracks.value.map { it.mediaId }.toSet()
            databaseFavorites.filter { track -> currentTrackIds.contains(track.mediaId) }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting favorites", e)
            emptyList()
        }
    }
    
    // Recently played/added operations
    
    suspend fun getRecentlyPlayed(limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        try {
            val databaseRecent = database.dao().getRecentlyPlayed(limit)
            val currentTrackIds = tracks.value.map { it.mediaId }.toSet()
            databaseRecent.filter { track -> currentTrackIds.contains(track.mediaId) }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting recently played", e)
            emptyList()
        }
    }
    
    suspend fun getRecentlyAdded(limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        try {
            val databaseRecent = database.dao().getRecentlyAdded(limit)
            val currentTrackIds = tracks.value.map { it.mediaId }.toSet()
            databaseRecent.filter { track -> currentTrackIds.contains(track.mediaId) }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting recently added", e)
            emptyList()
        }
    }
    
    suspend fun recordTrackPlayed(mediaId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().insertOrUpdatePlayHistory(mediaId)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error recording track played", e)
            Result.failure(e)
        }
    }
    
    suspend fun clearRecentlyPlayed(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().clearPlayHistory()
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error clearing recently played", e)
            Result.failure(e)
        }
    }
    
    suspend fun clearRecentlyAdded(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Implementation depends on how recently added is tracked
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error clearing recently added", e)
            Result.failure(e)
        }
    }
    
    // Search history operations
    
    fun getSearchHistory(max: Int = 20): List<String> {
        return try {
            val searchPrefs = context.getSharedPreferences("search_history_prefs", Context.MODE_PRIVATE)
            val raw = searchPrefs.getString("history", "[]") ?: "[]"
            val arr = org.json.JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val q = arr.optString(i).orEmpty()
                if (q.isNotBlank()) out += q
            }
            out.take(max)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting search history", e)
            emptyList()
        }
    }
    
    fun addSearchHistory(query: String, max: Int = 20) {
        try {
            val q = query.trim()
            if (q.isBlank()) return
            
            val current = getSearchHistory(max).toMutableList()
            current.removeAll { it.equals(q, ignoreCase = true) }
            current.add(0, q)
            while (current.size > max) current.removeLastOrNull()
            
            val arr = org.json.JSONArray()
            current.forEach { arr.put(it) }
            
            val searchPrefs = context.getSharedPreferences("search_history_prefs", Context.MODE_PRIVATE)
            searchPrefs.edit().putString("history", arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error adding search history", e)
        }
    }
    
    fun clearSearchHistory() {
        try {
            val searchPrefs = context.getSharedPreferences("search_history_prefs", Context.MODE_PRIVATE)
            searchPrefs.edit().putString("history", "[]").apply()
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error clearing search history", e)
        }
    }
    
    // Metadata operations
    
    suspend fun updateTrackMetadata(track: Track): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().updateTrackMetadata(
                mediaId = track.mediaId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                genre = track.genre,
                year = track.year
            )
            // Update cache
            cacheManager.cacheTrack(track)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error updating track metadata", e)
            Result.failure(e)
        }
    }
    
    suspend fun updateTrackArtwork(mediaId: String, artUri: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.dao().updateTrackArt(mediaId, artUri)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error updating track artwork", e)
            Result.failure(e)
        }
    }
    
    // Library management operations
    
    suspend fun initializeLibrary(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mediaScanningService.initialize()
            dataManager.ensureInitialized()
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error initializing library", e)
            Result.failure(e)
        }
    }
    
    suspend fun forceRefreshLibrary(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mediaScanningService.forceRefresh()
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error force refreshing library", e)
            Result.failure(e)
        }
    }
    
    suspend fun onPermissionsChanged(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mediaScanningService.onPermissionsChanged()
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error handling permission changes", e)
            Result.failure(e)
        }
    }
    
    suspend fun addPermanentFile(uri: android.net.Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mediaScanningService.addPermanentFile(uri)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error adding permanent file", e)
            Result.failure(e)
        }
    }
    
    // Artwork operations
    
    suspend fun prefetchArtwork(trackUris: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mediaScanningService.prefetchArtworkForUI(trackUris)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error prefetching artwork", e)
            Result.failure(e)
        }
    }
    
    suspend fun getArtworkForTrack(trackUri: String): String? = withContext(Dispatchers.IO) {
        try {
            cacheStrategy.getCachedArtwork(trackUri)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error getting artwork for track", e)
            null
        }
    }
    
    // Cache management
    
    suspend fun clearAllCaches(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cacheStrategy.cleanupCache(com.musify.mu.data.cache.CleanupAggressiveness.COMPLETE)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedLibraryRepository", "Error clearing caches", e)
            Result.failure(e)
        }
    }
    
    fun getCacheStatistics(): com.musify.mu.data.cache.CacheStatistics {
        return cacheStrategy.getCacheStatistics()
    }
}