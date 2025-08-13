package com.musify.mu.data.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import android.os.Build

class MediaStoreScanner(private val context: Context, private val db: AppDatabase) {

    companion object {
        private const val CHUNK_SIZE = 100 // Load songs in chunks of 100
        private const val TAG = "MediaStoreScanner"
    }

    suspend fun scanAndCache(onProgress: ((Int, Int) -> Unit)? = null): List<Track> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA // Add DATA field for file path
        )
        
        // Use different URIs based on Android version
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        
        val tracks = mutableListOf<Track>()
        var totalCount = 0
        
        try {
            // Build selection criteria - simplified for better compatibility
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            
            // First, get the total count
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                selection,
                null,
                null
            )?.use { cursor ->
                totalCount = cursor.count
            }
            
            // Now scan in chunks
            var offset = 0
            while (offset < totalCount) {
                val chunkTracks = mutableListOf<Track>()
                
                // Build sort order with LIMIT and OFFSET for chunking
                val sortOrder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC LIMIT $CHUNK_SIZE OFFSET $offset"
                } else {
                    // For older versions, we'll process all at once but yield periodically
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                }
                
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    
                    var processedInChunk = 0
                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idCol)
                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 
                                id
                            )
                            val title = cursor.getString(titleCol) ?: "Unknown"
                            val artist = cursor.getString(artistCol) ?: "Unknown"
                            val album = cursor.getString(albumCol) ?: "Unknown"
                            val duration = cursor.getLong(durationCol)
                            val albumId = cursor.getLong(albumIdCol)
                            val dateAdded = cursor.getLong(dateAddedCol)
                            
                            // Skip very short files (less than 10 seconds) as they might be ringtones
                            if (duration < 10000) continue
                            
                            // For older Android versions, check if file exists
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && dataCol >= 0) {
                                val filePath = cursor.getString(dataCol)
                                if (filePath != null) {
                                    // Skip files in system directories
                                    if (filePath.contains("/Android/", ignoreCase = true) ||
                                        filePath.contains("/ringtones/", ignoreCase = true) ||
                                        filePath.contains("/notifications/", ignoreCase = true) ||
                                        filePath.contains("/alarms/", ignoreCase = true)) {
                                        continue
                                    }
                                    
                                    // Check if file exists
                                    if (!File(filePath).exists()) {
                                        continue
                                    }
                                }
                            }
                            
                            val artUri = try {
                                getOrCreateTrackArtwork(contentUri.toString(), albumId, title, artist, album)
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "Failed to load artwork for $title", e)
                                null
                            }
                            
                            chunkTracks += Track(
                                mediaId = contentUri.toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                durationMs = duration,
                                artUri = artUri,
                                albumId = albumId,
                                dateAddedSec = dateAdded
                            )
                            
                            processedInChunk++
                            
                            // For older Android versions, break manually after CHUNK_SIZE
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && processedInChunk >= CHUNK_SIZE) {
                                break
                            }
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Error processing track", e)
                        }
                        
                        // Allow other coroutines to run
                        if (processedInChunk % 10 == 0) {
                            yield()
                        }
                    }
                }
                
                // Save chunk to database
                if (chunkTracks.isNotEmpty()) {
                    try {
                        db.dao().upsertTracks(chunkTracks)
                        tracks.addAll(chunkTracks)
                        
                        // Report progress
                        onProgress?.invoke(tracks.size, totalCount)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to save tracks to database", e)
                    }
                }
                
                // For older Android versions, we processed everything in one go
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    break
                }
                
                offset += CHUNK_SIZE
                
                // Allow other coroutines to run between chunks
                yield()
            }
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Permission denied while scanning media", e)
            // Return what we have from the database
            return@withContext db.dao().getAllTracks()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error scanning media store", e)
            // Return what we have from the database
            return@withContext db.dao().getAllTracks()
        }
        
        tracks
    }
    
    private suspend fun getOrCreateTrackArtwork(trackUri: String, albumId: Long, title: String, artist: String, album: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Create unique filename for this track
                val trackKey = "${trackUri.hashCode()}_${title.trim()}_${artist.trim()}"
                val hashKey = trackKey.hashCode().toString()
                val filename = "track_art_${hashKey}.jpg"
                
                val artDir = File(context.filesDir, "track_artwork")
                if (!artDir.exists()) {
                    artDir.mkdirs()
                }
                
                val artFile = File(artDir, filename)
                
                // If file already exists, return its URI
                if (artFile.exists()) {
                    return@withContext Uri.fromFile(artFile).toString()
                }
                
                // Try to get artwork from the individual track first
                val bitmap = try {
                    val uri = Uri.parse(trackUri)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                    } else {
                        // For older versions, try to get track artwork
                        val cursor = context.contentResolver.query(
                            uri,
                            arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                            null,
                            null,
                            null
                        )
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val trackAlbumId = it.getLong(0)
                                if (trackAlbumId != 0L) {
                                    val albumArtUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, trackAlbumId)
                                    val albumCursor = context.contentResolver.query(
                                        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                        arrayOf(MediaStore.Audio.Albums.ALBUM_ART),
                                        "${MediaStore.Audio.Albums._ID} = ?",
                                        arrayOf(trackAlbumId.toString()),
                                        null
                                    )
                                    albumCursor?.use { ac ->
                                        if (ac.moveToFirst()) {
                                            val artPath = ac.getString(0)
                                            if (artPath != null) {
                                                BitmapFactory.decodeFile(artPath)
                                            } else null
                                        } else null
                                    }
                                } else null
                            } else null
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to album artwork if track artwork fails
                    if (albumId != 0L) {
                        val albumArtUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                context.contentResolver.loadThumbnail(albumArtUri, Size(512, 512), null)
                            } else {
                                val cursor = context.contentResolver.query(
                                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                    arrayOf(MediaStore.Audio.Albums.ALBUM_ART),
                                    "${MediaStore.Audio.Albums._ID} = ?",
                                    arrayOf(albumId.toString()),
                                    null
                                )
                                cursor?.use {
                                    if (it.moveToFirst()) {
                                        val artPath = it.getString(0)
                                        if (artPath != null) {
                                            BitmapFactory.decodeFile(artPath)
                                        } else null
                                    } else null
                                }
                            }
                        } catch (e2: Exception) {
                            null
                        }
                    } else null
                }
                
                // Save bitmap to file
                bitmap?.let {
                    FileOutputStream(artFile).use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    Uri.fromFile(artFile).toString()
                } ?: run {
                    // Return null if we can't process the image
                    null
                }
            } catch (e: Exception) {
                android.util.Log.w("MediaStoreScanner", "Failed to get track artwork for: $title", e)
                null
            }
        }
    }
}
