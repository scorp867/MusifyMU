package com.musify.mu.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Manager for handling custom album artwork
 */
object AlbumArtManager {
    private const val CUSTOM_ART_DIR = "custom_album_art"
    private const val MAX_IMAGE_SIZE = 1024 // Max width/height in pixels
    
    /**
     * Save custom album art from gallery URI
     * @return The file URI of the saved image, or null if failed
     */
    suspend fun saveCustomAlbumArt(
        context: Context,
        mediaId: String,
        sourceUri: Uri
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Create directory for custom album art
            val artDir = File(context.filesDir, CUSTOM_ART_DIR)
            if (!artDir.exists()) {
                artDir.mkdirs()
            }
            
            // Generate filename based on mediaId
            val filename = "${mediaId.md5()}.jpg"
            val destFile = File(artDir, filename)
            
            // Copy and resize image
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                // Decode bitmap with bounds only first
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                
                // Calculate sample size
                options.inSampleSize = calculateInSampleSize(options, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)
                options.inJustDecodeBounds = false
                
                // Decode actual bitmap
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream, null, options)
                    bitmap?.let {
                        // Save to file
                        FileOutputStream(destFile).use { out ->
                            it.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        it.recycle()
                        
                        // Update the artwork caches
                        val fileUri = "file://${destFile.absolutePath}"
                        OptimizedArtworkLoader.cacheUri(mediaId, fileUri)
                        OnDemandArtworkLoader.cacheUri(mediaId, fileUri)
                        
                        return@withContext fileUri
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            android.util.Log.e("AlbumArtManager", "Failed to save custom album art", e)
            null
        }
    }
    
    /**
     * Get custom album art if exists
     */
    fun getCustomAlbumArt(context: Context, mediaId: String): String? {
        val artDir = File(context.filesDir, CUSTOM_ART_DIR)
        val artFile = File(artDir, "${mediaId.md5()}.jpg")
        return if (artFile.exists()) {
            "file://${artFile.absolutePath}"
        } else {
            null
        }
    }
    
    /**
     * Delete custom album art
     */
    fun deleteCustomAlbumArt(context: Context, mediaId: String): Boolean {
        val artDir = File(context.filesDir, CUSTOM_ART_DIR)
        val artFile = File(artDir, "${mediaId.md5()}.jpg")
        return if (artFile.exists()) {
            artFile.delete()
        } else {
            false
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
    
    private fun String.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}