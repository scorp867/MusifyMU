package com.musify.mu.data.localfiles.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.musify.mu.data.localfiles.proto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Spotify-style MediaStore reader with exact projection and filtering logic
 * Based on com.spotify.localfiles.mediastore.MediaStoreReader decompilation
 */
class MediaStoreReader private constructor(
    private val context: Context,
    private val options: MediaStoreReaderOptions
) {
    companion object {
        private const val TAG = "MediaStoreReader"
        
        @Volatile
        private var INSTANCE: MediaStoreReader? = null
        
        fun getInstance(context: Context, options: MediaStoreReaderOptions): MediaStoreReader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaStoreReader(context.applicationContext, options).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contentResolver: ContentResolver = context.contentResolver
    
    // Spotify's exact projection columns
    private val PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.IS_ALARM,
        MediaStore.Audio.Media.IS_MUSIC,
        MediaStore.Audio.Media.IS_NOTIFICATION,
        MediaStore.Audio.Media.IS_PODCAST,
        MediaStore.Audio.Media.IS_RINGTONE,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.MIME_TYPE,
        // API-gated columns
        *(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media.IS_AUDIOBOOK,
                MediaStore.Audio.Media.IS_PENDING,
                // Document scanning support
                "document_id",
                *(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    arrayOf(MediaStore.Audio.Media.IS_TRASHED)
                } else emptyArray())
            )
        } else emptyArray())
    )
    
    // ContentObserver for change detection
    private var contentObserver: ContentObserver? = null
    private val observedQueries = ConcurrentHashMap<String, Boolean>()
    
    /**
     * Run the main MediaStore query with Spotify's exact filtering logic
     */
    suspend fun runQuery(openedAudioFiles: OpenedAudioFiles? = null): QueryResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Spotify-style MediaStore query")
            
            val selection = buildSelection()
            val selectionArgs = buildSelectionArgs()
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            
            Log.d(TAG, "Query selection: $selection")
            Log.d(TAG, "Query args: ${selectionArgs?.joinToString() ?: "none"}")
            
            val localFiles = mutableListOf<LocalFile>()
            
            // Query MediaStore
            val mediaStoreFiles = queryMediaStore(selection, selectionArgs, sortOrder)
            localFiles.addAll(mediaStoreFiles)
            
            // Merge in opened audio files (permanent + temporary URIs)
            openedAudioFiles?.let { openedFiles ->
                val openedLocalFiles = processOpenedAudioFiles(openedFiles)
                localFiles.addAll(openedLocalFiles)
            }
            
            val result = QueryResult(
                localFiles = localFiles,
                totalCount = localFiles.size,
                scanTimestamp = System.currentTimeMillis()
            )
            
            Log.d(TAG, "Query completed with ${result.totalCount} files")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during MediaStore query", e)
            QueryResult()
        }
    }
    
    private suspend fun queryMediaStore(
        selection: String,
        selectionArgs: Array<String>?,
        sortOrder: String
    ): List<LocalFile> = withContext(Dispatchers.IO) {
        val localFiles = mutableListOf<LocalFile>()
        
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            Log.d(TAG, "MediaStore query returned ${cursor.count} rows")
            
            val columnIndices = getColumnIndices(cursor)
            
            while (cursor.moveToNext()) {
                try {
                    val localFile = processMediaStoreRow(cursor, columnIndices)
                    localFile?.let { localFiles.add(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing MediaStore row at position ${cursor.position}", e)
                }
            }
        }
        
        localFiles
    }
    
    private fun processMediaStoreRow(cursor: Cursor, indices: ColumnIndices): LocalFile? {
        try {
            val id = cursor.getLong(indices.id)
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
            )
            
            val metadata = extractMetadataFromCursor(cursor, indices)
            
            // Validate duration meets minimum requirement
            if ((metadata.duration ?: 0) < options.durationMin) {
                return null
            }
            
            // Check for embedded artwork during initial scan - Spotify's approach
            val hasEmbeddedArt = checkForEmbeddedArtwork(contentUri)
            val finalMetadata = metadata.copy(hasEmbeddedArtwork = hasEmbeddedArt)
            
            return LocalFile(
                path = contentUri.toString(),
                metadata = finalMetadata,
                imageState = if (hasEmbeddedArt) ImageState.HAS_IMAGE else ImageState.NO_IMAGE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error processing MediaStore row", e)
            return null
        }
    }
    
    private fun extractMetadataFromCursor(cursor: Cursor, indices: ColumnIndices): LocalFileMetadata {
        return LocalFileMetadata(
            title = cursor.getStringOrNull(indices.title),
            artist = cursor.getStringOrNull(indices.artist),
            album = cursor.getStringOrNull(indices.album),
            duration = cursor.getLongOrNull(indices.duration),
            year = cursor.getIntOrNull(indices.year),
            trackNumber = cursor.getIntOrNull(indices.track),
            mimeType = cursor.getStringOrNull(indices.mimeType),
            displayName = cursor.getStringOrNull(indices.displayName),
            dateAdded = cursor.getLongOrNull(indices.dateAdded),
            albumId = cursor.getLongOrNull(indices.albumId),
            isMusic = cursor.getInt(indices.isMusic) == 1,
            isAlarm = cursor.getInt(indices.isAlarm) == 1,
            isRingtone = cursor.getInt(indices.isRingtone) == 1,
            isNotification = cursor.getInt(indices.isNotification) == 1,
            isPodcast = cursor.getInt(indices.isPodcast) == 1,
            isAudiobook = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && indices.isAudiobook != -1) {
                cursor.getInt(indices.isAudiobook) == 1
            } else false,
            isPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && indices.isPending != -1) {
                cursor.getInt(indices.isPending) == 1
            } else false,
            isTrashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && indices.isTrashed != -1) {
                cursor.getInt(indices.isTrashed) == 1
            } else false
        )
    }
    
    private suspend fun processOpenedAudioFiles(openedFiles: OpenedAudioFiles): List<LocalFile> = withContext(Dispatchers.IO) {
        val localFiles = mutableListOf<LocalFile>()
        
        // Process both permanent and temporary files
        val allOpenedUris = openedFiles.getPermanentFiles() + openedFiles.getTemporaryFiles()
        
        allOpenedUris.forEach { uri ->
            try {
                val metadata = extractMetadataFromUri(uri)
                if ((metadata.duration ?: 0) >= options.durationMin) {
                    localFiles.add(
                        LocalFile(
                            path = uri.toString(),
                            metadata = metadata,
                            imageState = ImageState.UNKNOWN
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error processing opened audio file: $uri", e)
            }
        }
        
        localFiles
    }
    
    /**
     * Extract metadata from URI using MediaMetadataRetriever
     * This is where album art detection happens (getEmbeddedPicture())
     */
    private suspend fun extractMetadataFromUri(uri: Uri): LocalFileMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        return@withContext try {
            retriever.setDataSource(context, uri)
            
            // Check for embedded artwork (ID3/APIC) - Spotify's approach
            val hasEmbeddedArt = try {
                val artworkBytes = retriever.embeddedPicture
                artworkBytes != null && artworkBytes.isNotEmpty()
            } catch (e: Exception) {
                Log.v(TAG, "No embedded artwork found for $uri: ${e.message}")
                false
            }
            
            if (hasEmbeddedArt) {
                Log.d(TAG, "Found embedded artwork for: $uri")
            }
            
            LocalFileMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull(),
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull(),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                isMusic = true, // Assume opened files are music
                hasEmbeddedArtwork = hasEmbeddedArt // Set the flag based on actual artwork presence
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting metadata from $uri", e)
            LocalFileMetadata()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }
    
    /**
     * Build WHERE clause with Spotify's exact filtering logic
     */
    private fun buildSelection(): String {
        val conditions = mutableListOf<String>()
        
        // Always require IS_MUSIC = 1 (core Spotify filter)
        conditions.add("${MediaStore.Audio.Media.IS_MUSIC} = 1")
        
        // Exclude unwanted categories based on options
        if (!options.includeAlarms) {
            conditions.add("${MediaStore.Audio.Media.IS_ALARM} = 0")
        }
        if (!options.includeRingtones) {
            conditions.add("${MediaStore.Audio.Media.IS_RINGTONE} = 0")
        }
        if (!options.includeNotifications) {
            conditions.add("${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
        }
        if (!options.includePodcasts) {
            conditions.add("${MediaStore.Audio.Media.IS_PODCAST} = 0")
        }
        
        // API-gated filters for newer Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!options.includeAudiobooks) {
                conditions.add("${MediaStore.Audio.Media.IS_AUDIOBOOK} = 0")
            }
            conditions.add("${MediaStore.Audio.Media.IS_PENDING} = 0")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            conditions.add("${MediaStore.Audio.Media.IS_TRASHED} = 0")
        }
        
        // Minimum duration filter
        conditions.add("${MediaStore.Audio.Media.DURATION} >= ${options.durationMin}")
        
        return conditions.joinToString(" AND ")
    }
    
    private fun buildSelectionArgs(): Array<String>? {
        // No dynamic args needed with current implementation
        return null
    }
    
    private fun getColumnIndices(cursor: Cursor): ColumnIndices {
        return ColumnIndices(
            id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID),
            title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE),
            artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST),
            album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM),
            duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION),
            albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID),
            dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED),
            year = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR),
            track = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK),
            mimeType = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE),
            displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME),
            isMusic = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC),
            isAlarm = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM),
            isRingtone = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE),
            isNotification = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION),
            isPodcast = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PODCAST),
            isAudiobook = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.IS_AUDIOBOOK)
            } else -1,
            isPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.IS_PENDING)
            } else -1,
            isTrashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.IS_TRASHED)
            } else -1
        )
    }
    
    /**
     * Start listening for MediaStore changes with ContentObserver
     * Based on Spotify's MediaStoreReader.startListening()
     */
    fun startListening(onChange: (String) -> Unit) {
        if (contentObserver != null) return
        
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val handle = uri?.toString() ?: "unknown"
                Log.d(TAG, "MediaStore changed: $handle")
                
                scope.launch {
                    // Debounce rapid changes
                    kotlinx.coroutines.delay(500)
                    onChange(handle)
                }
            }
        }
        
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        
        Log.d(TAG, "Started listening for MediaStore changes")
    }
    
    /**
     * Stop listening for MediaStore changes
     */
    fun stopListening() {
        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
            contentObserver = null
            Log.d(TAG, "Stopped listening for MediaStore changes")
        }
    }
    
    fun cleanup() {
        stopListening()
        observedQueries.clear()
        Log.d(TAG, "MediaStoreReader cleaned up")
    }
    
    /**
     * Check if a URI has embedded artwork during initial scan
     * This is the optimization the user requested - only check artwork for tracks that have it
     */
    private fun checkForEmbeddedArtwork(uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val artworkBytes = retriever.embeddedPicture
            val hasArt = artworkBytes != null && artworkBytes.isNotEmpty()
            
            if (hasArt) {
                Log.v(TAG, "✓ Embedded artwork found for: $uri")
            } else {
                Log.v(TAG, "✗ No embedded artwork for: $uri")
            }
            
            hasArt
        } catch (e: Exception) {
            Log.v(TAG, "✗ Artwork check failed for: $uri - ${e.message}")
            false
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever during artwork check", e)
            }
        }
    }
    
    // Helper data class for column indices
    private data class ColumnIndices(
        val id: Int,
        val title: Int,
        val artist: Int,
        val album: Int,
        val duration: Int,
        val albumId: Int,
        val dateAdded: Int,
        val year: Int,
        val track: Int,
        val mimeType: Int,
        val displayName: Int,
        val isMusic: Int,
        val isAlarm: Int,
        val isRingtone: Int,
        val isNotification: Int,
        val isPodcast: Int,
        val isAudiobook: Int,
        val isPending: Int,
        val isTrashed: Int
    )
    
    // Extension functions for safe cursor access
    private fun Cursor.getStringOrNull(columnIndex: Int): String? {
        return if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex) else null
    }
    
    private fun Cursor.getLongOrNull(columnIndex: Int): Long? {
        return if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else null
    }
    
    private fun Cursor.getIntOrNull(columnIndex: Int): Int? {
        return if (columnIndex >= 0 && !isNull(columnIndex)) getInt(columnIndex) else null
    }
}
