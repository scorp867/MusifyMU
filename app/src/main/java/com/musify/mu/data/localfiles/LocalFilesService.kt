package com.musify.mu.data.localfiles

import android.content.Context
import android.util.Log
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.mediastore.MediaStoreReader
import com.musify.mu.data.proto.OpenedAudioFilesStorage
import com.musify.mu.data.proto.OpenedAudioFilesStorageImpl
import com.musify.mu.data.proto.QueryResult
import com.musify.mu.data.proto.QueryResultStorage
import com.musify.mu.data.proto.QueryResultStorageImpl
import com.musify.mu.data.proto.CustomArtworkStorage
import com.musify.mu.data.proto.CustomArtworkStorageImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spotify-style LocalFilesService that coordinates scanning and provides the API
 */
class LocalFilesService private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocalFilesService"
        private const val MIN_DURATION_MS = 1000L // 1 second minimum

        @Volatile
        private var INSTANCE: LocalFilesService? = null

        fun getInstance(context: Context): LocalFilesService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalFilesService(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Core components
    private val cacheDir = (context.getExternalFilesDir("spotify_artwork_cache") 
        ?: context.cacheDir.resolve("spotify_artwork_cache")).apply { mkdirs() }
    private val mediaStoreReader = MediaStoreReader.getInstance(
        context,
        MediaStoreReader.MediaStoreReaderOptions(
            minDurationMs = MIN_DURATION_MS,
            cacheDir = cacheDir
        )
    )

    private val openedFilesStorage: OpenedAudioFilesStorage = OpenedAudioFilesStorageImpl(context)
    private val queryResultStorage: QueryResultStorage = QueryResultStorageImpl(context)
    private val customArtworkStorage: CustomArtworkStorage = CustomArtworkStorageImpl(context)

    // State flows
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastScanTime = MutableStateFlow<Long?>(null)
    val lastScanTime: StateFlow<Long?> = _lastScanTime.asStateFlow()

    private var isInitialized = false

    /**
     * Initialize the service (call this at app startup)
     */
    suspend fun initialize() {
        if (isInitialized) return

        Log.d(TAG, "Initializing LocalFilesService")

        try {
            // Load any previously opened files
            val openedFiles = openedFilesStorage.load()
            Log.d(TAG, "Loaded ${openedFiles.permanentFiles.size} opened files")

            // Try to load cached QueryResult first (Spotify's efficient approach)
            val cachedResult = queryResultStorage.load()
            if (cachedResult != null) {
                Log.d(TAG, "Using cached QueryResult from ${System.currentTimeMillis() - cachedResult.timestamp}ms ago")
                val tracks = convertQueryResultToTracks(cachedResult)
                val tracksWithCustomArt = applyCustomArtwork(tracks)
                _tracks.value = tracksWithCustomArt
                _lastScanTime.value = cachedResult.timestamp
            } else {
                Log.d(TAG, "No valid cache found, performing initial scan")
                refreshTracks()
            }

            // Start listening for MediaStore changes
            mediaStoreReader.startListening {
                scope.launch {
                    refreshTracks()
                }
            }

            isInitialized = true
            Log.d(TAG, "LocalFilesService initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LocalFilesService", e)
        }
    }

    /**
     * Refresh tracks by running a new scan
     */
    suspend fun refreshTracks() {
        if (_isScanning.value) {
            Log.d(TAG, "Scan already in progress, skipping")
            return
        }

        _isScanning.value = true

        try {
            Log.d(TAG, "Starting track refresh")

            val startTime = System.currentTimeMillis()

            // Run the MediaStore scan
            val scannedTracks = mediaStoreReader.runQuery()

            // Merge with opened files if any
            val openedFiles = openedFilesStorage.load()
            val mergedTracks = mergeWithOpenedFiles(scannedTracks, openedFiles)

            // Apply custom artwork
            val tracksWithCustomArt = applyCustomArtwork(mergedTracks)

            // Create QueryResult for caching
            val queryResult = QueryResult(
                tracks = scannedTracks.map { track ->
                    com.musify.mu.data.proto.LocalTrack(
                        mediaId = track.mediaId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        artUri = track.artUri,
                        albumId = track.albumId,
                        dateAddedSec = track.dateAddedSec,
                        genre = track.genre,
                        year = track.year,
                        track = track.track,
                        albumArtist = track.albumArtist,
                        hasEmbeddedArt = track.artUri != null,
                        filePath = null
                    )
                },
                timestamp = System.currentTimeMillis(),
                scanDurationMs = System.currentTimeMillis() - startTime
            )

            // Cache the QueryResult (Spotify's approach)
            queryResultStorage.save(queryResult)

            // Update the state
            _tracks.value = tracksWithCustomArt
            _lastScanTime.value = System.currentTimeMillis()

            val scanDuration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Track refresh completed in ${scanDuration}ms with ${tracksWithCustomArt.size} tracks")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh tracks", e)
        } finally {
            _isScanning.value = false
        }
    }

    /**
     * Add a file to the opened files list
     */
    suspend fun addOpenedFile(uri: String) {
        openedFilesStorage.addFile(uri)
        Log.d(TAG, "Added opened file: $uri")
    }

    /**
     * Remove a file from the opened files list
     */
    suspend fun removeOpenedFile(uri: String) {
        openedFilesStorage.removeFile(uri)
        Log.d(TAG, "Removed opened file: $uri")
    }

    /**
     * Get tracks filtered by search query
     */
    fun searchTracks(query: String): List<Track> {
        if (query.isBlank()) return _tracks.value

        val lowercaseQuery = query.lowercase()
        return _tracks.value.filter { track ->
            track.title.lowercase().contains(lowercaseQuery) ||
            track.artist.lowercase().contains(lowercaseQuery) ||
            track.album.lowercase().contains(lowercaseQuery)
        }
    }

    /**
     * Get tracks by artist
     */
    fun getTracksByArtist(artist: String): List<Track> {
        return _tracks.value.filter { it.artist.equals(artist, ignoreCase = true) }
    }

    /**
     * Get tracks by album
     */
    fun getTracksByAlbum(album: String): List<Track> {
        return _tracks.value.filter { it.album.equals(album, ignoreCase = true) }
    }

    /**
     * Get unique artists
     */
    fun getArtists(): List<String> {
        return _tracks.value
            .map { it.artist }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    /**
     * Get unique albums with their track counts
     */
    fun getAlbums(): List<Triple<String, String, Int>> { // albumName, artistName, trackCount
        return _tracks.value
            .groupBy { it.albumId }
            .map { (_, tracks) ->
                val firstTrack = tracks.first()
                Triple(firstTrack.album, firstTrack.artist, tracks.size)
            }
            .sortedBy { it.first.lowercase() }
    }

    /**
     * Convert QueryResult to Track entities
     */
    private fun convertQueryResultToTracks(queryResult: QueryResult): List<Track> {
        return queryResult.tracks.map { localTrack ->
            val computedArtUri = if (localTrack.hasEmbeddedArt) mediaStoreReader.generateArtworkUri(localTrack.mediaId) else null
            val existingArtUri = computedArtUri?.let { uriStr ->
                val path = uriStr.removePrefix("file://")
                try {
                    val f = java.io.File(path)
                    if (f.exists() && f.isFile) uriStr else null
                } catch (_: Exception) { null }
            }
            Track(
                mediaId = localTrack.mediaId,
                title = localTrack.title,
                artist = localTrack.artist,
                album = localTrack.album,
                durationMs = localTrack.durationMs,
                artUri = existingArtUri,
                albumId = localTrack.albumId,
                dateAddedSec = localTrack.dateAddedSec,
                genre = localTrack.genre,
                year = localTrack.year,
                track = localTrack.track,
                albumArtist = localTrack.albumArtist
            )
        }
    }

    /**
     * Apply custom artwork to tracks
     */
    private suspend fun applyCustomArtwork(tracks: List<Track>): List<Track> {
        val customArtworkMap = customArtworkStorage.loadAll()
        return tracks.map { track ->
            val customArtUri = customArtworkMap[track.mediaId]
            if (customArtUri != null) {
                track.copy(artUri = customArtUri)
            } else {
                track
            }
        }
    }

    /**
     * Save custom artwork for a track
     */
    suspend fun saveCustomArtwork(trackUri: String, artworkUri: String) {
        customArtworkStorage.save(trackUri, artworkUri)
        Log.d(TAG, "Saved custom artwork for track: $trackUri")

        // Update the current tracks list with the new artwork
        val updatedTracks = _tracks.value.map { track ->
            if (track.mediaId == trackUri) {
                track.copy(artUri = artworkUri)
            } else {
                track
            }
        }
        _tracks.value = updatedTracks

        // Also update the cache to ensure immediate availability
        queryResultStorage.clear() // Force refresh of cached results
    }

    /**
     * Get custom artwork for a track
     */
    suspend fun getCustomArtwork(trackUri: String): String? {
        return customArtworkStorage.load(trackUri)
    }

    /**
     * Clear all caches (useful for debugging or when cache becomes corrupted)
     */
    suspend fun clearCache() {
        queryResultStorage.clear()
        customArtworkStorage.clear()
        _tracks.value = emptyList()
        _lastScanTime.value = null
        Log.d(TAG, "All caches cleared")
    }

    /**
     * Force a fresh scan (bypassing cache)
     */
    suspend fun forceRefresh() {
        Log.d(TAG, "Forcing fresh scan (clearing cache first)")
        queryResultStorage.clear()
        refreshTracks()
    }

    /**
     * Prefetch artwork for a list of tracks to reduce loading delays
     */
    suspend fun prefetchArtwork(trackUris: List<String>) {
        if (trackUris.isEmpty()) return

        withContext(Dispatchers.IO) {
            val urisToPrefetch = trackUris.take(20) // Limit to prevent overwhelming
            urisToPrefetch.forEach { trackUri ->
                // Check if we already have custom artwork
                val customArt = customArtworkStorage.load(trackUri)
                if (customArt != null) {
                    // Custom artwork already available
                    return@forEach
                }

                // Check if embedded artwork is cached
                val cachedUri = com.musify.mu.util.OnDemandArtworkLoader.getCachedUri(trackUri)
                if (cachedUri == null) {
                    // Trigger background loading
                    com.musify.mu.util.OnDemandArtworkLoader.loadArtwork(trackUri)
                }
            }
        }
    }

    /**
     * Clean up old artwork files that are no longer referenced
     */
    suspend fun cleanupOldArtwork() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting artwork cleanup")

            val currentArtworkFiles = _tracks.value
                .mapNotNull { it.artUri }
                .filter { it.startsWith("file://") }
                .map { it.removePrefix("file://").substringAfterLast("/") }
                .toSet()

            val cacheFiles = cacheDir.listFiles { file ->
                file.isFile && file.name.endsWith(".jpg")
            } ?: emptyArray()

            var deletedCount = 0
            cacheFiles.forEach { file ->
                if (file.name !in currentArtworkFiles) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }

            Log.d(TAG, "Artwork cleanup completed: deleted $deletedCount old files")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old artwork", e)
        }
    }

    /**
     * Merge scanned tracks with opened files
     */
    private suspend fun mergeWithOpenedFiles(
        scannedTracks: List<Track>,
        openedFiles: com.musify.mu.data.proto.OpenedAudioFiles
    ): List<Track> = withContext(Dispatchers.IO) {
        val mergedTracks = scannedTracks.toMutableList()

        // Add any opened files that weren't found in the scan
        for (fileUri in openedFiles.getAllFiles()) {
            if (mergedTracks.none { it.mediaId == fileUri }) {
                // Try to extract metadata from this file
                try {
                    val track = extractTrackFromUri(fileUri)
                    if (track != null) {
                        mergedTracks.add(track)
                        Log.d(TAG, "Added opened file track: ${track.title}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract metadata from opened file: $fileUri", e)
                }
            }
        }

        // Sort by date added (most recent first)
        mergedTracks.sortedByDescending { it.dateAddedSec }
    }

    /**
     * Extract track metadata from a URI (for opened files not in MediaStore)
     */
    private suspend fun extractTrackFromUri(uriString: String): Track? = withContext(Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(uriString)
            val (hasEmbeddedArt, metadata) = mediaStoreReader.extractMetadataAndArt(uri)

            Track(
                mediaId = uriString,
                title = metadata.title ?: "Unknown",
                artist = metadata.artist ?: "Unknown",
                album = metadata.album ?: "Unknown",
                durationMs = metadata.duration ?: 0,
                artUri = if (hasEmbeddedArt) {
                    mediaStoreReader.generateArtworkUri(uriString)
                } else null,
                albumId = null,
                dateAddedSec = System.currentTimeMillis() / 1000,
                genre = metadata.genre,
                year = metadata.year,
                track = metadata.trackNumber,
                albumArtist = metadata.albumArtist
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract track from URI: $uriString", e)
            null
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        mediaStoreReader.stopListening()
        scope.launch {
            cleanupOldArtwork()
        }
        Log.d(TAG, "LocalFilesService cleaned up")
    }
}
