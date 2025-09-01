package com.musify.mu.data.media

import android.content.Context
import android.util.Log
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import com.musify.mu.util.OptimizedArtworkLoader
import com.musify.mu.util.MetadataPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * Optimized data manager with Spotify-like performance:
 * - Immediate UI response with cached data
 * - Progressive loading in background
 * - Intelligent artwork prefetching
 * - Minimal UI blocking
 */
class OptimizedDataManager private constructor(
    private val context: Context,
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "OptimizedDataManager"
        
        @Volatile
        private var INSTANCE: OptimizedDataManager? = null
        
        fun get(context: Context, db: AppDatabase): OptimizedDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OptimizedDataManager(context, db).also { INSTANCE = it }
            }
        }
    }
    
    // Fast in-memory cache
    private val _cachedTracks = MutableStateFlow<List<Track>>(emptyList())
    val cachedTracks: StateFlow<List<Track>> = _cachedTracks.asStateFlow()
    
    // Loading state
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()
    
    // Background scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Scanner
    private val scanner = OptimizedMediaStoreScanner(context, db)
    
    // Expose database for metadata operations
    val database: AppDatabase = db
    
    private var isInitialized = false
    
    /**
     * Initialize with immediate UI response
     */
    suspend fun initializeOnAppLaunch() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        
        try {
            // First, load from database cache for immediate UI response
            val cachedDbTracks = withContext(Dispatchers.IO) { db.dao().getAllTracks() }
            if (cachedDbTracks.isNotEmpty()) {
                _cachedTracks.value = cachedDbTracks
                Log.d(TAG, "Loaded ${cachedDbTracks.size} tracks from database cache")
                
                // Prefetch artwork for first few tracks
                val firstBatch = cachedDbTracks.take(20).map { it.mediaId }
                OptimizedArtworkLoader.prefetch(firstBatch)
            }
            
            // Then scan MediaStore in background for updates
            _loadingState.value = LoadingState.Loading("Scanning for new music...")
            
            scope.launch {
                val freshTracks = scanner.scanTracksOptimized()
                
                // Apply custom metadata to scanned tracks
                val tracksWithCustomMetadata = MetadataPersistence.applyCustomMetadataToTracks(context, freshTracks)
                
                withContext(Dispatchers.Main) {
                    _cachedTracks.value = tracksWithCustomMetadata
                    _loadingState.value = LoadingState.Completed(tracksWithCustomMetadata.size)
                    Log.d(TAG, "Background scan completed: ${tracksWithCustomMetadata.size} tracks")
                }
                
                // Cache to database
                if (tracksWithCustomMetadata.isNotEmpty()) {
                    db.dao().upsertTracks(tracksWithCustomMetadata)
                    
                    // Progressive artwork prefetching
                    tracksWithCustomMetadata.chunked(50).forEachIndexed { index, chunk ->
                        delay(index * 100L) // Stagger to avoid overwhelming the system
                        OptimizedArtworkLoader.prefetch(chunk.map { it.mediaId })
                    }
                }
            }
            
            // Register content observer
            scanner.registerContentObserver {
                scope.launch {
                    refreshTracksFromMediaStore()
                }
            }
            
            isInitialized = true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during initialization", e)
            _loadingState.value = LoadingState.Error(e.message ?: "Initialization failed")
        }
    }
    
    /**
     * Get all tracks from memory cache
     */
    fun getAllTracks(): List<Track> = _cachedTracks.value
    
    /**
     * Search tracks in memory cache
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
     * Get tracks by album from memory cache
     */
    fun getTracksByAlbum(albumId: Long?): List<Track> {
        return _cachedTracks.value.filter { it.albumId == albumId }
    }
    
    /**
     * Get unique albums from memory cache
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
                    artUri = firstTrack.artUri
                )
            }
            .sortedBy { it.albumName.lowercase() }
    }
    
    /**
     * Refresh tracks when MediaStore changes
     */
    private suspend fun refreshTracksFromMediaStore() {
        try {
            Log.d(TAG, "Refreshing tracks due to MediaStore change...")
            val newTracks = scanner.scanTracksOptimized()
            
            // Apply custom metadata to refreshed tracks
            val tracksWithCustomMetadata = MetadataPersistence.applyCustomMetadataToTracks(context, newTracks)
            
            withContext(Dispatchers.Main) {
                _cachedTracks.value = tracksWithCustomMetadata
            }
            
            if (tracksWithCustomMetadata.isNotEmpty()) {
                db.dao().upsertTracks(tracksWithCustomMetadata)
                Log.d(TAG, "Refresh completed: ${tracksWithCustomMetadata.size} tracks")
                
                // Prefetch artwork for new tracks
                val newTrackIds = tracksWithCustomMetadata.map { it.mediaId }
                OptimizedArtworkLoader.prefetch(newTrackIds.take(50))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing tracks", e)
        }
    }
    
    /**
     * Prefetch artwork for visible tracks
     */
    fun prefetchArtwork(tracks: List<Track>) {
        val mediaUris = tracks.map { it.mediaId }
        OptimizedArtworkLoader.prefetch(mediaUris)
    }
    
    /**
     * Update cached tracks (for metadata edits)
     */
    fun updateCachedTracks(tracks: List<Track>) {
        _cachedTracks.value = tracks
    }
    
    /**
     * Ensure data manager is initialized
     */
    suspend fun ensureInitialized() {
        if (!isInitialized) {
            Log.d(TAG, "Data manager not initialized, initializing now...")
            initializeOnAppLaunch()
        }
    }
}

// Album info data class
data class AlbumInfo(
    val albumId: Long,
    val albumName: String,
    val artistName: String,
    val trackCount: Int,
    val artUri: String?
)