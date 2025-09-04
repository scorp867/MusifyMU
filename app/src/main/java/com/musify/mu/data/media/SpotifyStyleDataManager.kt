package com.musify.mu.data.media

import android.content.Context
import android.util.Log
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.localfiles.LocalFilesService
import com.musify.mu.data.localfiles.ScanState
import com.musify.mu.data.localfiles.proto.LocalTrack
import com.musify.mu.data.localfiles.proto.LocalAlbum
import com.musify.mu.data.localfiles.proto.LocalArtist
import com.musify.mu.util.SpotifyStyleArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Spotify-style data manager that coordinates with LocalFilesService
 * and provides a bridge between the new architecture and existing UI code.
 * 
 * This replaces SimpleBackgroundDataManager with the new Spotify-based approach.
 */
class SpotifyStyleDataManager private constructor(
    private val context: Context,
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "SpotifyDataManager"
        
        @Volatile
        private var INSTANCE: SpotifyStyleDataManager? = null
        
        fun getInstance(context: Context, db: AppDatabase): SpotifyStyleDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpotifyStyleDataManager(context, db).also { INSTANCE = it }
            }
        }
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localFilesService = LocalFilesService.getInstance(context)
    
    // Exposed state flows for UI
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()
    
    private var isInitialized = false
    
    init {
        // Initialize artwork loader
        SpotifyStyleArtworkLoader.initialize(context)
        
        // Observe LocalFilesService state and map to our format
        scope.launch {
            combine(
                localFilesService.tracks,
                localFilesService.scanState
            ) { localTracks, scanState ->
                Pair(localTracks, scanState)
            }.collect { (localTracks, scanState) ->
                // Map scan state
                _loadingState.value = mapScanState(scanState)
                
                // Map local tracks to Track entities
                val tracks = localTracks.map { localTrack ->
                    Track(
                        mediaId = localTrack.id,
                        title = localTrack.title,
                        artist = localTrack.artist,
                        album = localTrack.album,
                        durationMs = localTrack.duration,
                        artUri = null, // Artwork handled by SpotifyStyleArtworkLoader
                        albumId = localTrack.albumId,
                        dateAddedSec = localTrack.dateAdded / 1000, // Convert to seconds
                        genre = localTrack.genre,
                        year = localTrack.year,
                        track = localTrack.trackNumber,
                        albumArtist = localTrack.albumArtist,
                        hasEmbeddedArtwork = localTrack.hasEmbeddedArtwork // Pass through the artwork flag
                    )
                }
                
                _tracks.value = tracks
                
                // Prefetch artwork for visible tracks
                if (tracks.isNotEmpty()) {
                    prefetchArtworkForTracks(tracks.take(20)) // Prefetch first 20 tracks
                }
                
                // Cache to database for offline access
                if (tracks.isNotEmpty() && scanState is ScanState.Completed) {
                    scope.launch {
                        try {
                            db.dao().upsertTracks(tracks)
                            Log.d(TAG, "Cached ${tracks.size} tracks to database")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error caching tracks to database", e)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Initialize the data manager - delegates to LocalFilesService
     */
    suspend fun initializeOnAppLaunch() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        
        try {
            Log.d(TAG, "Initializing Spotify-style data manager...")
            
            // Initialize the LocalFilesService
            localFilesService.initialize()
            
            isInitialized = true
            Log.d(TAG, "Data manager initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing data manager", e)
            _loadingState.value = LoadingState.Error("Initialization failed: ${e.message}")
        }
    }
    
    /**
     * Ensure initialization (for UI calls)
     */
    suspend fun ensureInitialized() {
        if (!isInitialized) {
            initializeOnAppLaunch()
        }
    }
    
    /**
     * Get all tracks from in-memory cache
     */
    fun getAllTracks(): List<Track> {
        return _tracks.value
    }
    
    /**
     * Get tracks by album
     */
    fun getTracksByAlbum(albumId: Long?): List<Track> {
        return _tracks.value.filter { it.albumId == albumId }
    }
    
    /**
     * Search tracks
     */
    fun searchTracks(query: String): List<Track> {
        if (query.isBlank()) return _tracks.value
        
        val lowercaseQuery = query.lowercase()
        return _tracks.value.filter { track ->
            track.title.lowercase().contains(lowercaseQuery) ||
            track.artist.lowercase().contains(lowercaseQuery) ||
            track.album.lowercase().contains(lowercaseQuery) ||
            track.genre?.lowercase()?.contains(lowercaseQuery) == true
        }
    }
    
    /**
     * Get unique albums
     */
    fun getUniqueAlbums(): List<AlbumInfo> {
        return _tracks.value
            .filter { it.albumId != null }
            .groupBy { it.albumId }
            .map { (albumId, tracks) ->
                val firstTrack = tracks.first()
                AlbumInfo(
                    albumId = albumId!!,
                    albumName = firstTrack.album,
                    artistName = firstTrack.albumArtist ?: firstTrack.artist,
                    trackCount = tracks.size,
                    artUri = null // Artwork handled by SpotifyStyleArtworkLoader
                )
            }
            .sortedBy { it.albumName.lowercase() }
    }
    
    /**
     * Get unique artists
     */
    fun getUniqueArtists(): List<ArtistInfo> {
        return _tracks.value
            .groupBy { it.artist }
            .map { (artistName, tracks) ->
                ArtistInfo(
                    name = artistName,
                    albumCount = tracks.mapNotNull { it.albumId }.distinct().size,
                    trackCount = tracks.size
                )
            }
            .sortedBy { it.name.lowercase() }
    }
    
    /**
     * Force refresh the library
     */
    fun forceRefresh() {
        scope.launch {
            localFilesService.forceRefresh()
        }
    }
    
    /**
     * Handle permission changes
     */
    suspend fun onPermissionsChanged() {
        localFilesService.onPermissionsChanged()
    }
    
    /**
     * Add a permanent file (e.g., from SAF picker)
     */
    suspend fun addPermanentFile(uri: android.net.Uri) {
        localFilesService.addPermanentFile(uri)
    }
    
    /**
     * Clear all caches
     */
    fun clearCache() {
        SpotifyStyleArtworkLoader.clearCaches()
        scope.launch {
            try {
                db.dao().deleteAllTracks()
                Log.d(TAG, "Cleared database cache")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing database cache", e)
            }
        }
    }
    
    /**
     * Get artwork for a specific track
     */
    suspend fun getArtworkForTrack(trackUri: String): String? {
        val hasEmbeddedArt = hasEmbeddedArtwork(trackUri)
        return SpotifyStyleArtworkLoader.loadArtwork(trackUri, hasEmbeddedArt)
    }
    
    /**
     * Prefetch artwork for a list of tracks (internal use)
     */
    private fun prefetchArtworkForTracks(tracks: List<Track>) {
        val trackUris = tracks.map { it.mediaId }
        scope.launch {
            prefetchArtwork(trackUris)
        }
    }
    
    /**
     * Map LocalFilesService scan state to our loading state
     */
    private fun mapScanState(scanState: ScanState): LoadingState {
        return when (scanState) {
            is ScanState.Idle -> LoadingState.Idle
            is ScanState.Scanning -> LoadingState.Loading(scanState.message)
            is ScanState.Completed -> LoadingState.Completed(scanState.trackCount)
            is ScanState.Error -> LoadingState.Error(scanState.message)
            is ScanState.PermissionRequired -> LoadingState.Error("Permission required to scan local files")
        }
    }
    
    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): CacheStats {
        val artworkStats = SpotifyStyleArtworkLoader.getCacheStats()
        return CacheStats(
            totalTracks = _tracks.value.size,
            artworkCacheSize = artworkStats.memoryCacheSize,
            artworkCacheMaxSize = artworkStats.memoryCacheMaxSize,
            failedArtworkExtractions = artworkStats.failedExtractions,
            diskCacheFiles = artworkStats.diskCacheFiles
        )
    }
    
    /**
     * Prefetch artwork for a list of track URIs
     */
    suspend fun prefetchArtwork(trackUris: List<String>) {
        Log.d(TAG, "Prefetching artwork for ${trackUris.size} tracks")
        val currentTracks = _tracks.value
        trackUris.forEach { uri ->
            scope.launch {
                // Get hasEmbeddedArtwork flag for optimization
                val track = currentTracks.find { it.mediaId == uri }
                val hasEmbeddedArt = track?.hasEmbeddedArtwork
                SpotifyStyleArtworkLoader.loadArtwork(uri, hasEmbeddedArt)
            }
        }
    }
    
    /**
     * Get whether a track has embedded artwork
     */
    fun hasEmbeddedArtwork(trackUri: String): Boolean? {
        return _tracks.value.find { it.mediaId == trackUri }?.hasEmbeddedArtwork
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        localFilesService.cleanup()
        SpotifyStyleArtworkLoader.clearCaches()
        Log.d(TAG, "SpotifyStyleDataManager cleaned up")
    }
}

/**
 * Album information for UI
 */
data class AlbumInfo(
    val albumId: Long,
    val albumName: String,
    val artistName: String,
    val trackCount: Int,
    val artUri: String? = null // Not used with new artwork system
)

/**
 * Artist information for UI
 */
data class ArtistInfo(
    val name: String,
    val albumCount: Int,
    val trackCount: Int
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

/**
 * Cache statistics for debugging
 */
data class CacheStats(
    val totalTracks: Int,
    val artworkCacheSize: Int,
    val artworkCacheMaxSize: Int,
    val failedArtworkExtractions: Int,
    val diskCacheFiles: Int
)
