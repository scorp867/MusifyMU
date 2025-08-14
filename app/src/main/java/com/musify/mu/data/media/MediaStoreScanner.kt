package com.musify.mu.data.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.util.Log
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

class MediaStoreScanner(private val context: Context, private val db: AppDatabase) {

    companion object {
        private const val TAG = "MediaStoreScanner"
        private const val CHUNK_SIZE = 100 // Process 100 songs at a time
        private const val SCAN_DELAY_MS = 50L // Small delay between chunks to avoid blocking
    }

    // Flow-based chunked scanning for better performance and real-time updates
    fun scanAndCacheFlow(): Flow<ScanProgress> = flow {
        emit(ScanProgress.Started)
        
        try {
            val totalTracks = getTotalTrackCount()
            emit(ScanProgress.Progress(0, totalTracks))
            
            if (totalTracks == 0) {
                emit(ScanProgress.NoTracksFound)
                return@flow
            }
            
            val allTracks = mutableListOf<Track>()
            var processedCount = 0
            
            // Process tracks in chunks
            var offset = 0
            while (offset < totalTracks) {
                try {
                    val chunkTracks = scanChunk(offset, CHUNK_SIZE)
                    allTracks.addAll(chunkTracks)
                    processedCount += chunkTracks.size
                    
                    // Cache chunk to database
                    if (chunkTracks.isNotEmpty()) {
                        db.dao().upsertTracks(chunkTracks)
                    }
                    
                    emit(ScanProgress.Progress(processedCount, totalTracks, chunkTracks))
                    
                    offset += CHUNK_SIZE
                    
                    // Small delay to prevent blocking UI thread
                    if (offset < totalTracks) {
                        delay(SCAN_DELAY_MS)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning chunk at offset $offset", e)
                    emit(ScanProgress.ChunkError(offset, e.message ?: "Unknown error"))
                    offset += CHUNK_SIZE // Continue with next chunk
                }
            }
            
            emit(ScanProgress.Completed(allTracks))
            
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during scanning", e)
            emit(ScanProgress.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    // Original method for backward compatibility
    suspend fun scanAndCache(): List<Track> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting media scan...")
            val tracks = mutableListOf<Track>()
            
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA
            )
            
            // Less restrictive selection - only require IS_MUSIC = 1 and size > 1KB
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND ${MediaStore.Audio.Media.SIZE} > ?"
            val selectionArgs = arrayOf("1000") // Reduced from 100KB to 1KB
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            
            Log.d(TAG, "Querying MediaStore with selection: $selection")
            
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val count = cursor.count
                Log.d(TAG, "MediaStore query returned $count potential tracks")
                
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                var processedCount = 0
                var validCount = 0
                
                while (cursor.moveToNext()) {
                    try {
                        processedCount++
                        val id = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val duration = cursor.getLong(durationCol)
                        val albumId = cursor.getLong(albumIdCol)
                        val dateAdded = cursor.getLong(dateAddedCol)
                        val size = cursor.getLong(sizeCol)
                        val dataPath = cursor.getString(dataCol)
                        
                        // More lenient validation - only check if duration is reasonable
                        val isValid = duration > 0 && dataPath != null && dataPath.isNotBlank()
                        
                        if (isValid) {
                            validCount++
                            // Skip artwork generation for faster scanning - will be loaded lazily
                            val artUri: String? = null
                            
                            tracks += Track(
                                mediaId = contentUri.toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                durationMs = duration,
                                artUri = artUri,
                                albumId = albumId,
                                dateAddedSec = dateAdded
                            )
                            
                            if (validCount <= 5) {
                                Log.d(TAG, "Added track: $title by $artist (duration: ${duration}ms, path: $dataPath)")
                            }
                        } else {
                            Log.v(TAG, "Skipped invalid track: $title (duration: $duration, path: $dataPath)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error processing track at cursor position $processedCount", e)
                    }
                }
                
                Log.d(TAG, "Processed $processedCount tracks, found $validCount valid tracks")
            } ?: run {
                Log.e(TAG, "MediaStore query returned null cursor - permission issue?")
            }

            Log.d(TAG, "Caching ${tracks.size} tracks to database...")
            // Cache to database for future use
            db.dao().upsertTracks(tracks)
            Log.d(TAG, "Media scan completed successfully with ${tracks.size} tracks")
            
            tracks
        } catch (e: Exception) {
            Log.e(TAG, "Error during media scan", e)
            emptyList()
        }
    }
    
    private suspend fun getTotalTrackCount(): Int = withContext(Dispatchers.IO) {
        try {
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND ${MediaStore.Audio.Media.SIZE} > ?"
            val selectionArgs = arrayOf("100000")
            
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf("COUNT(*)"),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(0)
                } else 0
            } ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting track count", e)
            0
        }
    }
    
    private suspend fun scanChunk(offset: Int, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA
            )
            
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND ${MediaStore.Audio.Media.SIZE} > ?"
            val selectionArgs = arrayOf("100000")
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC LIMIT $limit OFFSET $offset"
            
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val duration = cursor.getLong(durationCol)
                        val albumId = cursor.getLong(albumIdCol)
                        val dateAdded = cursor.getLong(dateAddedCol)
                        val dataPath = cursor.getString(dataCol)
                        
                        // Validate file existence
                        if (dataPath != null && File(dataPath).exists()) {
                            // Skip artwork generation in chunks for performance - do it lazily
                            tracks += Track(
                                mediaId = contentUri.toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                durationMs = duration,
                                artUri = null, // Load artwork lazily
                                albumId = albumId,
                                dateAddedSec = dateAdded
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error processing track in chunk", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning chunk", e)
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
                Log.w(TAG, "Failed to get track artwork for: $title", e)
                null
            }
        }
    }
    
    sealed class ScanProgress {
        object Started : ScanProgress()
        data class Progress(val scanned: Int, val total: Int, val latestTracks: List<Track> = emptyList()) : ScanProgress()
        data class ChunkError(val offset: Int, val error: String) : ScanProgress()
        data class Completed(val allTracks: List<Track>) : ScanProgress()
        data class Error(val message: String) : ScanProgress()
        object NoTracksFound : ScanProgress()
    }
}
