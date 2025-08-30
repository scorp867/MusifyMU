package com.musify.mu.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Optimized artwork loader with batching, prioritization, and efficient caching.
 * 
 * Key improvements:
 * 1. Batch processing of artwork requests
 * 2. Priority queue for visible items
 * 3. Concurrent loading with controlled parallelism
 * 4. Efficient memory and disk caching
 * 5. Deduplication of in-flight requests
 */
object OptimizedArtworkLoader {
    private const val MAX_MEMORY_ENTRIES = 256
    private const val MAX_BITMAP_EDGE = 512
    private const val BATCH_SIZE = 10
    private const val MAX_CONCURRENT_LOADS = 4
    
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Memory cache with increased capacity
    private val memoryCache = object : LruCache<String, String>(MAX_MEMORY_ENTRIES) {
        override fun sizeOf(key: String, value: String): Int = 1
    }
    
    // Disk cache directory
    private val diskDir: File by lazy {
        File(appContext.cacheDir, "optimized_artwork").apply { 
            if (!exists()) mkdirs() 
        }
    }
    
    // Failed keys to avoid retrying
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    
    // In-flight requests to avoid duplicates
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<String?>>()
    
    // Per-mediaUri flows for UI updates
    private val artworkFlows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    
    // Priority queue for loading requests
    private val loadingQueue = Channel<LoadRequest>(Channel.UNLIMITED)
    
    // Batch processor job
    private var batchProcessorJob: Job? = null
    
    private const val NONE_SENTINEL = "__NONE__"
    
    data class LoadRequest(
        val mediaUri: String,
        val priority: Int = 0 // Higher priority = loaded first
    )
    
    fun init(context: Context) {
        appContext = context.applicationContext
        startBatchProcessor()
    }
    
    private fun startBatchProcessor() {
        batchProcessorJob?.cancel()
        batchProcessorJob = scope.launch {
            val pendingRequests = mutableListOf<LoadRequest>()
            
            while (isActive) {
                try {
                    // Collect requests for batch processing
                    val request = loadingQueue.tryReceive().getOrNull()
                    if (request != null) {
                        pendingRequests.add(request)
                        
                        // Collect more requests up to batch size
                        repeat(BATCH_SIZE - 1) {
                            loadingQueue.tryReceive().getOrNull()?.let {
                                pendingRequests.add(it)
                            }
                        }
                    }
                    
                    if (pendingRequests.isNotEmpty()) {
                        // Sort by priority (descending)
                        pendingRequests.sortByDescending { it.priority }
                        
                        // Process in parallel with controlled concurrency
                        pendingRequests.chunked(MAX_CONCURRENT_LOADS).forEach { chunk ->
                            chunk.map { request ->
                                async {
                                    loadArtworkInternal(request.mediaUri)
                                }
                            }.awaitAll()
                        }
                        
                        pendingRequests.clear()
                    } else {
                        // No requests, wait a bit
                        delay(50)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OptimizedArtworkLoader", "Batch processor error", e)
                }
            }
        }
    }
    
    fun getCachedUri(mediaUri: String): String? {
        val cached = memoryCache.get(mediaUri)
        return if (cached == null || cached == NONE_SENTINEL) null else cached
    }
    
    fun artworkFlow(mediaUri: String): StateFlow<String?> {
        return artworkFlows.getOrPut(mediaUri) {
            MutableStateFlow(getCachedUri(mediaUri))
        }.asStateFlow()
    }
    
    /**
     * Request artwork loading with optional priority.
     * Higher priority items (e.g., visible items) are processed first.
     */
    fun requestArtwork(mediaUri: String?, priority: Int = 0) {
        if (mediaUri.isNullOrBlank()) return
        if (failedKeys.contains(mediaUri)) return
        if (memoryCache.get(mediaUri) != null) return
        if (inFlightRequests.containsKey(mediaUri)) return
        
        scope.launch {
            loadingQueue.send(LoadRequest(mediaUri, priority))
        }
    }
    
    /**
     * Prefetch artwork for multiple URIs with high priority
     */
    fun prefetch(mediaUris: List<String>, priority: Int = 5) {
        mediaUris.forEach { uri ->
            requestArtwork(uri, priority)
        }
    }
    
    /**
     * Load artwork with caching and deduplication
     */
    suspend fun loadArtwork(mediaUri: String?): String? {
        if (mediaUri.isNullOrBlank()) return null
        
        // Check memory cache
        getCachedUri(mediaUri)?.let { return it }
        
        // Check if already loading
        inFlightRequests[mediaUri]?.let { deferred ->
            return deferred.await()
        }
        
        // Create new loading task
        val deferred = scope.async {
            loadArtworkInternal(mediaUri)
        }
        
        inFlightRequests[mediaUri] = deferred
        
        return try {
            deferred.await()
        } finally {
            inFlightRequests.remove(mediaUri)
        }
    }
    
    private suspend fun loadArtworkInternal(mediaUri: String): String? {
        // Check memory cache again
        memoryCache.get(mediaUri)?.let { cached ->
            return if (cached == NONE_SENTINEL) null else cached
        }
        
        // Check disk cache
        val cacheFile = File(diskDir, mediaUri.md5() + ".jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            val uriString = "file://${cacheFile.absolutePath}"
            cacheUri(mediaUri, uriString)
            return uriString
        }
        
        // Extract from media file
        return extractAndCacheArtwork(mediaUri, cacheFile)
    }
    
    private suspend fun extractAndCacheArtwork(mediaUri: String, cacheFile: File): String? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            when {
                mediaUri.startsWith("content://") -> retriever.setDataSource(appContext, Uri.parse(mediaUri))
                mediaUri.startsWith("/") -> retriever.setDataSource(mediaUri)
                else -> {
                    cacheUri(mediaUri, null)
                    return@withContext null
                }
            }
            
            val artworkBytes = retriever.embeddedPicture
            if (artworkBytes == null) {
                cacheUri(mediaUri, null)
                return@withContext null
            }
            
            // Decode and resize bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size, options)
            
            // Calculate sample size for efficient decoding
            options.inSampleSize = calculateInSampleSize(options, MAX_BITMAP_EDGE, MAX_BITMAP_EDGE)
            options.inJustDecodeBounds = false
            
            val bitmap = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size, options)
            if (bitmap == null) {
                cacheUri(mediaUri, null)
                return@withContext null
            }
            
            // Save to disk
            cacheFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            
            val uriString = "file://${cacheFile.absolutePath}"
            cacheUri(mediaUri, uriString)
            return@withContext uriString
            
        } catch (e: Exception) {
            android.util.Log.w("OptimizedArtworkLoader", "Error extracting artwork", e)
            cacheUri(mediaUri, null)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
    
    private fun cacheUri(mediaUri: String, artUri: String?) {
        val value = artUri ?: NONE_SENTINEL
        memoryCache.put(mediaUri, value)
        
        if (artUri == null) {
            failedKeys.add(mediaUri)
        } else {
            failedKeys.remove(mediaUri)
        }
        
        // Update flow
        artworkFlows[mediaUri]?.value = artUri
    }
    
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    private fun String.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Clear all caches and reset state
     */
    fun clearCache() {
        memoryCache.evictAll()
        failedKeys.clear()
        artworkFlows.clear()
        diskDir.listFiles()?.forEach { it.delete() }
    }
}