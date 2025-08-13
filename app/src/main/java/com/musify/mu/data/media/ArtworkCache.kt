package com.musify.mu.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.collection.LruCache as AndroidXLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min

/**
 * Efficient artwork caching system with memory and disk tiers
 * Features:
 * - Memory cache for instant access to recently used artwork
 * - Disk cache for persistent storage across app sessions
 * - Automatic cache size management
 * - Background loading and processing
 * - Placeholder generation for missing artwork
 */
class ArtworkCache private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ArtworkCache"
        private const val DISK_CACHE_DIR = "artwork_cache"
        private const val MEMORY_CACHE_SIZE_RATIO = 0.125f // 1/8 of available memory
        private const val DISK_CACHE_SIZE_MB = 100 // 100MB disk cache
        private const val MAX_TEXTURE_SIZE = 2048 // Max texture size for GPU
        private const val THUMBNAIL_SIZE = 512 // Standard thumbnail size
        
        @Volatile
        private var INSTANCE: ArtworkCache? = null
        
        fun getInstance(context: Context): ArtworkCache {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtworkCache(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Memory cache for fast access
    private val memoryCache: LruCache<String, Bitmap>
    
    // Disk cache directory
    private val diskCacheDir: File
    
    // Cache statistics
    private var memoryHits = 0
    private var diskHits = 0
    private var misses = 0
    
    init {
        // Initialize memory cache
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMemory * MEMORY_CACHE_SIZE_RATIO).toInt()
        
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024 // Size in KB
            }
            
            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                if (evicted && !oldValue.isRecycled) {
                    Log.d(TAG, "Memory cache evicted: $key")
                }
            }
        }
        
        // Initialize disk cache
        diskCacheDir = File(context.cacheDir, DISK_CACHE_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        
        // Clean up old cache files if needed
        cleanupDiskCache()
        
        Log.i(TAG, "ArtworkCache initialized - Memory: ${cacheSize}KB, Disk: ${DISK_CACHE_SIZE_MB}MB")
    }
    
    /**
     * Get artwork for a track with automatic caching
     */
    suspend fun getArtwork(
        trackId: String,
        artworkUri: String?,
        size: Int = THUMBNAIL_SIZE,
        fallbackToPlaceholder: Boolean = true
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(trackId, artworkUri, size)
        
        // Try memory cache first
        memoryCache.get(cacheKey)?.let { bitmap ->
            memoryHits++
            Log.d(TAG, "Memory cache hit for: $trackId")
            return@withContext bitmap
        }
        
        // Try disk cache
        getDiskCached(cacheKey)?.let { bitmap ->
            // Add to memory cache for future access
            memoryCache.put(cacheKey, bitmap)
            diskHits++
            Log.d(TAG, "Disk cache hit for: $trackId")
            return@withContext bitmap
        }
        
        // Load from source
        val bitmap = try {
            when {
                artworkUri != null -> loadFromUri(artworkUri, size)
                fallbackToPlaceholder -> generatePlaceholder(trackId, size)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load artwork for $trackId", e)
            if (fallbackToPlaceholder) generatePlaceholder(trackId, size) else null
        }
        
        bitmap?.let {
            // Cache the loaded bitmap
            memoryCache.put(cacheKey, it)
            saveToDiskCache(cacheKey, it)
            Log.d(TAG, "Cached new artwork for: $trackId")
        } ?: run {
            misses++
            Log.d(TAG, "Artwork miss for: $trackId")
        }
        
        bitmap
    }
    
    /**
     * Preload artwork for multiple tracks
     */
    suspend fun preloadArtwork(trackIds: List<String>, artworkUris: List<String?>) = withContext(Dispatchers.IO) {
        require(trackIds.size == artworkUris.size) { "Track IDs and artwork URIs must have same size" }
        
        Log.i(TAG, "Preloading ${trackIds.size} artworks")
        
        trackIds.zip(artworkUris).forEach { (trackId, artworkUri) ->
            try {
                getArtwork(trackId, artworkUri, THUMBNAIL_SIZE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload artwork for $trackId", e)
            }
        }
    }
    
    /**
     * Clear specific artwork from cache
     */
    fun evict(trackId: String, artworkUri: String? = null) {
        val cacheKey = generateCacheKey(trackId, artworkUri, THUMBNAIL_SIZE)
        memoryCache.remove(cacheKey)
        
        val diskFile = File(diskCacheDir, cacheKey)
        if (diskFile.exists()) {
            diskFile.delete()
        }
        
        Log.d(TAG, "Evicted artwork for: $trackId")
    }
    
    /**
     * Clear all cached artwork
     */
    fun clearAll() {
        memoryCache.evictAll()
        
        diskCacheDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
        
        memoryHits = 0
        diskHits = 0
        misses = 0
        
        Log.i(TAG, "Cleared all artwork cache")
    }
    
    /**
     * Get cache statistics
     */
    fun getStatistics(): CacheStatistics {
        val total = memoryHits + diskHits + misses
        return CacheStatistics(
            memorySize = memoryCache.size(),
            memorySizeKB = memoryCache.size(),
            diskSizeMB = getDiskCacheSizeMB(),
            memoryHits = memoryHits,
            diskHits = diskHits,
            misses = misses,
            hitRate = if (total > 0) (memoryHits + diskHits).toFloat() / total else 0f
        )
    }
    
    // Private helper methods
    
    private fun generateCacheKey(trackId: String, artworkUri: String?, size: Int): String {
        val input = "$trackId:$artworkUri:$size"
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    private suspend fun loadFromUri(uri: String, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val inputStream = when {
                uri.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(uri))
                uri.startsWith("file://") -> File(Uri.parse(uri).path ?: return@withContext null).inputStream()
                else -> File(uri).inputStream()
            }
            
            inputStream?.use { stream ->
                // Decode with appropriate size
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                
                BitmapFactory.decodeStream(stream, null, options)
                
                // Calculate sample size
                options.inSampleSize = calculateInSampleSize(options, size, size)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
                
                // Re-open stream for actual decoding
                val newStream = when {
                    uri.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(uri))
                    uri.startsWith("file://") -> File(Uri.parse(uri).path ?: return@withContext null).inputStream()
                    else -> File(uri).inputStream()
                }
                
                newStream?.use { actualStream ->
                    BitmapFactory.decodeStream(actualStream, null, options)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bitmap from URI: $uri", e)
            null
        }
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
    
    private fun generatePlaceholder(trackId: String, size: Int): Bitmap {
        // Generate a simple colored placeholder based on track ID
        val colors = arrayOf(
            0xFF6200EE.toInt(), 0xFF3700B3.toInt(), 0xFF03DAC6.toInt(),
            0xFFFF6200.toInt(), 0xFFFF3700.toInt(), 0xFF00DAC6.toInt()
        )
        
        val color = colors[trackId.hashCode().and(Int.MAX_VALUE) % colors.size]
        
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            eraseColor(color)
        }
    }
    
    private fun getDiskCached(cacheKey: String): Bitmap? {
        val file = File(diskCacheDir, cacheKey)
        return if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load from disk cache: $cacheKey", e)
                file.delete() // Remove corrupted file
                null
            }
        } else null
    }
    
    private suspend fun saveToDiskCache(cacheKey: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        try {
            val file = File(diskCacheDir, cacheKey)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save to disk cache: $cacheKey", e)
        }
    }
    
    private fun getDiskCacheSizeMB(): Float {
        val files = diskCacheDir.listFiles() ?: return 0f
        val totalBytes = files.sumOf { if (it.isFile) it.length() else 0L }
        return totalBytes / (1024f * 1024f)
    }
    
    private fun cleanupDiskCache() {
        val files = diskCacheDir.listFiles() ?: return
        val currentSizeMB = getDiskCacheSizeMB()
        
        if (currentSizeMB > DISK_CACHE_SIZE_MB) {
            // Sort by last modified and delete oldest files
            val sortedFiles = files.filter { it.isFile }
                .sortedBy { it.lastModified() }
            
            var deletedSizeMB = 0f
            val targetDeleteMB = currentSizeMB - DISK_CACHE_SIZE_MB * 0.8f // Leave 20% buffer
            
            for (file in sortedFiles) {
                if (deletedSizeMB >= targetDeleteMB) break
                
                val fileSizeMB = file.length() / (1024f * 1024f)
                if (file.delete()) {
                    deletedSizeMB += fileSizeMB
                }
            }
            
            Log.i(TAG, "Cleaned up disk cache: deleted ${deletedSizeMB}MB")
        }
    }
    
    data class CacheStatistics(
        val memorySize: Int,
        val memorySizeKB: Int,
        val diskSizeMB: Float,
        val memoryHits: Int,
        val diskHits: Int,
        val misses: Int,
        val hitRate: Float
    )
}