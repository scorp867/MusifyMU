package com.musify.mu.data.media

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optimized MediaStore scanner with Spotify-like performance:
 * - Fast initial scan without artwork extraction
 * - Efficient projection with only needed fields
 * - Optimized sorting and filtering
 * - Minimal memory footprint during scan
 */
class OptimizedMediaStoreScanner(
    private val context: Context,
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "OptimizedMediaStoreScanner"
    }
    
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var contentObserver: MediaStoreContentObserver? = null
    
    // Optimized projection - only essential fields for fast scanning
    private val OPTIMIZED_PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.ARTIST_ID,
        MediaStore.Audio.Media.GENRE
    )
    
    /**
     * Optimized track scanning for Spotify-like performance
     */
    suspend fun scanTracksOptimized(): List<Track> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting optimized MediaStore scan...")
            
            // Check permissions
            val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            
            if (ContextCompat.checkSelfPermission(context, requiredPermission) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Required permission not granted: $requiredPermission")
                return@withContext emptyList()
            }
            
            val tracks = mutableListOf<Track>()
            
            // Optimized query with better selection criteria
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND ${MediaStore.Audio.Media.DURATION} > 30000" // Only music files longer than 30 seconds
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC" // Sort by date added for better Recently Added performance
            
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                OPTIMIZED_PROJECTION,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                Log.d(TAG, "MediaStore query returned ${cursor.count} tracks")
                
                // Get column indices once for performance
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val artistIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val genreIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
                
                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLong(idIndex)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val artist = cursor.getString(artistIndex)?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                        val album = cursor.getString(albumIndex)?.takeIf { it.isNotBlank() } ?: "Unknown Album"
                        val duration = cursor.getLong(durationIndex)
                        val albumId = cursor.getLong(albumIdIndex)
                        val dateAdded = cursor.getLong(dateAddedIndex)
                        val artistId = cursor.getLong(artistIdIndex)
                        val genre = cursor.getString(genreIndex)
                        
                        // Fast album art URI generation
                        val albumArtUri = if (albumId > 0) {
                            "content://media/external/audio/albumart/$albumId"
                        } else null
                        
                        val track = Track(
                            mediaId = contentUri.toString(),
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = duration,
                            artUri = albumArtUri,
                            albumId = albumId,
                            dateAddedSec = dateAdded,
                            artistId = artistId,
                            genre = genre
                        )
                        
                        tracks.add(track)
                        
                        // Log first few tracks for debugging
                        if (tracks.size <= 3) {
                            Log.d(TAG, "Added track: $title by $artist (album: $album, duration: ${duration}ms)")
                        }
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "Error processing track at cursor position ${cursor.position}", e)
                    }
                }
            } ?: run {
                Log.e(TAG, "MediaStore query returned null cursor")
            }
            
            Log.d(TAG, "Optimized scan completed: ${tracks.size} tracks")
            return@withContext tracks
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during optimized scan", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Register content observer for MediaStore changes
     */
    fun registerContentObserver(onChange: () -> Unit) {
        if (contentObserver != null) return
        
        contentObserver = MediaStoreContentObserver(Handler(Looper.getMainLooper())) { uri ->
            Log.d(TAG, "MediaStore changed: $uri")
            observerScope.launch {
                onChange()
            }
        }
        
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        Log.d(TAG, "MediaStore content observer registered")
    }
    
    /**
     * Unregister content observer
     */
    fun unregisterContentObserver() {
        contentObserver?.let { observer ->
            context.contentResolver.unregisterContentObserver(observer)
            contentObserver = null
            Log.d(TAG, "MediaStore content observer unregistered")
        }
    }
    
    // Content observer implementation
    private class MediaStoreContentObserver(
        handler: Handler,
        private val onChange: (android.net.Uri?) -> Unit
    ) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            super.onChange(selfChange, uri)
            onChange(uri)
        }
    }
}