package com.musify.mu.data.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.musify.mu.data.localfiles.LocalFilesService

/**
 * Efficient data manager that caches tracks in memory and only queries MediaStore once at launch.
 * Uses content observer for real-time updates without constant database queries.
 */
class SimpleBackgroundDataManager private constructor(
    private val context: Context,
    private val db: AppDatabase
) {

    companion object {
        private const val TAG = "SimpleBackgroundDataManager"

        @Volatile
        private var INSTANCE: SimpleBackgroundDataManager? = null

        fun get(context: Context, db: AppDatabase): SimpleBackgroundDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SimpleBackgroundDataManager(context, db).also { INSTANCE = it }
            }
        }
    }

    // In-memory cache - this is the single source of truth
    private val _cachedTracks = MutableStateFlow<List<Track>>(emptyList())
    val cachedTracks: StateFlow<List<Track>> = _cachedTracks.asStateFlow()

    // Loading state
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    // Spotify-style local files service
    val localFilesService = LocalFilesService.getInstance(context)

    // Track if we've already initialized
    private var isInitialized = false
    private var collectionStarted = false

    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob())

    /**
     * Initialize data loading ONCE at app launch
     * This is the ONLY time we query MediaStore (unless cache is invalid)
     */
    suspend fun initializeOnAppLaunch() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, using cached data")
            return
        }

        try {
            _loadingState.value = LoadingState.Loading("Loading music library...")

            // Initialize the Spotify-style local files service
            localFilesService.initialize()

            // Set initial snapshot without waiting on a long-lived collect
            val initial = localFilesService.tracks.value
            _cachedTracks.value = initial
            _loadingState.value = LoadingState.Completed(initial.size)

            // Start long-lived collection in manager scope (not tied to composition)
            if (!collectionStarted) {
                collectionStarted = true
                scope.launch {
                    localFilesService.tracks.collect { tracks ->
                        val previous = _cachedTracks.value
                        val changed = (previous.size != tracks.size) || (previous.firstOrNull()?.mediaId != tracks.firstOrNull()?.mediaId)

                        _cachedTracks.value = tracks
                        Log.d(TAG, "Received ${tracks.size} tracks from LocalFilesService")

                        if (tracks.isNotEmpty()) {
                            if (changed) {
                                // Cache to database only if set meaningfully changed
                                Log.d(TAG, "Caching ${tracks.size} tracks to database (changed=true)...")
                                db.dao().upsertTracks(tracks)
                            } else {
                                Log.d(TAG, "Skipping DB upsert (no meaningful change)")
                            }

                            _loadingState.value = LoadingState.Completed(tracks.size)
                        } else {
                            _loadingState.value = LoadingState.Completed(0)
                            Log.w(TAG, "No tracks found during initialization")
                        }
                    }
                }
            }

            // LocalFilesService handles its own content observer for real-time updates

            isInitialized = true
            Log.d(TAG, "Data manager initialized successfully")

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Expected if caller scope is cancelled (e.g., composition left); do not treat as error
            Log.d(TAG, "Initialization coroutine cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during app launch initialization", e)
            _loadingState.value = LoadingState.Error(e.message ?: "Initialization failed")
        }
    }

    /**
     * Ensure data manager is initialized - this is called by the UI when needed
     */
    suspend fun ensureInitialized() {
        if (!isInitialized) {
            Log.d(TAG, "Data manager not initialized, initializing now...")
            initializeOnAppLaunch()
        }
    }

    /**
     * Get all tracks from memory cache - NO database query
     */
    fun getAllTracks(): List<Track> {
        val tracks = _cachedTracks.value
        Log.d(TAG, "getAllTracks called, returning ${tracks.size} tracks")
        return tracks
    }

    /**
     * Get tracks by album from memory cache - NO database query
     */
    fun getTracksByAlbum(albumId: Long?): List<Track> {
        return _cachedTracks.value.filter { it.albumId == albumId }
    }

    /**
     * Search tracks in memory cache - NO database query
     */
    fun searchTracks(query: String): List<Track> {
        if (query.isBlank()) return _cachedTracks.value

        val lowercaseQuery = query.lowercase()
        return _cachedTracks.value.filter { track ->
            track.title.lowercase().contains(lowercaseQuery) ||
                    track.artist.lowercase().contains(lowercaseQuery) ||
                    track.album.lowercase().contains(lowercaseQuery)
        }
    }

    /**
     * Get unique albums from memory cache - NO database query
     */
    fun getUniqueAlbums(): List<AlbumInfo> {
        return _cachedTracks.value
            .groupBy { it.albumId }
            .map { (albumId, tracks) ->
                val firstTrack = tracks.first()
                AlbumInfo(
                    albumId = albumId ?: 0L,
                    albumName = firstTrack.album,
                    artistName = firstTrack.artist,
                    trackCount = tracks.size,
                    // Use first track's art URI (extracted during initialization)
                    artUri = firstTrack.artUri
                )
            }
            .sortedBy { it.albumName.lowercase() }
    }





    /**
     * Force refresh (for manual refresh button if needed)
     */
    fun forceRefresh() {
        if (!isInitialized) {
            Log.w(TAG, "Cannot force refresh before initialization")
            return
        }

        scope.launch {
            localFilesService.forceRefresh()
        }
    }

    /**
     * Clear cache (for testing or memory management)
     */
    fun clearCache() {
        scope.launch {
            localFilesService.clearCache()
        }
        _cachedTracks.value = emptyList()
        Log.d(TAG, "Memory cache cleared")
    }



    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.launch {
            localFilesService.cleanup()
        }
        Log.d(TAG, "Data manager cleaned up")
    }


}

/**
 * Album information for efficient display
 */
data class AlbumInfo(
    val albumId: Long,
    val albumName: String,
    val artistName: String,
    val trackCount: Int,
    val artUri: String?
)

/**
 * Loading states for UI
 */
sealed class LoadingState {
    object Idle : LoadingState()
    data class Loading(val message: String) : LoadingState()
    data class Completed(val trackCount: Int) : LoadingState()
    data class Error(val message: String) : LoadingState()
}

