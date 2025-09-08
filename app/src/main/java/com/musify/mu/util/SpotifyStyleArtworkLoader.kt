package com.musify.mu.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.provider.MediaStore
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
    private const val MAX_MEMORY_ENTRIES = Int.MAX_VALUE
    
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractionSemaphore = java.util.concurrent.Semaphore(3, true)
    
    // Shared Coil image loader for optimized artwork loading
    private var artworkImageLoader: ImageLoader? = null
    
    // In-memory LRU cache for artwork URIs
    private val memoryCache = LruCache<String, String>(MAX_MEMORY_ENTRIES)

    // Dedicated LruCache for now playing screen - instant switching
    private val nowPlayingCache = LruCache<String, String>(50) // Cache last 50 now playing artworks
    
    // Negative-cache marker kept only in memoryCache via CACHE_SENTINEL; no hard block set

    // Currently loading URIs to prevent duplicate work
    private val loadingUris = ConcurrentHashMap.newKeySet<String>()

    // Content-based cache to prevent duplicate extraction for same files
    private val contentCache = ConcurrentHashMap<String, String>()
    
    // Flow per URI for reactive updates
    private val uriFlows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    
    // Failure retry delay mechanism to prevent excessive failed attempts
    private val recentlyFailed = ConcurrentHashMap<String, Long>()
    private const val FAILURE_RETRY_DELAY_MS = 30_000L // 30 seconds
    
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
        
        // Initialize shared Coil image loader
        artworkImageLoader = ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.50)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("artwork_cache"))
                    .maxSizeBytes(1024 * 1024 * 100) // 100MB
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false) // Ignore HTTP cache headers for local files
            .allowHardware(true) // Allow hardware bitmaps for better performance
            .build()
        
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
     * Get the shared Coil image loader
     */
    fun getImageLoader(): ImageLoader? = artworkImageLoader
    
    /**
     * Get a flow that emits artwork URI updates for a given track URI
     */
    fun getArtworkFlow(trackUri: String): StateFlow<String?> {
        val flow = uriFlows.getOrPut(trackUri) {
            val cached = getCachedArtworkUri(trackUri)
            MutableStateFlow(cached)
        }
        return flow.asStateFlow()
    }
    
    /**
     * Get cached artwork URI if available, null otherwise
     */
    fun getCachedArtworkUri(trackUri: String): String? {
        // Check in-memory cache first
        val cached = memoryCache.get(trackUri)
        if (cached != null) {
            return if (cached == CACHE_SENTINEL) null else cached
        }

        // Fall back to disk cache on miss so process restarts still show art
        return try {
            val file = getDiskCacheFile(trackUri)
            if (file.exists()) {
                val fileUri = "file://${file.absolutePath}"
                // Warm memory cache and notify any existing flow listeners
                cacheArtworkUri(trackUri, fileUri)
                fileUri
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns true if we have permanently marked this track as having no artwork.
     * When true, no further extraction attempts should be made.
     */
    fun isNoArtwork(trackUri: String): Boolean {
        return memoryCache.get(trackUri) == CACHE_SENTINEL
    }
    
    /**
     * Load artwork for a track URI on-demand
     * This is the main entry point that follows Spotify's ID3/APIC extraction pattern
     */
    suspend fun loadArtwork(trackUri: String, hasEmbeddedArtwork: Boolean? = null): String? {
        if (trackUri.isBlank()) return null

        // Do not retry if previously marked as having no artwork
        if (isNoArtwork(trackUri)) {
            return null
        }

        // Check if we recently failed on this track to avoid excessive retries
        val lastFailureTime = recentlyFailed[trackUri]
        if (lastFailureTime != null && System.currentTimeMillis() - lastFailureTime < FAILURE_RETRY_DELAY_MS) {
            return null // Don't retry too soon after failure
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
                extractionSemaphore.acquire()
                try {
                    val result = extractAndCacheArtwork(trackUri)
                    if (result == null) {
                        // Permanently mark as no-artwork to disable retries
                        cacheNoArtwork(trackUri)
                    } else {
                        // Remove from failed list if successful
                        recentlyFailed.remove(trackUri)
                    }
                    result
                } finally {
                    extractionSemaphore.release()
                }
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
                // Fallback: try album art via MediaStore Albums
                val albumArtBytes = tryFetchAlbumArtBytes(trackUri)
                if (albumArtBytes != null && albumArtBytes.isNotEmpty()) {
                    val fallbackUri = processAndSaveArtwork(trackUri, albumArtBytes)
                    if (fallbackUri != null) {
                        cacheArtworkUri(trackUri, fallbackUri)
                        return fallbackUri
                    }
                }
            }
            
            // Mark as no artwork found
            cacheNoArtwork(trackUri)
            null
            
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting artwork from $trackUri", e)
            cacheNoArtwork(trackUri)
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
     * Try to fetch album artwork bytes from MediaStore using the track's albumId
     */
    private fun tryFetchAlbumArtBytes(trackUri: String): ByteArray? {
        return try {
            if (!trackUri.startsWith("content://")) return null
            val uri = Uri.parse(trackUri)
            val cr = appContext.contentResolver
            val cursor = cr.query(uri, arrayOf(MediaStore.Audio.Media.ALBUM_ID), null, null, null)
            val albumId = cursor?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null

            // Common album art content Uri pattern
            val albumArtUri = Uri.parse("content://media/external/audio/albumart").let { base ->
                android.content.ContentUris.withAppendedId(base, albumId)
            }
            val artworkBytes = cr.openInputStream(albumArtUri)?.use { input ->
                input.readBytes()
            }
            
            if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                Log.d(TAG, "Found album art via MediaStore for $trackUri (${artworkBytes.size} bytes)")
                return artworkBytes
            }
            
            // Reduce log verbosity - only log detailed failure reasons occasionally
            val shouldLogDetailed = (trackUri.hashCode() % 10 == 0) // Log 10% of failures in detail
            if (shouldLogDetailed) {
                Log.v(TAG, "Album art fallback failed for $trackUri: No album art found")
            }
            null
            
        } catch (e: Exception) {
            // Only log exceptions, not normal "not found" cases
            Log.v(TAG, "Album art fallback failed for $trackUri: ${e.message}")
            null
        }
    }
    
    /**
     * Process raw artwork bytes and save to disk cache
     */
    private suspend fun processAndSaveArtwork(trackUri: String, artworkBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing artwork bytes: ${artworkBytes.size} bytes for $trackUri")

            // Save raw bytes directly - let Coil handle decoding and optimization
            val cacheFile = getDiskCacheFile(trackUri)
            Log.d(TAG, "Saving artwork to: ${cacheFile.absolutePath}")

            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(artworkBytes)

            if (!cacheFile.exists()) {
                Log.e(TAG, "Failed to save artwork: file does not exist")
                return@withContext null
            }

            val fileUri = "file://${cacheFile.absolutePath}"
            Log.d(TAG, "Artwork processing complete: $fileUri")

            fileUri
        } catch (e: Exception) {
            Log.e(TAG, "Error processing artwork for $trackUri", e)
            null
        }
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
            val flow = uriFlows[trackUri]
            if (flow != null) {
                flow.value = artworkUri
            }
        }
    }
    
    /**
     * Cache that a track has no artwork to prevent future extraction attempts
     */
    private fun cacheNoArtwork(trackUri: String) {
        memoryCache.put(trackUri, CACHE_SENTINEL)
        // Update flow with null
        uriFlows[trackUri]?.value = null
        Log.v(TAG, "Cached no artwork for: $trackUri")
    }
    
    /**
     * Mark a URI as failed to avoid repeated attempts
     */
    private fun markAsFailed(trackUri: String) {
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
                    if (getCachedArtworkUri(trackUri) == null && !isNoArtwork(trackUri)) {
                        scope.launch { loadArtwork(trackUri) }
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
        uriFlows.clear()
        contentCache.clear()
        recentlyFailed.clear() // Clear failure retry tracking

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
            failedExtractions = recentlyFailed.size,
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
