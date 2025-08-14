package com.musify.mu.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

object EmbeddedArtCache {
    private val memoryCache: LruCache<String, Bitmap>
    
    // Session-persistent cache - keeps artworks until app is completely closed
    private val sessionCache = mutableMapOf<String, Bitmap>()
    private val loadingKeys = mutableSetOf<String>()

    init {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxMemoryKb * 0.08f).toInt() // Increased to 8% for better persistence
        memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
            
            override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
                // When evicted from LRU cache, keep in session cache for persistence
                if (evicted && !sessionCache.containsKey(key)) {
                    sessionCache[key] = oldValue
                }
            }
        }
    }

    /**
     * Get artwork from cache - checks both memory and session cache
     */
    fun getFromMemory(audioUri: String): Bitmap? {
        // First try memory cache (fastest)
        memoryCache.get(audioUri)?.let { return it }
        
        // Then try session cache (persistent until app close)
        sessionCache[audioUri]?.let { bitmap ->
            // Move back to memory cache for faster future access
            memoryCache.put(audioUri, bitmap)
            return bitmap
        }
        
        return null
    }

    /**
     * Store artwork in both memory and session cache
     */
    private fun put(audioUri: String, bmp: Bitmap) {
        // Store in memory cache
        memoryCache.put(audioUri, bmp)
        
        // Also store in session cache for persistence
        sessionCache[audioUri] = bmp
    }
    
    /**
     * Check if we're currently loading this artwork to prevent duplicate loads
     */
    fun isLoading(audioUri: String): Boolean = synchronized(loadingKeys) {
        loadingKeys.contains(audioUri)
    }

    suspend fun loadEmbedded(context: Context, audioUri: String): Bitmap? = withContext(Dispatchers.IO) {
        // Return cached if present (checks both memory and session cache)
        getFromMemory(audioUri)?.let { return@withContext it }

        // Prevent duplicate loading
        synchronized(loadingKeys) {
            if (loadingKeys.contains(audioUri)) {
                return@withContext null // Already loading, prevent duplicate
            }
            loadingKeys.add(audioUri)
        }

        try {
            runCatching {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(context, Uri.parse(audioUri))
                val bytes = mmr.embeddedPicture
                mmr.release()
                if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            }.onSuccess { bmp ->
                if (bmp != null) put(audioUri, bmp)
            }.getOrNull()
        } finally {
            // Always remove from loading set
            synchronized(loadingKeys) {
                loadingKeys.remove(audioUri)
            }
        }
    }

    suspend fun preload(context: Context, audioUris: List<String>) = withContext(Dispatchers.IO) {
        // Process in chunks for better performance
        audioUris.chunked(10).forEach { chunk ->
            chunk.forEach { uri ->
                if (memoryCache.get(uri) == null) {
                    runCatching { loadEmbedded(context, uri) }
                }
            }
            // Small delay between chunks to prevent blocking
            delay(50)
        }
    }
    
    // Background preload without blocking
    fun preloadAsync(context: Context, audioUris: List<String>) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            preload(context, audioUris)
        }
    }
    
    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            memoryCacheSize = memoryCache.size(),
            sessionCacheSize = sessionCache.size,
            currentlyLoading = loadingKeys.size
        )
    }
    
    /**
     * Clear only the LRU memory cache, keep session cache for persistence
     */
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }
    
    /**
     * Clear all caches (use only when needed)
     */
    fun clearAllCaches() {
        memoryCache.evictAll()
        sessionCache.clear()
        synchronized(loadingKeys) {
            loadingKeys.clear()
        }
    }
    
    data class CacheStats(
        val memoryCacheSize: Int,
        val sessionCacheSize: Int,
        val currentlyLoading: Int
    )
}


