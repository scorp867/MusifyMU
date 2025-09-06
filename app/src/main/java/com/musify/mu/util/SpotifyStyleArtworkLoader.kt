package com.musify.mu.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
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
 * Spotify-style artwork loader that extracts embedded album art on-demand
 * using ID3/APIC frames via MediaMetadataRetriever.
 * 
 * Based on Spotify's approach:
 * - /sources/p/u93.java — APIC frame class (attached pictures from ID3)
 * - /sources/p/t6t.java — ID3 decoder that constructs APIC objects
 * - /sources/p/lgo.java — MediaMetadataRetriever utilities
 */
object SpotifyStyleArtworkLoader {
    private const val TAG = "SpotifyArtworkLoader"
    private const val MAX_MEMORY_ENTRIES = 100
    private const val MAX_BITMAP_SIZE = 512 // Maximum edge size in pixels
    
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // In-memory LRU cache for artwork URIs
    private val memoryCache = LruCache<String, String>(MAX_MEMORY_ENTRIES)

    // Dedicated LruCache for now playing screen - instant switching
    private val nowPlayingCache = LruCache<String, String>(50) // Cache last 50 now playing artworks
    
    // Negative cache to avoid re-attempting failed extractions
    private val failedExtractions = ConcurrentHashMap.newKeySet<String>()

    // Currently loading URIs to prevent duplicate work
    private val loadingUris = ConcurrentHashMap.newKeySet<String>()

    // Content-based cache to prevent duplicate extraction for same files
    private val contentCache = ConcurrentHashMap<String, String>()
    
    // Flow per URI for reactive updates
    private val uriFlows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    
    // Disk cache directory
    private val diskCacheDir: File by lazy {
        File(appContext.cacheDir, "spotify_artwork_cache").apply {
            if (!exists()) mkdirs()
        }
    }
    
    private const val CACHE_SENTINEL = "__NO_ARTWORK__"
    
    /**
     * Initialize the artwork loader
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        
        // Initialize disk cache directory immediately and verify it works
        try {
            val cacheDir = File(appContext.cacheDir, "spotify_artwork_cache")
            if (!cacheDir.exists()) {
                val created = cacheDir.mkdirs()
                Log.d(TAG, "Created cache directory: $created at ${cacheDir.absolutePath}")
            } else {
                Log.d(TAG, "Cache directory exists at ${cacheDir.absolutePath}")
            }
            
            // Test cache directory accessibility
            if (cacheDir.canWrite()) {
                Log.d(TAG, "Cache directory is writable")
                // Log existing cache files
                val existingFiles = cacheDir.listFiles()?.size ?: 0
                Log.d(TAG, "Found $existingFiles existing cache files")
                
                // List first few files for debugging
                cacheDir.listFiles()?.take(3)?.forEach { file ->
                    Log.d(TAG, "Cache file: ${file.name} (${file.length()} bytes)")
                }
            } else {
                Log.e(TAG, "Cache directory is not writable!")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing cache directory", e)
        }
        
        Log.d(TAG, "Spotify-style artwork loader initialized successfully")
    }
    
    /**
     * Get a flow that emits artwork URI updates for a given track URI
     */
    fun getArtworkFlow(trackUri: String): StateFlow<String?> {
        return uriFlows.getOrPut(trackUri) {
            val cached = getCachedArtworkUri(trackUri)
            MutableStateFlow(cached)
        }.asStateFlow()
    }
    
    /**
     * Get cached artwork URI if available, null otherwise
     */
    fun getCachedArtworkUri(trackUri: String): String? {
        val cached = memoryCache.get(trackUri)
        return if (cached == CACHE_SENTINEL) null else cached
    }
    
