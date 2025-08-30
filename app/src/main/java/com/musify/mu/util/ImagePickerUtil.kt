package com.musify.mu.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility for handling custom image selection and processing
 */
object ImagePickerUtil {
    private const val TAG = "ImagePickerUtil"
    private const val MAX_IMAGE_SIZE = 1024 // Max size for custom artwork
    private const val QUALITY = 85 // JPEG quality
    
    /**
     * Save custom image to app's private directory
     */
    suspend fun saveCustomImage(
        context: Context,
        sourceUri: Uri,
        mediaId: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext null
            
            val bitmap = BitmapFactory.decodeStream(inputStream)
                ?: return@withContext null
            
            inputStream.close()
            
            // Rotate bitmap if needed based on EXIF data
            val rotatedBitmap = rotateImageIfRequired(context, sourceUri, bitmap)
            
            // Resize to optimal size
            val resizedBitmap = resizeBitmap(rotatedBitmap, MAX_IMAGE_SIZE)
            
            // Create custom artwork directory
            val customArtDir = File(context.filesDir, "custom_artwork")
            if (!customArtDir.exists()) {
                customArtDir.mkdirs()
            }
            
            // Generate filename based on mediaId
            val filename = "${mediaId.hashCode()}.jpg"
            val outputFile = File(customArtDir, filename)
            
            // Save the processed image
            FileOutputStream(outputFile).use { out ->
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            
            // Clean up bitmaps
            if (resizedBitmap != rotatedBitmap) {
                rotatedBitmap.recycle()
            }
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            resizedBitmap.recycle()
            
            return@withContext "file://${outputFile.absolutePath}"
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error saving custom image", e)
            return@withContext null
        }
    }
    
    /**
     * Rotate image based on EXIF orientation
     */
    private fun rotateImageIfRequired(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return bitmap
            
            val exif = ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            
            inputStream.close()
            
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                else -> bitmap
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not rotate image", e)
            bitmap
        }
    }
    
    /**
     * Rotate bitmap by specified degrees
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    /**
     * Resize bitmap to fit within max size while maintaining aspect ratio
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
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
    
    /**
     * Get custom image path for a media ID
     */
    fun getCustomImagePath(context: Context, mediaId: String): String? {
        val customArtDir = File(context.filesDir, "custom_artwork")
        val filename = "${mediaId.hashCode()}.jpg"
        val file = File(customArtDir, filename)
        
        return if (file.exists()) {
            "file://${file.absolutePath}"
        } else {
            null
        }
    }
    
    /**
     * Delete custom image for a media ID
     */
    fun deleteCustomImage(context: Context, mediaId: String): Boolean {
        return try {
            val customArtDir = File(context.filesDir, "custom_artwork")
            val filename = "${mediaId.hashCode()}.jpg"
            val file = File(customArtDir, filename)
            
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error deleting custom image", e)
            false
        }
    }
}

/**
 * Composable for image picker with permission handling
 */
@Composable
fun rememberImagePicker(
    onImageSelected: (Uri) -> Unit
): () -> Unit {
    val context = LocalContext.current
    
    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onImageSelected(it) }
    }
    
    // Storage permission launcher for Android 13+
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            imagePickerLauncher.launch("image/*")
        } else {
            android.widget.Toast.makeText(
                context,
                "Storage permission is required to select images",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    
    return remember {
        {
            // Check if we need storage permission (Android 13+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_IMAGES
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                if (hasPermission) {
                    imagePickerLauncher.launch("image/*")
                } else {
                    storagePermissionLauncher.launch(android.Manifest.permission.READ_MEDIA_IMAGES)
                }
            } else {
                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                if (hasPermission) {
                    imagePickerLauncher.launch("image/*")
                } else {
                    storagePermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }
}