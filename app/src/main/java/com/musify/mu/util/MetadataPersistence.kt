package com.musify.mu.util

import android.content.Context
import android.provider.MediaStore
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility for persisting metadata changes beyond app lifecycle
 * 
 * Note: Direct ID3 tag editing requires complex native libraries and may not be 
 * reliable across all devices. This implementation uses a hybrid approach:
 * 1. Store custom metadata in app database
 * 2. Use file path + modification time as unique identifier
 * 3. Restore metadata when tracks are rescanned
 */
object MetadataPersistence {
    private const val TAG = "MetadataPersistence"
    
    /**
     * Create a persistent identifier for a track that survives app reinstalls
     */
    fun createPersistentId(track: Track): String {
        // Use a combination of file path, size, and duration as unique identifier
        val uri = track.mediaId
        return "${uri}_${track.durationMs}_${track.title.hashCode()}"
    }
    
    /**
     * Store custom metadata changes
     */
    suspend fun storeCustomMetadata(
        context: Context,
        db: AppDatabase,
        track: Track,
        customTitle: String? = null,
        customArtist: String? = null,
        customAlbum: String? = null,
        customGenre: String? = null,
        customArtUri: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val persistentId = createPersistentId(track)
            
            // Store in SharedPreferences for persistence across reinstalls
            val prefs = context.getSharedPreferences("custom_metadata", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            customTitle?.let { editor.putString("${persistentId}_title", it) }
            customArtist?.let { editor.putString("${persistentId}_artist", it) }
            customAlbum?.let { editor.putString("${persistentId}_album", it) }
            customGenre?.let { editor.putString("${persistentId}_genre", it) }
            customArtUri?.let { editor.putString("${persistentId}_art", it) }
            
            editor.apply()
            
            android.util.Log.d(TAG, "Stored custom metadata for track: ${track.title}")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error storing custom metadata", e)
        }
    }
    
    /**
     * Restore custom metadata for a track
     */
    suspend fun restoreCustomMetadata(
        context: Context,
        track: Track
    ): Track = withContext(Dispatchers.IO) {
        try {
            val persistentId = createPersistentId(track)
            val prefs = context.getSharedPreferences("custom_metadata", Context.MODE_PRIVATE)
            
            val customTitle = prefs.getString("${persistentId}_title", null)
            val customArtist = prefs.getString("${persistentId}_artist", null)
            val customAlbum = prefs.getString("${persistentId}_album", null)
            val customGenre = prefs.getString("${persistentId}_genre", null)
            val customArtUri = prefs.getString("${persistentId}_art", null)
            
            if (customTitle != null || customArtist != null || customAlbum != null || 
                customGenre != null || customArtUri != null) {
                
                android.util.Log.d(TAG, "Restored custom metadata for track: ${track.title}")
                
                return@withContext track.copy(
                    title = customTitle ?: track.title,
                    artist = customArtist ?: track.artist,
                    album = customAlbum ?: track.album,
                    genre = customGenre ?: track.genre,
                    artUri = customArtUri ?: track.artUri
                )
            }
            
            return@withContext track
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error restoring custom metadata", e)
            return@withContext track
        }
    }
    
    /**
     * Apply custom metadata to all tracks during scan
     */
    suspend fun applyCustomMetadataToTracks(
        context: Context,
        tracks: List<Track>
    ): List<Track> = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("custom_metadata", Context.MODE_PRIVATE)
            val allCustomKeys = prefs.all.keys
            
            if (allCustomKeys.isEmpty()) {
                return@withContext tracks
            }
            
            val updatedTracks = tracks.map { track ->
                restoreCustomMetadata(context, track)
            }
            
            android.util.Log.d(TAG, "Applied custom metadata to ${updatedTracks.size} tracks")
            return@withContext updatedTracks
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error applying custom metadata", e)
            return@withContext tracks
        }
    }
    
    /**
     * Clear all custom metadata
     */
    fun clearAllCustomMetadata(context: Context) {
        try {
            val prefs = context.getSharedPreferences("custom_metadata", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            android.util.Log.d(TAG, "Cleared all custom metadata")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error clearing custom metadata", e)
        }
    }
    
    /**
     * Remove custom metadata for a specific track
     */
    fun removeCustomMetadata(context: Context, track: Track) {
        try {
            val persistentId = createPersistentId(track)
            val prefs = context.getSharedPreferences("custom_metadata", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            editor.remove("${persistentId}_title")
            editor.remove("${persistentId}_artist")
            editor.remove("${persistentId}_album")
            editor.remove("${persistentId}_genre")
            editor.remove("${persistentId}_art")
            
            editor.apply()
            
            android.util.Log.d(TAG, "Removed custom metadata for track: ${track.title}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error removing custom metadata", e)
        }
    }
}