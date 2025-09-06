package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.util.SpotifyStyleArtworkLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel for managing artwork loading and caching.
 * Prevents duplicate artwork extraction and provides centralized caching.
 */
@HiltViewModel
class ArtworkViewModel @Inject constructor(
    private val dataManager: SpotifyStyleDataManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "ArtworkViewModel"
        private const val PREFETCH_BATCH_SIZE = 20
    }
    
    // Cache for artwork URIs by track media ID
    private val artworkCache = MutableStateFlow<Map<String, String?>>(emptyMap())
    
    // Loading state for specific tracks
    private val loadingTracks = MutableStateFlow<Set<String>>(emptySet())
    
    // Mutex for thread-safe operations
    private val cacheMutex = Mutex()
    
    // Track artwork extraction attempts to avoid retrying failures
    private val failedExtractions = MutableStateFlow<Set<String>>(emptySet())
    
    /**
     * Get artwork URI for a track, loading it if necessary
     */
    fun getArtworkUri(trackUri: String): StateFlow<String?> {
        // Return a flow that emits the artwork URI when available
        return SpotifyStyleArtworkLoader.getArtworkFlow(trackUri)
    }
    
    /**
     * Get cached artwork URI immediately (non-suspending)
     */
    fun getCachedArtworkUri(trackUri: String): String? {
        return SpotifyStyleArtworkLoader.getCachedArtworkUri(trackUri)
    }
    
    /**
     * Load artwork for a track
     */
    fun loadArtwork(trackUri: String) {
        if (trackUri.isBlank() || failedExtractions.value.contains(trackUri)) {
            return
        }
        
        viewModelScope.launch {
            try {
                // Check if already loading
                if (loadingTracks.value.contains(trackUri)) {
                    return@launch
                }
                
                // Mark as loading
                loadingTracks.update { it + trackUri }
                
                // Check if track has embedded artwork to optimize loading
                val hasEmbeddedArt = dataManager.hasEmbeddedArtwork(trackUri)
                
                // Load artwork
                val artUri = SpotifyStyleArtworkLoader.loadArtwork(trackUri, hasEmbeddedArt)
                
                // Update cache
                cacheMutex.withLock {
                    artworkCache.update { cache ->
                        cache + (trackUri to artUri)
                    }
                }
                
                // Track failed extractions
                if (artUri == null) {
                    failedExtractions.update { it + trackUri }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading artwork for $trackUri", e)
                failedExtractions.update { it + trackUri }
            } finally {
                // Remove from loading set
                loadingTracks.update { it - trackUri }
            }
        }
    }
    
    /**
     * Prefetch artwork for a list of tracks
     */
    fun prefetchArtwork(trackUris: List<String>) {
        if (trackUris.isEmpty()) return
        
        viewModelScope.launch {
            try {
                Log.d(TAG, "Prefetching artwork for ${trackUris.size} tracks")
                
                // Filter out already cached and failed tracks
                val urisToLoad = trackUris.filter { uri ->
                    !artworkCache.value.containsKey(uri) && 
                    !failedExtractions.value.contains(uri) &&
                    !loadingTracks.value.contains(uri)
                }.take(PREFETCH_BATCH_SIZE)
                
                if (urisToLoad.isEmpty()) {
                    return@launch
                }
                
                // Load in batches to avoid overwhelming the system
                dataManager.prefetchArtwork(urisToLoad)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error prefetching artwork", e)
            }
        }
    }
    
    /**
     * Prefetch artwork for visible items in a list
     */
    fun prefetchForVisibleRange(
        allTrackUris: List<String>,
        firstVisibleIndex: Int,
        lastVisibleIndex: Int
    ) {
        if (allTrackUris.isEmpty()) return
        
        // Calculate prefetch range (visible + buffer)
        val bufferSize = 10
        val startIndex = (firstVisibleIndex - bufferSize).coerceAtLeast(0)
        val endIndex = (lastVisibleIndex + bufferSize).coerceAtMost(allTrackUris.size - 1)
        
        if (startIndex <= endIndex) {
            val visibleUris = allTrackUris.subList(startIndex, endIndex + 1)
            prefetchArtwork(visibleUris)
        }
    }
    
    /**
     * Clear artwork cache
     */
    fun clearCache() {
        viewModelScope.launch {
            try {
                SpotifyStyleArtworkLoader.clearCaches()
                
                cacheMutex.withLock {
                    artworkCache.value = emptyMap()
                }
                
                failedExtractions.value = emptySet()
                loadingTracks.value = emptySet()
                
                Log.d(TAG, "Artwork cache cleared")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing artwork cache", e)
            }
        }
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): ArtworkCacheStats {
        val stats = SpotifyStyleArtworkLoader.getCacheStats()
        return ArtworkCacheStats(
            cachedCount = artworkCache.value.size,
            failedCount = failedExtractions.value.size,
            loadingCount = loadingTracks.value.size,
            memoryCacheSize = stats.memoryCacheSize,
            memoryCacheMaxSize = stats.memoryCacheMaxSize,
            diskCacheFiles = stats.diskCacheFiles
        )
    }
    
    /**
     * Check if artwork is currently loading
     */
    fun isLoading(trackUri: String): Boolean {
        return loadingTracks.value.contains(trackUri)
    }
    
    /**
     * Check if artwork extraction has failed
     */
    fun hasFailed(trackUri: String): Boolean {
        return failedExtractions.value.contains(trackUri)
    }
    
    /**
     * Retry failed artwork extraction
     */
    fun retryFailed(trackUri: String) {
        failedExtractions.update { it - trackUri }
        loadArtwork(trackUri)
    }
    
    /**
     * Retry all failed extractions
     */
    fun retryAllFailed() {
        val failed = failedExtractions.value.toList()
        failedExtractions.value = emptySet()
        
        failed.forEach { trackUri ->
            loadArtwork(trackUri)
        }
    }
}

/**
 * Data class for artwork cache statistics
 */
data class ArtworkCacheStats(
    val cachedCount: Int,
    val failedCount: Int,
    val loadingCount: Int,
    val memoryCacheSize: Int,
    val memoryCacheMaxSize: Int,
    val diskCacheFiles: Int
)