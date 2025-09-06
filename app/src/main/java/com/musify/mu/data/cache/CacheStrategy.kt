package com.musify.mu.data.cache

import android.content.Context
import android.util.Log
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advanced caching strategy that manages multiple cache layers:
 * 1. Memory cache (LRU) - Fast access for frequently used items
 * 2. Disk cache - Persistent storage for artwork and metadata
 * 3. Database cache - Structured data with relationships
 * 4. Network cache - For future streaming features
 */
@Singleton
class CacheStrategy @Inject constructor(
    private val context: Context,
    private val cacheManager: CacheManager
) {
    companion object {
        private const val TAG = "CacheStrategy"
        private const val DISK_CACHE_DIR = "musify_cache"
        private const val ARTWORK_CACHE_DIR = "artwork"
        private const val METADATA_CACHE_DIR = "metadata"
        private const val MAX_DISK_CACHE_SIZE = 500L * 1024 * 1024 // 500MB
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheMutex = Mutex()
    
    // Cache directories
    private val diskCacheDir = File(context.cacheDir, DISK_CACHE_DIR)
    private val artworkCacheDir = File(diskCacheDir, ARTWORK_CACHE_DIR)
    private val metadataCacheDir = File(diskCacheDir, METADATA_CACHE_DIR)
    
    // Cache state
    private val _cacheState = MutableStateFlow(CacheState())
    val cacheState: StateFlow<CacheState> = _cacheState.asStateFlow()
    
    init {
        initializeCacheDirectories()
        monitorCacheSize()
    }
    
    private fun initializeCacheDirectories() {
        try {
            diskCacheDir.mkdirs()
            artworkCacheDir.mkdirs()
            metadataCacheDir.mkdirs()
            Log.d(TAG, "Cache directories initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize cache directories", e)
        }
    }
    
    private fun monitorCacheSize() {
        scope.launch {
            try {
                val totalSize = calculateTotalCacheSize()
                _cacheState.value = _cacheState.value.copy(
                    totalDiskCacheSize = totalSize,
                    maxDiskCacheSize = MAX_DISK_CACHE_SIZE
                )
                
                // Clean up if cache is too large
                if (totalSize > MAX_DISK_CACHE_SIZE) {
                    cleanupDiskCache()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to monitor cache size", e)
            }
        }
    }
    
    /**
     * Cache a track with all associated data
     */
    suspend fun cacheTrack(track: Track, priority: CachePriority = CachePriority.NORMAL) = cacheMutex.withLock {
        try {
            // Cache in memory
            cacheManager.cacheTrack(track)
            
            // Cache metadata to disk if high priority
            if (priority == CachePriority.HIGH) {
                cacheTrackMetadataToDisk(track)
            }
            
            Log.d(TAG, "Cached track: ${track.title} (priority: $priority)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache track: ${track.title}", e)
        }
    }
    
    /**
     * Cache artwork with intelligent storage strategy
     */
    suspend fun cacheArtwork(trackUri: String, artworkData: ByteArray, priority: CachePriority = CachePriority.NORMAL) = cacheMutex.withLock {
        try {
            val artworkFile = File(artworkCacheDir, "${trackUri.hashCode()}.jpg")
            
            // Always cache to memory first
            val artworkPath = artworkFile.absolutePath
            cacheManager.cacheArtwork(trackUri, artworkPath)
            
            // Cache to disk based on priority and size constraints
            if (shouldCacheArtworkToDisk(artworkData.size, priority)) {
                artworkFile.writeBytes(artworkData)
                Log.d(TAG, "Cached artwork to disk: $trackUri (${artworkData.size} bytes)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache artwork: $trackUri", e)
        }
    }
    
    /**
     * Get cached artwork with fallback strategy
     */
    suspend fun getCachedArtwork(trackUri: String): String? {
        return try {
            // Try memory cache first
            var artworkPath = cacheManager.getCachedArtwork(trackUri)
            
            if (artworkPath == null) {
                // Try disk cache
                val artworkFile = File(artworkCacheDir, "${trackUri.hashCode()}.jpg")
                if (artworkFile.exists()) {
                    artworkPath = artworkFile.absolutePath
                    // Update memory cache
                    cacheManager.cacheArtwork(trackUri, artworkPath)
                }
            }
            
            artworkPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cached artwork: $trackUri", e)
            null
        }
    }
    
    /**
     * Intelligent cache preloading based on usage patterns
     */
    suspend fun preloadCache(tracks: List<Track>, strategy: PreloadStrategy) {
        try {
            val tracksToPreload = when (strategy) {
                PreloadStrategy.RECENTLY_PLAYED -> tracks.sortedByDescending { it.dateAddedSec }.take(50)
                PreloadStrategy.FREQUENTLY_ACCESSED -> tracks.take(100) // Assume first tracks are most accessed
                PreloadStrategy.UPCOMING_QUEUE -> tracks.take(20)
                PreloadStrategy.ALL -> tracks
            }
            
            Log.d(TAG, "Preloading cache with ${tracksToPreload.size} tracks (strategy: $strategy)")
            
            // Preload in batches to avoid overwhelming the system
            tracksToPreload.chunked(10).forEach { batch ->
                scope.launch {
                    batch.forEach { track ->
                        cacheTrack(track, CachePriority.NORMAL)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload cache", e)
        }
    }
    
    /**
     * Cache cleanup with intelligent eviction
     */
    suspend fun cleanupCache(aggressiveness: CleanupAggressiveness = CleanupAggressiveness.NORMAL) = cacheMutex.withLock {
        try {
            Log.d(TAG, "Starting cache cleanup (aggressiveness: $aggressiveness)")
            
            when (aggressiveness) {
                CleanupAggressiveness.LIGHT -> {
                    // Only clear expired items
                    cleanupExpiredItems()
                }
                CleanupAggressiveness.NORMAL -> {
                    // Clear expired items and least recently used
                    cleanupExpiredItems()
                    cleanupLeastRecentlyUsed(0.7f) // Keep 70% of cache
                }
                CleanupAggressiveness.AGGRESSIVE -> {
                    // Clear most items, keep only essentials
                    cacheManager.clearSearchCache()
                    cleanupLeastRecentlyUsed(0.3f) // Keep only 30% of cache
                    cleanupDiskCache()
                }
                CleanupAggressiveness.COMPLETE -> {
                    // Clear everything
                    cacheManager.clearAllCaches()
                    clearDiskCache()
                }
            }
            
            // Update cache state
            val totalSize = calculateTotalCacheSize()
            _cacheState.value = _cacheState.value.copy(
                totalDiskCacheSize = totalSize,
                lastCleanupTimestamp = System.currentTimeMillis()
            )
            
            Log.d(TAG, "Cache cleanup completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup cache", e)
        }
    }
    
    /**
     * Get comprehensive cache statistics
     */
    fun getCacheStatistics(): CacheStatistics {
        val memoryStats = cacheManager.getCacheStats()
        val diskSize = calculateTotalCacheSize()
        
        return CacheStatistics(
            memoryCache = MemoryCacheStats(
                trackCacheSize = memoryStats.trackCacheSize,
                trackCacheMaxSize = memoryStats.trackCacheMaxSize,
                trackCacheHitRate = if (memoryStats.trackCacheMissCount + memoryStats.trackCacheHitCount > 0) {
                    memoryStats.trackCacheHitCount.toDouble() / (memoryStats.trackCacheMissCount + memoryStats.trackCacheHitCount)
                } else 0.0,
                artworkCacheSize = memoryStats.artworkCacheSize,
                searchCacheSize = memoryStats.searchCacheSize
            ),
            diskCache = DiskCacheStats(
                totalSize = diskSize,
                maxSize = MAX_DISK_CACHE_SIZE,
                artworkFiles = artworkCacheDir.listFiles()?.size ?: 0,
                metadataFiles = metadataCacheDir.listFiles()?.size ?: 0
            ),
            lastCleanup = _cacheState.value.lastCleanupTimestamp
        )
    }
    
    // Private helper methods
    
    private fun shouldCacheArtworkToDisk(artworkSize: Int, priority: CachePriority): Boolean {
        val totalCacheSize = calculateTotalCacheSize()
        val availableSpace = MAX_DISK_CACHE_SIZE - totalCacheSize
        
        return when (priority) {
            CachePriority.HIGH -> availableSpace > artworkSize
            CachePriority.NORMAL -> availableSpace > artworkSize * 2 // More conservative
            CachePriority.LOW -> availableSpace > artworkSize * 5 // Very conservative
        }
    }
    
    private fun cacheTrackMetadataToDisk(track: Track) {
        try {
            val metadataFile = File(metadataCacheDir, "${track.mediaId.hashCode()}.json")
            // Simple JSON serialization (in real app, use proper JSON library)
            val json = """
                {
                    "mediaId": "${track.mediaId}",
                    "title": "${track.title}",
                    "artist": "${track.artist}",
                    "album": "${track.album}",
                    "duration": ${track.durationMs},
                    "cached_at": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            metadataFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache metadata for track: ${track.title}", e)
        }
    }
    
    private fun calculateTotalCacheSize(): Long {
        return try {
            val artworkSize = artworkCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val metadataSize = metadataCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            artworkSize + metadataSize
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun cleanupExpiredItems() {
        val expirationTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000) // 7 days
        
        try {
            // Clean expired artwork
            artworkCacheDir.listFiles()?.forEach { file ->
                if (file.lastModified() < expirationTime) {
                    file.delete()
                }
            }
            
            // Clean expired metadata
            metadataCacheDir.listFiles()?.forEach { file ->
                if (file.lastModified() < expirationTime) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup expired items", e)
        }
    }
    
    private fun cleanupLeastRecentlyUsed(keepRatio: Float) {
        try {
            val artworkFiles = artworkCacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            val filesToDelete = artworkFiles.take((artworkFiles.size * (1 - keepRatio)).toInt())
            
            filesToDelete.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup least recently used items", e)
        }
    }
    
    private fun cleanupDiskCache() {
        val currentSize = calculateTotalCacheSize()
        if (currentSize <= MAX_DISK_CACHE_SIZE) return
        
        // Remove oldest files until we're under the limit
        val allFiles = (artworkCacheDir.listFiles()?.toList() ?: emptyList()) +
                      (metadataCacheDir.listFiles()?.toList() ?: emptyList())
        
        val sortedFiles = allFiles.sortedBy { it.lastModified() }
        var deletedSize = 0L
        
        for (file in sortedFiles) {
            if (currentSize - deletedSize <= MAX_DISK_CACHE_SIZE * 0.8) break // Stop at 80% capacity
            
            deletedSize += file.length()
            file.delete()
        }
    }
    
    private fun clearDiskCache() {
        try {
            artworkCacheDir.deleteRecursively()
            metadataCacheDir.deleteRecursively()
            artworkCacheDir.mkdirs()
            metadataCacheDir.mkdirs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear disk cache", e)
        }
    }
}

// Data classes and enums

data class CacheState(
    val totalDiskCacheSize: Long = 0L,
    val maxDiskCacheSize: Long = 0L,
    val lastCleanupTimestamp: Long = 0L
)

data class CacheStatistics(
    val memoryCache: MemoryCacheStats,
    val diskCache: DiskCacheStats,
    val lastCleanup: Long
)

data class MemoryCacheStats(
    val trackCacheSize: Int,
    val trackCacheMaxSize: Int,
    val trackCacheHitRate: Double,
    val artworkCacheSize: Int,
    val searchCacheSize: Int
)

data class DiskCacheStats(
    val totalSize: Long,
    val maxSize: Long,
    val artworkFiles: Int,
    val metadataFiles: Int
)

enum class CachePriority {
    HIGH, NORMAL, LOW
}

enum class PreloadStrategy {
    RECENTLY_PLAYED, FREQUENTLY_ACCESSED, UPCOMING_QUEUE, ALL
}

enum class CleanupAggressiveness {
    LIGHT, NORMAL, AGGRESSIVE, COMPLETE
}