    /**
     * Load artwork for a track URI on-demand
     * This is the main entry point that follows Spotify's ID3/APIC extraction pattern
     */
    suspend fun loadArtwork(trackUri: String, hasEmbeddedArtwork: Boolean? = null): String? {
        if (trackUri.isBlank()) return null

        // Optimization: If we know the track doesn't have embedded artwork, don't attempt extraction
        if (hasEmbeddedArtwork == false) {
            Log.v(TAG, "Skipping artwork extraction for $trackUri - no embedded artwork")
            cacheNoArtwork(trackUri)
            return null
        }

        // Check if we've already failed to extract artwork for this URI
        if (failedExtractions.contains(trackUri)) {
            return null
        }

        // Check memory cache first
        val cached = getCachedArtworkUri(trackUri)
        if (cached != null) {
            return cached
        }

        // Check content-based cache for duplicate files
        val contentKey = getContentBasedKey(trackUri)
        if (contentKey != null) {
            val contentCached = contentCache[contentKey]
            if (contentCached != null) {
                Log.d(TAG, "Found content-based cached artwork for $trackUri")
                cacheArtworkUri(trackUri, contentCached)
                return contentCached
            }
        }

        // Prevent duplicate loading
        if (loadingUris.contains(trackUri)) {
            return null
        }

        loadingUris.add(trackUri)

        return try {
            withContext(Dispatchers.IO) {
                extractAndCacheArtwork(trackUri)
            }
        } finally {
            loadingUris.remove(trackUri)
        }
    }
    
