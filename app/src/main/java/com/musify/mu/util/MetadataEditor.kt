package com.musify.mu.util

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Utility for editing audio file metadata using JAudioTagger
 */
object MetadataEditor {
    
    /**
     * Update metadata directly in the audio file
     * @return true if successful, false otherwise
     */
    suspend fun updateFileMetadata(
        context: Context,
        mediaUri: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        albumArtPath: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Convert content URI to file path if needed
            val filePath = when {
                mediaUri.startsWith("content://") -> {
                    // For content URIs, we need to get the actual file path
                    getFilePathFromUri(context, Uri.parse(mediaUri))
                }
                mediaUri.startsWith("/") -> mediaUri
                else -> null
            }
            
            if (filePath == null) {
                android.util.Log.e("MetadataEditor", "Cannot resolve file path for: $mediaUri")
                return@withContext false
            }
            
            val file = File(filePath)
            if (!file.exists() || !file.canWrite()) {
                android.util.Log.e("MetadataEditor", "File not accessible: $filePath")
                return@withContext false
            }
            
            // Read the audio file
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            
            // Update metadata fields
            title?.let { tag.setField(FieldKey.TITLE, it) }
            artist?.let { tag.setField(FieldKey.ARTIST, it) }
            album?.let { tag.setField(FieldKey.ALBUM, it) }
            albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
            
            // Update album art if provided
            if (albumArtPath != null) {
                try {
                    val artFile = when {
                        albumArtPath.startsWith("file://") -> File(albumArtPath.substring(7))
                        else -> File(albumArtPath)
                    }
                    
                    if (artFile.exists()) {
                        val artData = artFile.readBytes()
                        val artwork = org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(artFile)
                        tag.deleteArtworkField()
                        tag.setField(artwork)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MetadataEditor", "Failed to update album art", e)
                }
            }
            
            // Commit changes
            audioFile.commit()
            
            // Notify MediaScanner to update the MediaStore
            MediaScannerConnection.scanFile(
                context,
                arrayOf(filePath),
                null
            ) { path, uri ->
                android.util.Log.d("MetadataEditor", "File scanned: $path")
            }
            
            return@withContext true
            
        } catch (e: Exception) {
            android.util.Log.e("MetadataEditor", "Failed to update metadata", e)
            return@withContext false
        }
    }
    
    /**
     * Get file path from content URI
     */
    private fun getFilePathFromUri(context: Context, uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                    cursor.getString(columnIndex)
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.e("MetadataEditor", "Failed to get file path from URI", e)
            null
        }
    }
    
    /**
     * Check if metadata editing is supported for the given file
     */
    fun isEditingSupported(mediaUri: String): Boolean {
        val lowerUri = mediaUri.lowercase()
        return lowerUri.endsWith(".mp3") || 
               lowerUri.endsWith(".flac") || 
               lowerUri.endsWith(".m4a") || 
               lowerUri.endsWith(".ogg") ||
               lowerUri.endsWith(".opus") ||
               lowerUri.contains(".mp3") ||
               lowerUri.contains(".flac") ||
               lowerUri.contains(".m4a")
    }
}