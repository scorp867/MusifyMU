package com.musify.mu.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Enhanced artwork manager that mimics Media3's approach for album art extraction.
 * Features:
 * - On-demand loading for visible items only
 * - Session-based memory caching
 * - Multiple fallback strategies (embedded tags, same directory, album art URI)
 * - Efficient memory management
 * - Background processing
 */
class ArtworkManager private constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "ArtworkManager"
        private const val MAX_CACHE_SIZE = 50 * 1024 * 1024 // 50MB memory cache
        private const val MAX_ARTWORK_SIZE = 512
        
        @Volatile
        private var INSTANCE: ArtworkManager? = null
        
        fun getInstance(context: Context): ArtworkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtworkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Session-based memory cache for currently visible artworks
    private val memoryCache = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }
        
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }
    
    // Disk cache directory for persistent storage
    private val diskCacheDir: File by lazy {
        File(context.cacheDir, "artwork_cache").apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * Load artwork for a track using Media3-like approach with multiple fallbacks
     */
    suspend fun loadArtwork(
        mediaId: String,
        albumId: Long? = null,
        audioUri: String? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(mediaId, albumId)
        
        // 1. Check memory cache first
        memoryCache.get(cacheKey)?.let { bitmap ->
            if (!bitmap.isRecycled) {
                Log.d(TAG, "Artwork found in memory cache for: $mediaId")
                return@withContext bitmap
            }
        }
        
        // 2. Check disk cache
        getDiskCachedArtwork(cacheKey)?.let { bitmap ->
            // Add to memory cache for faster access
            memoryCache.put(cacheKey, bitmap)
            Log.d(TAG, "Artwork found in disk cache for: $mediaId")
            return@withContext bitmap
        }
        
        // 3. Extract artwork using multiple fallback strategies
        val extractedBitmap = extractArtworkWithFallbacks(mediaId, albumId, audioUri)
        
        // 4. Cache the result if found
        extractedBitmap?.let { bitmap ->
            memoryCache.put(cacheKey, bitmap)
            saveToDiskCache(cacheKey, bitmap)
            Log.d(TAG, "Artwork extracted and cached for: $mediaId")
        }
        
        extractedBitmap
    }
    
    /**
     * Extract artwork using multiple fallback strategies similar to Media3
     */
    private suspend fun extractArtworkWithFallbacks(
        mediaId: String,
        albumId: Long?,
        audioUri: String?
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Strategy 1: Embedded metadata tags (like Media3 does)
            extractEmbeddedArtwork(mediaId, audioUri)?.let { return@withContext it }
            
            // Strategy 2: MediaStore album art URI (Android system cache)
            albumId?.let { id ->
                extractFromAlbumArtUri(id)?.let { return@withContext it }
            }
            
            // Strategy 3: Same directory fallback (cover.jpg, folder.jpg, etc.)
            audioUri?.let { uri ->
                extractFromSameDirectory(uri)?.let { return@withContext it }
            }
            
            Log.d(TAG, "No artwork found for: $mediaId")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting artwork for $mediaId", e)
            null
        }
    }
    
    /**
     * Extract embedded artwork from audio file metadata (primary method)
     */
    private suspend fun extractEmbeddedArtwork(
        mediaId: String,
        audioUri: String?
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (audioUri.isNullOrEmpty()) return@withContext null
        
        val retriever = MediaMetadataRetriever()
        try {
            // Set data source based on URI type
            when {
                audioUri.startsWith("content://") -> {
                    retriever.setDataSource(context, Uri.parse(audioUri))
                }
                audioUri.startsWith("/") -> {
                    retriever.setDataSource(audioUri)
                }
                else -> {
                    Log.w(TAG, "Unsupported URI format for embedded extraction: $audioUri")
                    return@withContext null
                }
            }
            
            // Extract embedded picture (ID3 tags, FLAC comments, etc.)
            val artworkBytes = retriever.embeddedPicture
            if (artworkBytes != null) {
                val originalBitmap = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size)
                if (originalBitmap != null) {
                    val resizedBitmap = resizeBitmap(originalBitmap, MAX_ARTWORK_SIZE)
                    if (resizedBitmap != originalBitmap) {
                        originalBitmap.recycle()
                    }
                    Log.d(TAG, "Extracted embedded artwork for: $mediaId")
                    return@withContext resizedBitmap
                }
            }
            
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting embedded artwork from $audioUri", e)
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
     * Extract artwork from MediaStore album art URI
     */
    private suspend fun extractFromAlbumArtUri(albumId: Long): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val artworkUri = Uri.parse("content://media/external/audio/albumart/$albumId")
            context.contentResolver.openInputStream(artworkUri)?.use { inputStream ->
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                if (originalBitmap != null) {
                    val resizedBitmap = resizeBitmap(originalBitmap, MAX_ARTWORK_SIZE)
                    if (resizedBitmap != originalBitmap) {
                        originalBitmap.recycle()
                    }
                    Log.d(TAG, "Extracted album art from MediaStore for albumId: $albumId")
                    return@withContext resizedBitmap
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting from album art URI for albumId: $albumId", e)
            null
        }
    }
    
    /**
     * Fallback: Look for common cover art files in the same directory
     */
    private suspend fun extractFromSameDirectory(audioUri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (!audioUri.startsWith("/")) return@withContext null
            
            val audioFile = File(audioUri)
            val parentDir = audioFile.parentFile ?: return@withContext null
            
            // Common album art filenames
            val artworkFilenames = listOf(
                "cover.jpg", "cover.jpeg", "cover.png",
                "folder.jpg", "folder.jpeg", "folder.png",
                "album.jpg", "album.jpeg", "album.png",
                "front.jpg", "front.jpeg", "front.png"
            )
            
            for (filename in artworkFilenames) {
                val artworkFile = File(parentDir, filename)
                if (artworkFile.exists() && artworkFile.canRead()) {
                    try {
                        val originalBitmap = BitmapFactory.decodeFile(artworkFile.absolutePath)
                        if (originalBitmap != null) {
                            val resizedBitmap = resizeBitmap(originalBitmap, MAX_ARTWORK_SIZE)
                            if (resizedBitmap != originalBitmap) {
                                originalBitmap.recycle()
                            }
                            Log.d(TAG, "Found artwork file: ${artworkFile.absolutePath}")
                            return@withContext resizedBitmap
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error decoding artwork file: ${artworkFile.absolutePath}", e)
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error searching for artwork in same directory", e)
            null
        }
    }
    
    /**
     * Get artwork from disk cache
     */
    private fun getDiskCachedArtwork(cacheKey: String): Bitmap? {
        val cacheFile = File(diskCacheDir, "$cacheKey.jpg")
        return if (cacheFile.exists()) {
            try {
                BitmapFactory.decodeFile(cacheFile.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Error reading disk cached artwork", e)
                cacheFile.delete() // Remove corrupted cache file
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Save artwork to disk cache
     */
    private fun saveToDiskCache(cacheKey: String, bitmap: Bitmap) {
        try {
            val cacheFile = File(diskCacheDir, "$cacheKey.jpg")
            cacheFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error saving artwork to disk cache", e)
        }
    }
    
    /**
     * Resize bitmap to maximum size while maintaining aspect ratio
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }
        
        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Generate cache key from media ID and album ID
     */
    private fun generateCacheKey(mediaId: String, albumId: Long?): String {
        val input = if (albumId != null) "$mediaId:$albumId" else mediaId
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Clear memory cache (useful for memory pressure)
     */
    fun clearMemoryCache() {
        memoryCache.evictAll()
        Log.d(TAG, "Memory cache cleared")
    }
    
    /**
     * Clear disk cache
     */
    fun clearDiskCache() {
        try {
            diskCacheDir.listFiles()?.forEach { file ->
                if (file.isFile) file.delete()
            }
            Log.d(TAG, "Disk cache cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing disk cache", e)
        }
    }
    
    /**
     * Get current memory cache size
     */
    fun getMemoryCacheSize(): String {
        val sizeBytes = memoryCache.size()
        return "${sizeBytes / (1024 * 1024)}MB"
    }
}