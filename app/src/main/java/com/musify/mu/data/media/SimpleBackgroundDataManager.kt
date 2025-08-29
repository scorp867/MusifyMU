package com.musify.mu.data.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ultra-responsive data manager optimized for smooth UI performance.
 * Features:
 * - Fast initial load without artwork extraction
 * - Background artwork loading for visible items only
 * - Immediate UI responsiveness
 * - Memory-efficient caching
 */
class SimpleBackgroundDataManager private constructor(
    private val context: Context,
    private val db: AppDatabase
) {
    
    companion object {
        private const val TAG = "SimpleBackgroundDataManager"
        
        @Volatile
        private var INSTANCE: SimpleBackgroundDataManager? = null
        
        fun get(context: Context, db: AppDatabase): SimpleBackgroundDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SimpleBackgroundDataManager(context, db).also { INSTANCE = it }
            }
        }
    }
    
    // In-memory cache - this is the single source of truth
    private val _cachedTracks = MutableStateFlow<List<Track>>(emptyList())
    val cachedTracks: StateFlow<List<Track>> = _cachedTracks.asStateFlow()
    
    // Loading state
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()
    
    // Scanner instance
    private val scanner = SimpleMediaStoreScanner(context, db)
    
    // Note: Artwork is handled by ArtworkManager on-demand for better UI responsiveness
    
    // Track if we've already initialized
    private var isInitialized = false
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob())
    
    /**
     * Ultra-fast initialization without artwork extraction for immediate UI response
     */
    suspend fun initializeOnAppLaunch() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, using cached data")
            return
        }
        
        try {
            _loadingState.value = LoadingState.Loading("Scanning music library...")
            Log.d(TAG, "Starting FAST app launch initialization (no artwork extraction)...")
            
            // First, try to load from database cache for instant UI
            val cachedTracksFromDb = db.dao().getAllTracks()
            if (cachedTracksFromDb.isNotEmpty()) {
                _cachedTracks.value = cachedTracksFromDb
                Log.d(TAG, "Loaded ${cachedTracksFromDb.size} tracks from database cache for instant UI")
            }
            
            // Then, perform fast MediaStore scan without artwork extraction
            val tracks = scanner.scanTracksWithoutArtwork()
            Log.d(TAG, "Fast scan completed: ${tracks.size} tracks (no artwork extraction)")
            
            if (tracks.isNotEmpty()) {
                // Store in memory cache - this is what the UI will use
                _cachedTracks.value = tracks
                Log.d(TAG, "Tracks cached in memory: ${_cachedTracks.value.size}")
                
                // Log sample track details for debugging
                val sampleTrack = tracks.first()
                Log.d(TAG, "Sample track - Title: ${sampleTrack.title}, Artist: ${sampleTrack.artist}, Album: ${sampleTrack.album}, AlbumID: ${sampleTrack.albumId}")
                
                // Cache to database for persistence (fast, no artwork)
                Log.d(TAG, "Caching ${tracks.size} tracks to database for persistence...")
                db.dao().upsertTracks(tracks)
                
                _loadingState.value = LoadingState.Completed(tracks.size)
                Log.d(TAG, "Fast initialization completed: ${tracks.size} tracks cached (artwork loaded on-demand)")
            } else {
                _loadingState.value = LoadingState.Completed(0)
                Log.w(TAG, "No tracks found during initialization")
            }
            
            // Register content observer for real-time updates ONLY
            scanner.registerContentObserver {
                Log.d(TAG, "MediaStore changed - refreshing cache...")
                scope.launch {
                    refreshTracksFromMediaStore()
                }
            }
            
            isInitialized = true
            Log.d(TAG, "Data manager initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during app launch initialization", e)
            _loadingState.value = LoadingState.Error(e.message ?: "Initialization failed")
        }
    }
    
    /**
     * Ensure data manager is initialized - this is called by the UI when needed
     */
    suspend fun ensureInitialized() {
        if (!isInitialized) {
            Log.d(TAG, "Data manager not initialized, initializing now...")
            initializeOnAppLaunch()
        }
    }
    
    /**
     * Get all tracks from memory cache - NO database query
     */
    fun getAllTracks(): List<Track> {
        val tracks = _cachedTracks.value
        Log.d(TAG, "getAllTracks called, returning ${tracks.size} tracks")
        return tracks
    }
    
    /**
     * Get tracks by album from memory cache - NO database query
     */
    fun getTracksByAlbum(albumId: Long?): List<Track> {
        return _cachedTracks.value.filter { it.albumId == albumId }
    }
    
    /**
     * Search tracks in memory cache - NO database query
     */
    fun searchTracks(query: String): List<Track> {
        if (query.isBlank()) return _cachedTracks.value
        
        val lowercaseQuery = query.lowercase()
        return _cachedTracks.value.filter { track ->
            track.title.lowercase().contains(lowercaseQuery) ||
            track.artist.lowercase().contains(lowercaseQuery) ||
            track.album.lowercase().contains(lowercaseQuery)
        }
    }
    
    /**
     * Get unique albums from memory cache - NO database query
     */
    fun getUniqueAlbums(): List<AlbumInfo> {
        return _cachedTracks.value
            .groupBy { it.albumId }
            .map { (albumId, tracks) ->
                val firstTrack = tracks.first()
                AlbumInfo(
                    albumId = albumId ?: 0L,
                    albumName = firstTrack.album,
                    artistName = firstTrack.artist,
                    trackCount = tracks.size,
                    // Artwork will be loaded on-demand when needed
                    artUri = firstTrack.artUri
                )
            }
            .sortedBy { it.albumName.lowercase() }
    }
    
    /**
     * Fast refresh when MediaStore changes - no artwork extraction for UI responsiveness
     */
    private suspend fun refreshTracksFromMediaStore() {
        try {
            Log.d(TAG, "Fast refresh due to MediaStore change (no artwork extraction)...")
            
            // Fast scan without artwork extraction for immediate response
            val newTracks = scanner.scanTracksWithoutArtwork()
            Log.d(TAG, "Refresh scan completed: ${newTracks.size} tracks (fast mode)")
            
            // Update memory cache
            _cachedTracks.value = newTracks
            
            // Update database for persistence (fast, no artwork)
            if (newTracks.isNotEmpty()) {
                db.dao().upsertTracks(newTracks)
                Log.d(TAG, "Cache and database updated with ${newTracks.size} tracks (fast mode)")
                
                // Clean up orphaned database entries for deleted tracks
                try {
                    val currentTrackIds = newTracks.map { it.mediaId }.toSet()
                    val allDatabaseTracks = db.dao().getAllTracks()
                    val orphanedTrackIds = allDatabaseTracks.map { it.mediaId }.filter { !currentTrackIds.contains(it) }
                    
                    if (orphanedTrackIds.isNotEmpty()) {
                        Log.d(TAG, "Cleaning up ${orphanedTrackIds.size} orphaned tracks from database")
                        // Note: This would require additional DAO methods to clean up related data
                        // For now, the filtering approach in LibraryRepository handles this
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up orphaned tracks", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing tracks", e)
        }
    }
    
    // Artwork is loaded on-demand via ArtworkManager for better performance
    
    /**
     * Force refresh (for manual refresh button if needed)
     */
    fun forceRefresh() {
        if (!isInitialized) {
            Log.w(TAG, "Cannot force refresh before initialization")
            return
        }
        
        scope.launch {
            refreshTracksFromMediaStore()
        }
    }
    
    /**
     * Clear cache (for testing or memory management)
     */
    fun clearCache() {
        _cachedTracks.value = emptyList()
        Log.d(TAG, "Memory cache cleared")
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        scanner.unregisterContentObserver()
        Log.d(TAG, "Data manager cleaned up, content observer unregistered")
    }
    

}

/**
 * Album information for efficient display
 */
data class AlbumInfo(
    val albumId: Long,
    val albumName: String,
    val artistName: String,
    val trackCount: Int,
    val artUri: String?
)

/**
 * Loading states for UI
 */
sealed class LoadingState {
    object Idle : LoadingState()
    data class Loading(val message: String) : LoadingState()
    data class Completed(val trackCount: Int) : LoadingState()
    data class Error(val message: String) : LoadingState()
}

