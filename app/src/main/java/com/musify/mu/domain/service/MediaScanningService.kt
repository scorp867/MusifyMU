package com.musify.mu.domain.service

import android.content.Context
import android.util.Log
import com.musify.mu.data.localfiles.LocalFilesService
import com.musify.mu.data.localfiles.ScanState
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.data.cache.CacheManager
import com.musify.mu.util.SpotifyStyleArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified service for media scanning and artwork extraction.
 * Eliminates duplication by centralizing all scanning operations.
 */
@Singleton
class MediaScanningService @Inject constructor(
    private val context: Context,
    private val localFilesService: LocalFilesService,
    private val dataManager: SpotifyStyleDataManager,
    private val cacheManager: CacheManager
) {
    companion object {
        private const val TAG = "MediaScanningService"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    
    // Expose scan state from LocalFilesService
    val scanState: StateFlow<ScanState> = localFilesService.scanState
    
    private var isInitialized = false
    
    /**
     * Initialize the scanning service
     */
    suspend fun initialize() = scanMutex.withLock {
        if (isInitialized) {
            Log.d(TAG, "MediaScanningService already initialized")
            return
        }
        
        try {
            Log.d(TAG, "Initializing MediaScanningService...")
            
            // Initialize LocalFilesService
            localFilesService.initialize()
            
            // Initialize SpotifyStyleArtworkLoader
            SpotifyStyleArtworkLoader.initialize(context)
            
            // Set up automatic artwork extraction when tracks change
            scope.launch {
                combine(
                    localFilesService.tracks,
                    localFilesService.scanState
                ) { tracks, scanState ->
                    Pair(tracks, scanState)
                }.collect { (tracks, scanState) ->
                    if (scanState is ScanState.Completed && tracks.isNotEmpty()) {
                        // Automatically extract artwork for new tracks
                        extractArtworkForTracks(tracks.map { it.id })
                    }
                }
            }
            
            isInitialized = true
            Log.d(TAG, "MediaScanningService initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaScanningService", e)
            throw e
        }
    }
    
    /**
     * Force refresh the entire library
     */
    suspend fun forceRefresh() {
        try {
            Log.d(TAG, "Force refreshing media library...")
            localFilesService.forceRefresh()
            // Clear caches to ensure fresh data
            cacheManager.clearAllCaches()
            SpotifyStyleArtworkLoader.clearCaches()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force refresh library", e)
            throw e
        }
    }
    
    /**
     * Handle permission changes
     */
    suspend fun onPermissionsChanged() {
        try {
            Log.d(TAG, "Permissions changed, updating scan state...")
            localFilesService.onPermissionsChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle permission changes", e)
            throw e
        }
    }
    
    /**
     * Add permanent file (e.g., from SAF)
     */
    suspend fun addPermanentFile(uri: android.net.Uri) {
        try {
            Log.d(TAG, "Adding permanent file: $uri")
            localFilesService.addPermanentFile(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add permanent file", e)
            throw e
        }
    }
    
    /**
     * Extract artwork for specific tracks (centralized artwork extraction)
     */
    suspend fun extractArtworkForTracks(trackUris: List<String>) = scanMutex.withLock {
        if (trackUris.isEmpty()) return
        
        try {
            Log.d(TAG, "Extracting artwork for ${trackUris.size} tracks")
            
            // Process in batches to avoid overwhelming the system
            val batchSize = 20
            trackUris.chunked(batchSize).forEach { batch ->
                scope.launch {
                    batch.forEach { trackUri ->
                        try {
                            // Check if artwork already exists in cache
                            val cachedArtwork = cacheManager.getCachedArtwork(trackUri)
                            if (cachedArtwork == null) {
                                // Extract artwork using SpotifyStyleArtworkLoader
                                val track = dataManager.getAllTracks().find { it.mediaId == trackUri }
                                val hasEmbeddedArt = track?.hasEmbeddedArtwork
                                
                                val artworkPath = SpotifyStyleArtworkLoader.loadArtwork(trackUri, hasEmbeddedArt)
                                if (artworkPath != null) {
                                    // Cache the artwork path
                                    cacheManager.cacheArtwork(trackUri, artworkPath)
                                    Log.d(TAG, "Extracted and cached artwork for: $trackUri")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to extract artwork for $trackUri", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract artwork for tracks", e)
        }
    }
    
    /**
     * Prefetch artwork for visible tracks (optimized for UI)
     */
    suspend fun prefetchArtworkForUI(trackUris: List<String>, priority: ArtworkPriority = ArtworkPriority.NORMAL) {
        if (trackUris.isEmpty()) return
        
        try {
            Log.d(TAG, "Prefetching artwork for UI: ${trackUris.size} tracks (priority: $priority)")
            
            val limitedUris = when (priority) {
                ArtworkPriority.HIGH -> trackUris.take(50) // More for high priority
                ArtworkPriority.NORMAL -> trackUris.take(20)
                ArtworkPriority.LOW -> trackUris.take(10)
            }
            
            // Use existing artwork extraction but with UI-optimized batching
            extractArtworkForTracks(limitedUris)
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prefetch artwork for UI", e)
        }
    }
    
    /**
     * Get scanning statistics
     */
    fun getScanningStats(): ScanningStats {
        val artworkStats = SpotifyStyleArtworkLoader.getCacheStats()
        val cacheStats = cacheManager.getCacheStats()
        
        return ScanningStats(
            totalTracks = dataManager.getAllTracks().size,
            artworkCacheSize = artworkStats.memoryCacheSize,
            artworkCacheMaxSize = artworkStats.memoryCacheMaxSize,
            failedArtworkExtractions = artworkStats.failedExtractions,
            trackCacheSize = cacheStats.trackCacheSize,
            trackCacheHitRate = if (cacheStats.trackCacheMissCount + cacheStats.trackCacheHitCount > 0) {
                cacheStats.trackCacheHitCount.toDouble() / (cacheStats.trackCacheMissCount + cacheStats.trackCacheHitCount)
            } else 0.0
        )
    }
    
    /**
     * Clear all caches
     */
    suspend fun clearAllCaches() {
        try {
            Log.d(TAG, "Clearing all caches...")
            cacheManager.clearAllCaches()
            SpotifyStyleArtworkLoader.clearCaches()
            dataManager.clearCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear caches", e)
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        localFilesService.cleanup()
        SpotifyStyleArtworkLoader.clearCaches()
        Log.d(TAG, "MediaScanningService cleaned up")
    }
}

enum class ArtworkPriority {
    HIGH, NORMAL, LOW
}

data class ScanningStats(
    val totalTracks: Int,
    val artworkCacheSize: Int,
    val artworkCacheMaxSize: Int,
    val failedArtworkExtractions: Int,
    val trackCacheSize: Int,
    val trackCacheHitRate: Double
)