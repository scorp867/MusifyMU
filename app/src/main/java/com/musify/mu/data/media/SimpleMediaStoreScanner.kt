package com.musify.mu.data.media

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * MediaStore scanner that extracts ALL artwork during initial scan and stores permanently.
 * No on-demand loading - everything is cached at startup for smooth scrolling.
 */
class SimpleMediaStoreScanner(
    private val context: Context, 
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "SimpleMediaStoreScanner"
        private const val ARTWORK_CACHE_DIR = "startup_artwork_cache"
        private const val MAX_ARTWORK_SIZE = 512
    }
    
    private val artworkCacheDir: File by lazy {
        File(context.cacheDir, ARTWORK_CACHE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var contentObserver: MediaStoreContentObserver? = null
    
    // Enhanced projection with more metadata fields for better album art extraction
    private val BASIC_PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DATA,  // File path for better metadata extraction
        MediaStore.Audio.Media.ALBUM_ARTIST,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.TRACK
    )

    /**
     * Register content observer for real-time MediaStore changes
     */
    fun registerContentObserver(onChange: () -> Unit) {
        if (contentObserver != null) return
        
        contentObserver = MediaStoreContentObserver(Handler(Looper.getMainLooper())) { uri ->
            Log.d(TAG, "MediaStore changed: $uri")
            observerScope.launch {
                try {
                    val newTracks = scanTracks()
                    if (newTracks.isNotEmpty()) {
                        onChange()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error handling MediaStore change", e)
                }
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

    /**
     * Get album art URI from MediaStore
     */
    private fun getAlbumArtFromMediaStore(albumId: Long?): String? {
        if (albumId == null || albumId == 0L) return null
        
        return try {
            val albumArtUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId
            )
            // Check if the URI actually has content
            context.contentResolver.openInputStream(albumArtUri)?.use {
                // If we can open the stream, the album art exists
                albumArtUri.toString()
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Extract and cache artwork for a track during startup scan
     * Enhanced to match Media3 notification player's accuracy
     */
    private suspend fun extractAndCacheArtwork(trackUri: String, albumId: Long? = null): String? = withContext(Dispatchers.IO) {
        try {
            val cacheKey = generateCacheKey(trackUri)
            val cacheFile = File(artworkCacheDir, "$cacheKey.jpg")
            
            // Check if already cached
            if (cacheFile.exists()) {
                return@withContext "file://${cacheFile.absolutePath}"
            }
            
            // First try MediaStore album art (most accurate for many files)
            val mediaStoreArt = getAlbumArtFromMediaStore(albumId)
            if (mediaStoreArt != null) {
                Log.d(TAG, "Found album art from MediaStore for albumId: $albumId")
                return@withContext mediaStoreArt
            }
            
            val retriever = MediaMetadataRetriever()
            try {
                // Set data source with better error handling
                when {
                    trackUri.startsWith("content://") -> {
                        retriever.setDataSource(context, Uri.parse(trackUri))
                    }
                    trackUri.startsWith("/") -> {
                        retriever.setDataSource(trackUri)
                    }
                    else -> {
                        Log.w(TAG, "Unsupported URI format for artwork extraction: $trackUri")
                        return@withContext null
                    }
                }
                
                // Try multiple metadata keys for artwork (matching Media3's approach)
                var artworkBytes: ByteArray? = null
                
                // First try embedded picture (most accurate)
                artworkBytes = retriever.embeddedPicture
                
                // If no embedded picture, try to extract from video frame (for some formats)
                if (artworkBytes == null) {
                    try {
                        val frameAtTime = retriever.getFrameAtTime(0)
                        if (frameAtTime != null) {
                            val stream = java.io.ByteArrayOutputStream()
                            frameAtTime.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                            artworkBytes = stream.toByteArray()
                            frameAtTime.recycle()
                        }
                    } catch (e: Exception) {
                        // Frame extraction not supported for audio files
                    }
                }
                
                if (artworkBytes != null) {
                    val originalBitmap = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size)
                    if (originalBitmap != null) {
                        // Resize and save to cache
                        val resizedBitmap = resizeBitmap(originalBitmap, MAX_ARTWORK_SIZE)
                        saveBitmapToCache(resizedBitmap, cacheFile)
                        
                        // Clean up bitmaps
                        if (resizedBitmap != originalBitmap) {
                            originalBitmap.recycle()
                        }
                        resizedBitmap.recycle()
                        
                        return@withContext "file://${cacheFile.absolutePath}"
                    }
                }
                
                return@withContext null
                
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting artwork from $trackUri", e)
            return@withContext null
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
     * Save bitmap to cache file
     */
    private fun saveBitmapToCache(bitmap: Bitmap, cacheFile: File) {
        try {
            cacheFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            Log.d(TAG, "Saved startup artwork to cache: ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save startup artwork to cache", e)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        }
    }
    
    /**
     * Generate cache key from track URI
     */
    private fun generateCacheKey(trackUri: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(trackUri.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Scan MediaStore and extract ALL artwork during startup
     */
    suspend fun scanTracks(): List<Track> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting simple MediaStore scan...")
            
            // Check permissions first
            val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            
            val hasPermission = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission check - $requiredPermission: ${if (hasPermission) "GRANTED" else "DENIED"}")
            
            if (!hasPermission) {
                Log.e(TAG, "Required permission not granted: $requiredPermission")
                return@withContext emptyList()
            }
            
            // Check if we can access MediaStore at all
            try {
                val testUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val testCursor = context.contentResolver.query(
                    testUri,
                    arrayOf(MediaStore.Audio.Media._ID),
                    null,
                    null,
                    null
                )
                Log.d(TAG, "MediaStore accessibility test - cursor: ${testCursor != null}, count: ${testCursor?.count ?: "null"}")
                testCursor?.close()
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore accessibility test failed", e)
            }
            
            val tracks = mutableListOf<Track>()

            // Less restrictive selection - just basic audio files
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
            val selectionArgs: Array<String>? = null
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

            Log.d(TAG, "MediaStore query - URI: ${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}")
            Log.d(TAG, "MediaStore query - Selection: $selection")
            Log.d(TAG, "MediaStore query - Projection: ${BASIC_PROJECTION.contentToString()}")

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                BASIC_PROJECTION,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                Log.d(TAG, "MediaStore query returned ${cursor.count} tracks")

                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLong(idIndex)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val artist = cursor.getString(artistIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val album = cursor.getString(albumIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val duration = cursor.getLong(durationIndex)
                        val albumId = cursor.getLong(albumIdIndex)

                        // Validate essential fields - be less restrictive
                        if (duration >= 0) {  // Allow 0 duration for now
                            // Extract artwork during startup scan
                            val artworkUri = extractAndCacheArtwork(contentUri.toString(), albumId)
                            
                            val track = Track(
                                mediaId = contentUri.toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                durationMs = duration,
                                artUri = artworkUri, // Extracted and cached at startup
                                albumId = albumId
                            )
                            tracks.add(track)
                            
                            if (tracks.size <= 5) {
                                Log.d(TAG, "Added track: $title by $artist (${duration}ms) - artwork: ${if (artworkUri != null) "extracted" else "none"}")
                            }
                        } else {
                            Log.d(TAG, "Skipped track with invalid duration: $title (${duration}ms)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error processing track at cursor position ${cursor.position}", e)
                    }
                }
            } ?: run {
                Log.e(TAG, "MediaStore query returned null cursor")
            }

            // If no tracks found with IS_MUSIC filter, try without it
            if (tracks.isEmpty()) {
                Log.d(TAG, "No tracks found with IS_MUSIC filter, trying broader query...")
                
                // Try completely unrestricted query first
                val unrestrictedCursor = context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    null,
                    null,
                    null
                )
                Log.d(TAG, "Unrestricted query (ID only) returned ${unrestrictedCursor?.count ?: "null"} files")
                unrestrictedCursor?.close()
                
                // Now try the broader query with full projection
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    BASIC_PROJECTION,
                    null, // No selection - get all audio files
                    null,
                    sortOrder
                )?.use { cursor ->
                    Log.d(TAG, "Broader query returned ${cursor.count} audio files")
                    
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idIndex)
                            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                            val artist = cursor.getString(artistIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                            val album = cursor.getString(albumIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                            val duration = cursor.getLong(durationIndex)
                            val albumId = cursor.getLong(albumIdIndex)

                            // Basic validation
                            if (duration >= 0) {
                                // Extract artwork during startup scan (broad query)
                                val artworkUri = extractAndCacheArtwork(contentUri.toString(), albumId)
                                
                                val track = Track(
                                    mediaId = contentUri.toString(),
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    durationMs = duration,
                                    artUri = artworkUri, // Extracted and cached at startup
                                    albumId = albumId
                                )
                                tracks.add(track)
                                
                                if (tracks.size <= 5) {
                                    Log.d(TAG, "Added track (broad): $title by $artist (${duration}ms) - artwork: ${if (artworkUri != null) "extracted" else "none"}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error processing track in broad query", e)
                        }
                    }
                } ?: run {
                    Log.e(TAG, "Broader MediaStore query also returned null cursor")
                }
            }

            // Cache to database
            if (tracks.isNotEmpty()) {
                Log.d(TAG, "Caching ${tracks.size} tracks to database...")
                db.dao().upsertTracks(tracks)
                Log.d(TAG, "Simple scan completed with ${tracks.size} tracks")
            } else {
                Log.w(TAG, "No tracks found in MediaStore - this might indicate permission issues or no audio files")
            }

            tracks
        } catch (e: Exception) {
            Log.e(TAG, "Error during simple scan", e)
            emptyList()
        }
    }

    /**
     * Streaming variant of scan that reports progress batches via callback while scanning.
     * Returns the final complete list at the end.
     */
    suspend fun scanTracksStreaming(onProgress: (List<Track>) -> Unit): List<Track> = withContext(Dispatchers.IO) {
        val all = scanTracksWithProgress(onProgress)
        all
    }

    private suspend fun scanTracksWithProgress(onProgress: (List<Track>) -> Unit): List<Track> {
        try {
            val tracks = mutableListOf<Track>()
            val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            val hasPermission = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return emptyList()

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

            val emitBatch: () -> Unit = {
                // Emit a snapshot to avoid concurrent modification
                onProgress(tracks.toList())
            }

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                BASIC_PROJECTION,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                var sinceLastEmit = 0
                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLong(idIndex)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val artist = cursor.getString(artistIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val album = cursor.getString(albumIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val duration = cursor.getLong(durationIndex)
                        val albumId = cursor.getLong(albumIdIndex)
                        if (duration >= 0) {
                            val artworkUri = extractAndCacheArtwork(contentUri.toString(), albumId)
                            tracks.add(
                                Track(
                                    mediaId = contentUri.toString(),
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    durationMs = duration,
                                    artUri = artworkUri,
                                    albumId = albumId
                                )
                            )
                            sinceLastEmit++
                            if (sinceLastEmit >= 25) {
                                emitBatch()
                                sinceLastEmit = 0
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            // Broader query if initial returned none
            if (tracks.isEmpty()) {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    BASIC_PROJECTION,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    var sinceLastEmit = 0
                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idIndex)
                            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                            val artist = cursor.getString(artistIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                            val album = cursor.getString(albumIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                            val duration = cursor.getLong(durationIndex)
                            val albumId = cursor.getLong(albumIdIndex)
                            if (duration >= 0) {
                                val artworkUri = extractAndCacheArtwork(contentUri.toString(), albumId)
                                tracks.add(
                                    Track(
                                        mediaId = contentUri.toString(),
                                        title = title,
                                        artist = artist,
                                        album = album,
                                        durationMs = duration,
                                        artUri = artworkUri,
                                        albumId = albumId
                                    )
                                )
                                sinceLastEmit++
                                if (sinceLastEmit >= 25) {
                                    onProgress(tracks.toList())
                                    sinceLastEmit = 0
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }

            // Final emit
            onProgress(tracks.toList())
            return tracks
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Content observer for real-time MediaStore changes
     */
    private class MediaStoreContentObserver(
        handler: Handler,
        private val onChange: (Uri?) -> Unit
    ) : ContentObserver(handler) {
        
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            onChange(uri)
        }
    }
}
