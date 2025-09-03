package com.musify.mu.data.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Spotify-style MediaStore reader that follows their scanning architecture
 * Uses protobuf for efficient data serialization and caching
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

    // Projection columns based on Spotify's implementation
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
        // API-gated columns
        *(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media.IS_AUDIOBOOK,
                MediaStore.Audio.Media.IS_PENDING,
                *(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    arrayOf(MediaStore.Audio.Media.IS_TRASHED)
                } else emptyArray())
            )
        } else emptyArray())
    )

    private val _scanResults = MutableStateFlow<List<Track>>(emptyList())
    val scanResults: StateFlow<List<Track>> = _scanResults.asStateFlow()

    private var contentObserver: ContentObserver? = null

    /**
     * MediaStore reader options following Spotify's configuration
     */
    data class MediaStoreReaderOptions(
        val minDurationMs: Long = 1000, // Minimum duration filter
        val includeAlarms: Boolean = false,
        val includeRingtones: Boolean = false,
        val includeNotifications: Boolean = false,
        val includePodcasts: Boolean = false,
        val includeAudiobooks: Boolean = true,
        val cacheDir: File
    )

    /**
     * Run the main MediaStore query with Spotify-style filtering
     */
    suspend fun runQuery(): List<Track> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Spotify-style MediaStore query")

            val selection = buildSelection()
            val selectionArgs = buildSelectionArgs()
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            Log.d(TAG, "Query selection: $selection")
            Log.d(TAG, "Query args: ${selectionArgs?.joinToString() ?: "none"}")

            val tracks = mutableListOf<Track>()

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                Log.d(TAG, "Query returned ${cursor.count} potential tracks")

                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLong(idIndex)
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        )

                        val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val artist = cursor.getString(artistIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val album = cursor.getString(albumIndex)?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val duration = cursor.getLong(durationIndex)
                        val albumId = cursor.getLong(albumIdIndex)
                        val dateAdded = cursor.getLong(dateAddedIndex)

                        // Validate track meets our criteria
                        if (duration >= options.minDurationMs) {
                            // Only check for album art existence, don't extract it yet
                            val (hasEmbeddedArt, metadata) = checkMetadataOnly(contentUri)

                            val track = Track(
                                mediaId = contentUri.toString(),
                                title = metadata.title ?: title,
                                artist = metadata.artist ?: artist,
                                album = metadata.album ?: album,
                                durationMs = metadata.duration ?: duration,
                                artUri = if (hasEmbeddedArt) {
                                    // Mark that this track has embedded art, but don't extract it yet
                                    "has_embedded_art:${contentUri}"
                                } else {
                                    // Fallback to album art URI
                                    albumId.takeIf { it > 0 }?.let {
                                        "content://media/external/audio/albumart/$it"
                                    }
                                },
                                albumId = albumId,
                                dateAddedSec = dateAdded,
                                // Additional metadata from MediaMetadataRetriever
                                genre = metadata.genre,
                                year = metadata.year,
                                track = metadata.trackNumber,
                                albumArtist = metadata.albumArtist
                            )

                            tracks.add(track)

                            if (tracks.size <= 10) {
                                Log.d(TAG, "Added track: $title by $artist (${duration}ms)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error processing track at cursor position ${cursor.position}", e)
                    }
                }
            } ?: run {
                Log.e(TAG, "MediaStore query returned null cursor")
            }

            Log.d(TAG, "Query completed with ${tracks.size} valid tracks")
            _scanResults.value = tracks
            tracks

        } catch (e: Exception) {
            Log.e(TAG, "Error during MediaStore query", e)
            emptyList()
        }
    }

    /**
     * Build WHERE clause following Spotify's filtering logic
     */
    private fun buildSelection(): String {
        val conditions = mutableListOf<String>()

        // Always require IS_MUSIC = 1
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
        conditions.add("${MediaStore.Audio.Media.DURATION} >= ${options.minDurationMs}")

        return conditions.joinToString(" AND ")
    }

    /**
     * Build selection arguments
     */
    private fun buildSelectionArgs(): Array<String>? {
        // For now, no dynamic args since we're using hardcoded values
        return null
    }

    /**
     * Check metadata and album art existence without extracting the art
     */
    private suspend fun checkMetadataOnly(uri: Uri): Pair<Boolean, Metadata> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        return@withContext try {
            retriever.setDataSource(context, uri)

            // Only check if embedded picture exists, don't extract it
            val hasEmbeddedArt = try {
                retriever.embeddedPicture != null
            } catch (e: Exception) {
                false
            }

            // Extract metadata
            val metadata = Metadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull(),
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull()
            )

            Pair(hasEmbeddedArt, metadata)

        } catch (e: Exception) {
            Log.w(TAG, "Error checking metadata from $uri", e)
            Pair(false, Metadata())
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }

    /**
     * Extract metadata and album art using MediaMetadataRetriever (Spotify style)
     * This is called on-demand when artwork is actually needed
     */
    suspend fun extractMetadataAndArt(uri: Uri): Pair<Boolean, Metadata> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        return@withContext try {
            retriever.setDataSource(context, uri)

            // Extract embedded picture
            val embeddedPicture = retriever.embeddedPicture
            val hasEmbeddedArt = embeddedPicture != null

            // If we have embedded art, cache it
            if (hasEmbeddedArt && embeddedPicture != null) {
                cacheEmbeddedArtwork(uri.toString(), embeddedPicture)
            }

            // Extract metadata
            val metadata = Metadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull(),
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull()
            )

            Pair(hasEmbeddedArt, metadata)

        } catch (e: Exception) {
            Log.w(TAG, "Error extracting metadata from $uri", e)
            Pair(false, Metadata())
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }

    /**
     * Cache embedded artwork with resizing
     */
    private suspend fun cacheEmbeddedArtwork(mediaUri: String, embeddedPicture: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val cacheKey = generateCacheKey(mediaUri)
            val cacheFile = File(options.cacheDir, "$cacheKey.jpg")

            // Resize and save the artwork
            val resizedBitmap = resizeBitmap(embeddedPicture)
            cacheFile.outputStream().use { out ->
                resizedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }

            resizedBitmap.recycle()

            Log.d(TAG, "Cached embedded artwork for $mediaUri")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache embedded artwork for $mediaUri", e)
        }
    }

    /**
     * Resize bitmap to optimal size for UI
     */
    private fun resizeBitmap(imageData: ByteArray): android.graphics.Bitmap {
        val originalBitmap = android.graphics.BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        val maxSize = 512

        if (originalBitmap.width <= maxSize && originalBitmap.height <= maxSize) {
            return originalBitmap
        }

        val ratio = minOf(maxSize.toFloat() / originalBitmap.width, maxSize.toFloat() / originalBitmap.height)
        val newWidth = (originalBitmap.width * ratio).toInt()
        val newHeight = (originalBitmap.height * ratio).toInt()

        return android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true).also {
            originalBitmap.recycle()
        }
    }

    /**
     * Generate cache key for artwork
     */
    private fun generateCacheKey(mediaUri: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(mediaUri.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate artwork URI for cached file
     */
    fun generateArtworkUri(mediaUri: String): String {
        val cacheKey = generateCacheKey(mediaUri)
        return "file://${options.cacheDir.absolutePath}/$cacheKey.jpg"
    }

    /**
     * Start listening for MediaStore changes
     */
    fun startListening(onChange: () -> Unit) {
        if (contentObserver != null) return

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private var lastChangeAtMs: Long = 0L
            private var pending: Boolean = false

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "MediaStore changed: $uri")
                val now = System.currentTimeMillis()
                lastChangeAtMs = now
                if (!pending) {
                    pending = true
                    scope.launch {
                        // Debounce: wait briefly to coalesce rapid bursts
                        kotlinx.coroutines.delay(400)
                        // Only fire if no newer change arrived during the delay
                        val since = System.currentTimeMillis() - lastChangeAtMs
                        if (since >= 350) {
                            onChange()
                        }
                        pending = false
                    }
                }
            }
        }

        // Register for multiple MediaStore URIs to catch all changes
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        
        // Also register for internal storage (some devices use this)
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.INTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        
        // Register for the files URI to catch file system changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                contentObserver!!
            )
        }

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

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopListening()
        Log.d(TAG, "MediaStoreReader cleaned up")
    }

    /**
     * Metadata extracted from MediaMetadataRetriever
     */
    data class Metadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val duration: Long? = null,
        val year: Int? = null,
        val trackNumber: Int? = null
    )
}
