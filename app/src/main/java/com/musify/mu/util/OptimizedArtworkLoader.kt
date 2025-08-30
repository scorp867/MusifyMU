package com.musify.mu.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Optimized artwork loader inspired by Spotify's approach:
 * - Aggressive memory caching
 * - Fast disk cache with optimized sizes
 * - Intelligent prefetching
 * - Minimal UI blocking
 */
object OptimizedArtworkLoader {
    private const val TAG = "OptimizedArtworkLoader"
    private const val MEMORY_CACHE_SIZE = 256 // Increased for better performance
    private const val DISK_CACHE_SIZE = 100L * 1024 * 1024 // 100MB disk cache
    private const val ARTWORK_SIZE = 512 // Optimized size for display

    private lateinit var appContext: Context
    private lateinit var imageLoader: ImageLoader
    
    // Fast in-memory cache for URIs
    private val uriCache = LruCache<String, String>(MEMORY_CACHE_SIZE)
    
    // Track extraction status to avoid redundant work
    private val extractionStatus = ConcurrentHashMap<String, ExtractionState>()
    
    // Flows for reactive UI updates
    private val artworkFlows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    
    // Background scope for artwork operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private enum class ExtractionState {
        NOT_STARTED, IN_PROGRESS, COMPLETED, FAILED
    }
    
    fun init(context: Context) {
        appContext = context.applicationContext
        
        // Create optimized Coil ImageLoader
        imageLoader = ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.25) // Use 25% of available memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appContext.cacheDir, "optimized_artwork"))
                    .maxSizeBytes(DISK_CACHE_SIZE)
                    .build()
            }
            .crossfade(150) // Smooth transitions
            .build()
    }
    
    /**
     * Get artwork flow for reactive UI updates
     */
    fun artworkFlow(mediaUri: String): StateFlow<String?> {
        return artworkFlows.getOrPut(mediaUri) {
            MutableStateFlow(getCachedUri(mediaUri))
        }.asStateFlow()
    }
    
    /**
     * Get immediately available cached artwork URI
     */
    fun getCachedUri(mediaUri: String): String? {
        return uriCache.get(mediaUri)?.takeIf { it != "FAILED" }
    }
    
    /**
     * Intelligent prefetch for visible items
     */
    fun prefetch(mediaUris: List<String>) {
        if (!::appContext.isInitialized) return
        
        // Only prefetch items not already cached or failed
        val toPrefetch = mediaUris.filter { mediaUri ->
            uriCache.get(mediaUri) == null && 
            extractionStatus[mediaUri] != ExtractionState.FAILED
        }.take(20) // Limit concurrent operations
        
        if (toPrefetch.isEmpty()) return
        
        scope.launch {
            toPrefetch.forEach { mediaUri ->
                launch {
                    loadArtworkInternal(mediaUri)
                }
            }
        }
    }
    
    /**
     * Load artwork with caching and optimization
     */
    suspend fun loadArtwork(mediaUri: String): String? {
        return loadArtworkInternal(mediaUri)
    }
    
    private suspend fun loadArtworkInternal(mediaUri: String): String? = withContext(Dispatchers.IO) {
        if (mediaUri.isBlank()) return@withContext null
        
        // Check memory cache first
        uriCache.get(mediaUri)?.let { cached ->
            return@withContext if (cached == "FAILED") null else cached
        }
        
        // Check if already failed
        if (extractionStatus[mediaUri] == ExtractionState.FAILED) {
            return@withContext null
        }
        
        // Check if already in progress
        if (extractionStatus[mediaUri] == ExtractionState.IN_PROGRESS) {
            return@withContext getCachedUri(mediaUri)
        }
        
        extractionStatus[mediaUri] = ExtractionState.IN_PROGRESS
        
        try {
            // Try to use album art from MediaStore first (fastest)
            val albumArtUri = tryGetAlbumArt(mediaUri)
            if (albumArtUri != null) {
                cacheAndNotify(mediaUri, albumArtUri)
                extractionStatus[mediaUri] = ExtractionState.COMPLETED
                return@withContext albumArtUri
            }
            
            // Check disk cache
            val cacheKey = generateCacheKey(mediaUri)
            val cacheFile = File(File(appContext.cacheDir, "optimized_artwork"), "$cacheKey.jpg")
            
            if (cacheFile.exists()) {
                val uri = "file://${cacheFile.absolutePath}"
                cacheAndNotify(mediaUri, uri)
                extractionStatus[mediaUri] = ExtractionState.COMPLETED
                return@withContext uri
            }
            
            // Extract embedded artwork as last resort
            val extractedUri = extractEmbeddedArtwork(mediaUri, cacheFile)
            if (extractedUri != null) {
                cacheAndNotify(mediaUri, extractedUri)
                extractionStatus[mediaUri] = ExtractionState.COMPLETED
                return@withContext extractedUri
            } else {
                // Mark as failed
                uriCache.put(mediaUri, "FAILED")
                extractionStatus[mediaUri] = ExtractionState.FAILED
                artworkFlows[mediaUri]?.value = null
                return@withContext null
            }
            
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Error loading artwork for $mediaUri", e)
            uriCache.put(mediaUri, "FAILED")
            extractionStatus[mediaUri] = ExtractionState.FAILED
            artworkFlows[mediaUri]?.value = null
            return@withContext null
        }
    }
    
    private fun tryGetAlbumArt(mediaUri: String): String? {
        return try {
            // Extract album ID from MediaStore content URI
            if (mediaUri.startsWith("content://media/external/audio/media/")) {
                val albumId = getAlbumIdFromMediaUri(mediaUri)
                if (albumId > 0) {
                    "content://media/external/audio/albumart/$albumId"
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getAlbumIdFromMediaUri(mediaUri: String): Long {
        return try {
            val cursor = appContext.contentResolver.query(
                Uri.parse(mediaUri),
                arrayOf(android.provider.MediaStore.Audio.Media.ALBUM_ID),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getLong(0)
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    private suspend fun extractEmbeddedArtwork(mediaUri: String, cacheFile: File): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                when {
                    mediaUri.startsWith("content://") -> {
                        retriever.setDataSource(appContext, Uri.parse(mediaUri))
                    }
                    mediaUri.startsWith("/") -> {
                        retriever.setDataSource(mediaUri)
                    }
                    else -> return null
                }
                
                val artworkBytes = retriever.embeddedPicture ?: return null
                val bitmap = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size) ?: return null
                
                // Resize and optimize
                val optimizedBitmap = resizeAndOptimize(bitmap)
                
                // Save to cache
                cacheFile.parentFile?.mkdirs()
                cacheFile.outputStream().use { out ->
                    optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                
                // Clean up
                if (optimizedBitmap != bitmap) {
                    bitmap.recycle()
                }
                optimizedBitmap.recycle()
                
                "file://${cacheFile.absolutePath}"
                
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to extract artwork for $mediaUri", e)
            null
        }
    }
    
    private fun resizeAndOptimize(bitmap: Bitmap): Bitmap {
        val maxSize = ARTWORK_SIZE
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }
        
        val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    private fun cacheAndNotify(mediaUri: String, artworkUri: String) {
        uriCache.put(mediaUri, artworkUri)
        artworkFlows[mediaUri]?.value = artworkUri
    }
    
    /**
     * Clear caches to free memory
     */
    fun clearCaches() {
        uriCache.evictAll()
        extractionStatus.clear()
        artworkFlows.clear()
    }
    
    /**
     * Generate cache key for artwork
     */
    private fun generateCacheKey(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hashBytes = digest.digest(input.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
}

private fun String.md5(): String {
    return try {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(this.toByteArray())
        hashBytes.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        this.hashCode().toString()
    }
}