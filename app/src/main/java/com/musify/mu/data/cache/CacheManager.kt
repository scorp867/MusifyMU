package com.musify.mu.data.cache

import android.content.Context
import android.util.LruCache
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TRACK_CACHE_SIZE = 1000
        private const val ARTWORK_CACHE_SIZE = 500
        private const val SEARCH_CACHE_SIZE = 100
    }
    
    // In-memory caches
    private val trackCache = LruCache<String, Track>(TRACK_CACHE_SIZE)
    private val artworkCache = LruCache<String, String>(ARTWORK_CACHE_SIZE)
    private val searchCache = LruCache<String, List<Track>>(SEARCH_CACHE_SIZE)
    
    // Mutexes for thread safety
    private val trackCacheMutex = Mutex()
    private val artworkCacheMutex = Mutex()
    private val searchCacheMutex = Mutex()
    
    // Track caching
    suspend fun cacheTrack(track: Track) = trackCacheMutex.withLock {
        trackCache.put(track.mediaId, track)
    }
    
    suspend fun getCachedTrack(mediaId: String): Track? = trackCacheMutex.withLock {
        return@withLock trackCache.get(mediaId)
    }
    
    suspend fun cacheTracks(tracks: List<Track>) = trackCacheMutex.withLock {
        tracks.forEach { track ->
            trackCache.put(track.mediaId, track)
        }
    }
    
    suspend fun getCachedTracks(): List<Track> = trackCacheMutex.withLock {
        val cachedTracks = mutableListOf<Track>()
        val snapshot = trackCache.snapshot()
        for (entry in snapshot) {
            cachedTracks.add(entry.value)
        }
        return@withLock cachedTracks
    }
    
    // Artwork caching
    suspend fun cacheArtwork(trackUri: String, artworkPath: String) = artworkCacheMutex.withLock {
        artworkCache.put(trackUri, artworkPath)
    }
    
    suspend fun getCachedArtwork(trackUri: String): String? = artworkCacheMutex.withLock {
        return@withLock artworkCache.get(trackUri)
    }
    
    // Search result caching
    suspend fun cacheSearchResults(query: String, results: List<Track>) = searchCacheMutex.withLock {
        searchCache.put(query.lowercase(), results)
    }
    
    suspend fun getCachedSearchResults(query: String): List<Track>? = searchCacheMutex.withLock {
        return@withLock searchCache.get(query.lowercase())
    }
    
    // Cache management
    suspend fun clearTrackCache() = trackCacheMutex.withLock {
        trackCache.evictAll()
    }
    
    suspend fun clearArtworkCache() = artworkCacheMutex.withLock {
        artworkCache.evictAll()
    }
    
    suspend fun clearSearchCache() = searchCacheMutex.withLock {
        searchCache.evictAll()
    }
    
    suspend fun clearAllCaches() {
        clearTrackCache()
        clearArtworkCache()
        clearSearchCache()
    }
    
    // Cache statistics
    fun getCacheStats(): CacheStats {
        return CacheStats(
            trackCacheSize = trackCache.size(),
            trackCacheMaxSize = trackCache.maxSize(),
            trackCacheHitCount = 0L, // Android LruCache doesn't have hitCount()
            trackCacheMissCount = 0L, // Android LruCache doesn't have missCount()
            artworkCacheSize = artworkCache.size(),
            artworkCacheMaxSize = artworkCache.maxSize(),
            searchCacheSize = searchCache.size(),
            searchCacheMaxSize = searchCache.maxSize()
        )
    }
    
    data class CacheStats(
        val trackCacheSize: Int,
        val trackCacheMaxSize: Int,
        val trackCacheHitCount: Long,
        val trackCacheMissCount: Long,
        val artworkCacheSize: Int,
        val artworkCacheMaxSize: Int,
        val searchCacheSize: Int,
        val searchCacheMaxSize: Int
    )
}