    /**
     * Generate a content-based key for duplicate file detection
     * Uses file size and modification time as a simple content hash
     */
    private suspend fun getContentBasedKey(trackUri: String): String? {
        return try {
            when {
                trackUri.startsWith("content://") -> {
                    val uri = Uri.parse(trackUri)
                    val cursor = appContext.contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val size = it.getLong(it.getColumnIndexOrThrow("_size"))
                            val modified = it.getLong(it.getColumnIndexOrThrow("date_modified"))
                            "${size}_${modified}"
                        } else null
                    }
                }
                trackUri.startsWith("file://") || trackUri.startsWith("/") -> {
                    val file = File(if (trackUri.startsWith("file://")) Uri.parse(trackUri).path!! else trackUri)
                    if (file.exists()) {
                        "${file.length()}_${file.lastModified()}"
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.v(TAG, "Could not generate content key for $trackUri: ${e.message}")
            null
        }
    }

    /**
     * Extract embedded artwork using MediaMetadataRetriever (ID3/APIC)
     * This mirrors Spotify's approach in their decompiled code
     */
    private suspend fun extractAndCacheArtwork(trackUri: String): String? {
        Log.d(TAG, "Extracting artwork for: $trackUri")
        
        // Check disk cache first
        val diskCacheFile = getDiskCacheFile(trackUri)
        if (diskCacheFile.exists()) {
            val fileUri = "file://${diskCacheFile.absolutePath}"
            Log.d(TAG, "Found cached artwork: ${diskCacheFile.absolutePath} (${diskCacheFile.length()} bytes)")
            cacheArtworkUri(trackUri, fileUri)
            return fileUri
        } else {
            Log.d(TAG, "No cached artwork found at: ${diskCacheFile.absolutePath}")
        }
        
        // Extract using MediaMetadataRetriever (Spotify's approach)
        val retriever = MediaMetadataRetriever()
        return try {
            // Set data source based on URI type
            when {
                trackUri.startsWith("content://") -> {
                    retriever.setDataSource(appContext, Uri.parse(trackUri))
                }
                trackUri.startsWith("file://") || trackUri.startsWith("/") -> {
                    val path = if (trackUri.startsWith("file://")) {
                        Uri.parse(trackUri).path
                    } else {
                        trackUri
                    }
                    retriever.setDataSource(path)
                }
                else -> {
                    Log.w(TAG, "Unsupported URI format: $trackUri")
                    markAsFailed(trackUri)
                    return null
                }
            }
            
            // Extract embedded picture (ID3/APIC frame)
            val embeddedPicture = retriever.embeddedPicture
            
            if (embeddedPicture != null) {
                Log.d(TAG, "Found embedded artwork for: $trackUri (${embeddedPicture.size} bytes)")
                
                // Process and save the artwork
                val artworkUri = processAndSaveArtwork(trackUri, embeddedPicture)
                if (artworkUri != null) {
                    cacheArtworkUri(trackUri, artworkUri)

                    // Store in content-based cache to prevent duplicate extractions
                    val contentKey = getContentBasedKey(trackUri)
                    if (contentKey != null) {
                        contentCache[contentKey] = artworkUri
                        Log.d(TAG, "Stored content-based cache for $trackUri")
                    }

                    return artworkUri
                }
            } else {
                Log.d(TAG, "No embedded artwork found for: $trackUri")
            }
            
            // Mark as failed if no artwork found
            markAsFailed(trackUri)
            null
            
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting artwork from $trackUri", e)
            markAsFailed(trackUri)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }
    
    /**
     * Process raw artwork bytes and save to disk cache
     */
    private suspend fun processAndSaveArtwork(trackUri: String, artworkBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing artwork bytes: ${artworkBytes.size} bytes for $trackUri")
            
            // Decode the bitmap
            val originalBitmap = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size)
            if (originalBitmap == null) {
                Log.w(TAG, "Failed to decode artwork bytes for $trackUri")
                return@withContext null
            }
            
            Log.d(TAG, "Decoded bitmap: ${originalBitmap.width}x${originalBitmap.height}")
            
            // Resize if necessary to prevent memory issues
            val resizedBitmap = if (originalBitmap.width > MAX_BITMAP_SIZE || originalBitmap.height > MAX_BITMAP_SIZE) {
                Log.d(TAG, "Resizing bitmap from ${originalBitmap.width}x${originalBitmap.height} to max $MAX_BITMAP_SIZE")
                resizeBitmap(originalBitmap, MAX_BITMAP_SIZE)
            } else {
                originalBitmap
            }
            
            // Save to disk cache
            val cacheFile = getDiskCacheFile(trackUri)
            Log.d(TAG, "Saving artwork to: ${cacheFile.absolutePath}")
            
            // Ensure parent directory exists
            cacheFile.parentFile?.mkdirs()
            
            var success = false
            cacheFile.outputStream().use { outputStream ->
                success = resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                outputStream.flush()
            }
            
            if (success && cacheFile.exists()) {
                Log.d(TAG, "Successfully saved artwork: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            } else {
                Log.e(TAG, "Failed to save artwork: success=$success, exists=${cacheFile.exists()}")
                return@withContext null
            }
            
            // Clean up bitmaps
            if (resizedBitmap !== originalBitmap) {
                originalBitmap.recycle()
            }
            resizedBitmap.recycle()
            
            val fileUri = "file://${cacheFile.absolutePath}"
            Log.d(TAG, "Artwork processing complete: $fileUri")
            
            fileUri
        } catch (e: Exception) {
            Log.e(TAG, "Error processing artwork for $trackUri", e)
            null
        }
    }
    
    /**
     * Resize bitmap to fit within max dimensions while maintaining aspect ratio
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }
        
        val ratio = kotlin.math.min(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Cache artwork URI in memory and notify observers
     */
    private fun cacheArtworkUri(trackUri: String, artworkUri: String?) {
        val cacheValue = artworkUri ?: CACHE_SENTINEL
        val previousValue = memoryCache.get(trackUri)
        
        memoryCache.put(trackUri, cacheValue)
        
        // Only notify if value actually changed
        if (previousValue != cacheValue) {
            uriFlows[trackUri]?.value = artworkUri
        }
    }
    
    /**
     * Cache that a track has no artwork to prevent future extraction attempts
     */
    private fun cacheNoArtwork(trackUri: String) {
        memoryCache.put(trackUri, CACHE_SENTINEL)
        failedExtractions.add(trackUri)
        
        // Update flow with null
        uriFlows[trackUri]?.value = null
        
        Log.v(TAG, "Cached no artwork for: $trackUri")
    }
    
    /**
     * Mark a URI as failed to avoid repeated attempts
     */
    private fun markAsFailed(trackUri: String) {
        failedExtractions.add(trackUri)
        cacheArtworkUri(trackUri, null)
    }
    
    /**
     * Get disk cache file for a track URI
     */
    private fun getDiskCacheFile(trackUri: String): File {
        val hash = trackUri.md5()
        return File(diskCacheDir, "$hash.jpg")
    }
    
    /**
     * Prefetch artwork for multiple tracks with optimized batching
     */
    fun prefetchArtwork(trackUris: List<String>) {
        if (!::appContext.isInitialized) return

        // Process in optimized batches to avoid overwhelming the system
        trackUris.distinct().take(100).chunked(10).forEachIndexed { batchIndex, batch ->
            scope.launch {
                // Stagger batch processing to prevent resource contention
                if (batchIndex > 0) {
                    kotlinx.coroutines.delay(100L * batchIndex)
                }

                batch.forEach { trackUri ->
                    if (!failedExtractions.contains(trackUri) && getCachedArtworkUri(trackUri) == null) {
                        // Launch each artwork load as a separate coroutine for better parallelism
                        scope.launch {
                            loadArtwork(trackUri)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Store artwork bytes directly (e.g., from ExoPlayer metadata)
     */
    suspend fun storeArtworkBytes(trackUri: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext null
        
        try {
            // Process and save the artwork bytes
            val artworkUri = processAndSaveArtwork(trackUri, bytes)
            if (artworkUri != null) {
                cacheArtworkUri(trackUri, artworkUri)
                return@withContext artworkUri
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error storing artwork bytes for $trackUri", e)
        }
        
        null
    }
    
    /**
     * Store custom artwork from URI (e.g., from gallery picker)
     */
    suspend fun storeCustomArtwork(trackUri: String, imageUri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext null
        
        try {
            appContext.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                return@withContext storeArtworkBytes(trackUri, bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error storing custom artwork for $trackUri", e)
        }
        
        null
    }

    /**
     * Get artwork optimized for now playing screen (prioritized caching)
     */
    suspend fun getNowPlayingArtwork(trackUri: String, hasEmbeddedArtwork: Boolean? = null): String? {
        // Check now playing cache first for instant switching
        val cached = nowPlayingCache.get(trackUri)
        if (cached != null) {
            return cached
        }

        // Load artwork with high priority
        val artworkUri = loadArtwork(trackUri, hasEmbeddedArtwork)

        // Cache in now playing cache for future instant access
        if (artworkUri != null) {
            nowPlayingCache.put(trackUri, artworkUri)
        }

        return artworkUri
    }

    /**
     * Preload artwork for queue tracks (for smooth transitions)
     */
    fun preloadQueueArtwork(trackUris: List<String>) {
        if (!::appContext.isInitialized) return

        // Prioritize first few tracks in queue
        val priorityTracks = trackUris.take(10)
        val remainingTracks = trackUris.drop(10).take(20)

        // Load priority tracks immediately
        priorityTracks.forEach { trackUri ->
            scope.launch {
                getNowPlayingArtwork(trackUri)
            }
        }

        // Load remaining tracks with slight delay
        if (remainingTracks.isNotEmpty()) {
            scope.launch {
                kotlinx.coroutines.delay(500) // Allow priority tracks to load first
                prefetchArtwork(remainingTracks)
            }
        }
    }

    /**
     * Clear all caches
     */
    fun clearCaches() {
        memoryCache.evictAll()
        nowPlayingCache.evictAll()
        failedExtractions.clear()
        uriFlows.clear()
        contentCache.clear()

        // Clear disk cache
        scope.launch {
            try {
                diskCacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension == "jpg") {
                        file.delete()
                    }
                }
                Log.d(TAG, "Cleared disk cache")
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing disk cache", e)
            }
        }
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): ArtworkCacheStats {
        return ArtworkCacheStats(
            memoryCacheSize = memoryCache.size(),
            memoryCacheMaxSize = memoryCache.maxSize(),
            failedExtractions = failedExtractions.size,
            diskCacheFiles = diskCacheDir.listFiles()?.filter { it.extension == "jpg" }?.size ?: 0
        )
    }
}

/**
 * Cache statistics for debugging
 */
data class ArtworkCacheStats(
    val memoryCacheSize: Int,
    val memoryCacheMaxSize: Int,
    val failedExtractions: Int,
    val diskCacheFiles: Int
)

/**
 * Extension function to compute MD5 hash
 */
private fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
