package com.musify.mu.data.localfiles

import android.content.Context
import android.util.Log
import com.musify.mu.data.localfiles.mediastore.MediaStoreReader
import com.musify.mu.data.localfiles.mediastore.OpenedAudioFiles
import com.musify.mu.data.localfiles.permissions.LocalFilesPermissionInteractor
import com.musify.mu.data.localfiles.proto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Main local files service that coordinates MediaStore scanning, permission handling,
 * and data management. Based on Spotify's LocalFilesService architecture.
 * 
 * This service acts as the central hub for all local file operations and maintains
 * the in-memory index of local tracks.
 */
class LocalFilesService private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocalFilesService"
        
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
    private val mutex = Mutex()
    
    // Core components
    private lateinit var mediaStoreReader: MediaStoreReader
    private lateinit var openedAudioFiles: OpenedAudioFiles
    private lateinit var permissionInteractor: LocalFilesPermissionInteractor
    private lateinit var artworkLoader: LocalFileImageLoader
    
    // Configuration
    private val options = MediaStoreReaderOptions(
        durationMin = 30000, // 30 seconds minimum
        includeAlarms = false,
        includeRingtones = false,
        includeNotifications = false,
        includePodcasts = true,
        includeAudiobooks = true,
        enableDocumentTreeScanning = true
    )
    
    // In-memory index (acts as native delegate replacement)
    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()
    
    private val _albums = MutableStateFlow<List<LocalAlbum>>(emptyList())
    val albums: StateFlow<List<LocalAlbum>> = _albums.asStateFlow()
    
    private val _artists = MutableStateFlow<List<LocalArtist>>(emptyList())
    val artists: StateFlow<List<LocalArtist>> = _artists.asStateFlow()
    
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()
    
    private var isInitialized = false
    private var isListening = false
    
    /**
     * Initialize the service with all required components
     */
    suspend fun initialize(): Unit = mutex.withLock {
        if (isInitialized) {
            Log.d(TAG, "Service already initialized")
            return
        }
        
        try {
            Log.d(TAG, "Initializing LocalFilesService...")
            
            // Initialize components
            permissionInteractor = LocalFilesPermissionInteractor.getInstance(context)
            openedAudioFiles = OpenedAudioFiles.getInstance(context)
            mediaStoreReader = MediaStoreReader.getInstance(context, options)
            artworkLoader = LocalFileImageLoader.getInstance(context)
            
            // Initialize sub-components
            openedAudioFiles.initialize()
            artworkLoader.initialize()
            
            // Check permissions and start initial scan if available
            if (permissionInteractor.canScanLocalFiles()) {
                performInitialScan()
                startListening()
            } else {
                Log.w(TAG, "Cannot scan local files - missing permissions")
                _scanState.value = ScanState.PermissionRequired
            }
            
            isInitialized = true
            Log.d(TAG, "LocalFilesService initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LocalFilesService", e)
            _scanState.value = ScanState.Error("Initialization failed: ${e.message}")
        }
    }
    
    /**
     * Perform initial scan of local files
     */
    private suspend fun performInitialScan() {
        try {
            _scanState.value = ScanState.Scanning("Scanning local music library...")

            Log.d(TAG, "Starting initial scan...")
            val queryResult = withContext(Dispatchers.IO) {
                // Skip artwork extraction for fast initial scan
                mediaStoreReader.runQuery(openedAudioFiles, skipArtworkExtraction = true)
            }

            // Process results into UI models
            updateInMemoryIndex(queryResult)

            _scanState.value = ScanState.Completed(queryResult.totalCount)
            Log.d(TAG, "Initial scan completed with ${queryResult.totalCount} files")

        } catch (e: Exception) {
            Log.e(TAG, "Error during initial scan", e)
            _scanState.value = ScanState.Error("Scan failed: ${e.message}")
        }
    }
    
    /**
     * Start listening for MediaStore changes
     */
    private suspend fun startListening() {
        if (isListening) return
        
        mediaStoreReader.startListening { handle ->
            Log.d(TAG, "MediaStore changed: $handle")
            scope.launch {
                performIncrementalScan(handle)
            }
        }
        
        isListening = true
        Log.d(TAG, "Started listening for MediaStore changes")
    }
    
    /**
     * Perform incremental scan when MediaStore changes
     */
    private suspend fun performIncrementalScan(handle: String) = mutex.withLock {
        try {
            Log.d(TAG, "Performing incremental scan due to change: $handle")

            val queryResult = withContext(Dispatchers.IO) {
                // Skip artwork extraction for faster incremental scans
                mediaStoreReader.runQuery(openedAudioFiles, skipArtworkExtraction = true)
            }
            updateInMemoryIndex(queryResult)

            Log.d(TAG, "Incremental scan completed with ${queryResult.totalCount} files")

        } catch (e: Exception) {
            Log.e(TAG, "Error during incremental scan", e)
        }
    }
    
    /**
     * Update the in-memory index with new query results
     * This replaces Spotify's native delegate functionality
     */
    private suspend fun updateInMemoryIndex(queryResult: QueryResult) = withContext(Dispatchers.Default) {
        val tracks = queryResult.localFiles.map { localFile ->
            LocalTrack(
                id = localFile.path,
                title = localFile.metadata.title ?: "Unknown",
                artist = localFile.metadata.artist ?: "Unknown",
                album = localFile.metadata.album ?: "Unknown",
                albumArtist = localFile.metadata.albumArtist,
                duration = localFile.metadata.duration ?: 0,
                year = localFile.metadata.year,
                trackNumber = localFile.metadata.trackNumber,
                genre = localFile.metadata.genre,
                albumId = localFile.metadata.albumId,
                dateAdded = localFile.metadata.dateAdded ?: 0,
                hasEmbeddedArtwork = localFile.imageState == ImageState.HAS_IMAGE
            )
        }.sortedWith(
            compareByDescending<LocalTrack> { it.dateAdded }
                .thenBy { it.artist.lowercase() }
                .thenBy { it.album.lowercase() }
                .thenBy { it.trackNumber ?: 0 }
        )
        
        val albums = tracks
            .filter { it.albumId != null }
            .groupBy { it.albumId }
            .map { (albumId, albumTracks) ->
                val firstTrack = albumTracks.first()
                LocalAlbum(
                    id = albumId!!,
                    name = firstTrack.album,
                    artist = firstTrack.albumArtist ?: firstTrack.artist,
                    trackCount = albumTracks.size,
                    year = albumTracks.mapNotNull { it.year }.minOrNull()
                )
            }
            .sortedBy { it.name.lowercase() }
        
        val artists = tracks
            .groupBy { it.artist }
            .map { (artistName, artistTracks) ->
                LocalArtist(
                    name = artistName,
                    albumCount = artistTracks.mapNotNull { it.albumId }.distinct().size,
                    trackCount = artistTracks.size
                )
            }
            .sortedBy { it.name.lowercase() }
        
        // Update flows
        _tracks.value = tracks
        _albums.value = albums
        _artists.value = artists
        
        Log.d(TAG, "Updated in-memory index: ${tracks.size} tracks, ${albums.size} albums, ${artists.size} artists")
    }
    
    /**
     * API methods for accessing data (LocalFilesEndpoint equivalent)
     */
    
    /**
     * Get all tracks
     */
    fun getTracks(): LocalTracksResponse {
        return LocalTracksResponse(
            tracks = _tracks.value,
            albums = _albums.value,
            artists = _artists.value,
            totalTracks = _tracks.value.size
        )
    }
    
    /**
     * Get tracks with search filter
     */
    fun searchTracks(query: String): List<LocalTrack> {
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
     * Get tracks by album
     */
    fun getTracksByAlbum(albumId: Long): List<LocalTrack> {
        return _tracks.value.filter { it.albumId == albumId }
    }
    
    /**
     * Get tracks by artist
     */
    fun getTracksByArtist(artistName: String): List<LocalTrack> {
        return _tracks.value.filter { 
            it.artist.equals(artistName, ignoreCase = true) ||
            it.albumArtist?.equals(artistName, ignoreCase = true) == true
        }
    }
    
    /**
     * Add permanent file URI (e.g., from SAF)
     */
    suspend fun addPermanentFile(uri: android.net.Uri) {
        openedAudioFiles.addPermanentFile(uri)
        // Trigger a rescan to include the new file
        scope.launch {
            performIncrementalScan("manual_add")
        }
    }
    
    /**
     * Add temporary file URI (session only)
     */
    fun addTemporaryFile(uri: android.net.Uri) {
        openedAudioFiles.addTemporaryFile(uri)
        // Trigger a rescan to include the new file
        scope.launch {
            performIncrementalScan("temp_add")
        }
    }
    
    /**
     * Force refresh the entire library
     */
    suspend fun forceRefresh() {
        if (!isInitialized) return
        
        scope.launch {
            performInitialScan()
        }
    }
    
    /**
     * Handle permission changes
     */
    suspend fun onPermissionsChanged() {
        permissionInteractor.updatePermissionState()
        
        if (permissionInteractor.canScanLocalFiles()) {
            if (!isInitialized) {
                initialize()
            } else {
                performInitialScan()
                if (!isListening) {
                    startListening()
                }
            }
        } else {
            _scanState.value = ScanState.PermissionRequired
        }
    }
    
    /**
     * Get artwork for a track
     */
    suspend fun getArtwork(trackUri: String): String? {
        return artworkLoader.loadArtwork(trackUri)
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        mediaStoreReader.stopListening()
        isListening = false
        Log.d(TAG, "LocalFilesService cleaned up")
    }
}

/**
 * Represents the current scanning state
 */
sealed class ScanState {
    object Idle : ScanState()
    data class Scanning(val message: String) : ScanState()
    data class Completed(val trackCount: Int) : ScanState()
    data class Error(val message: String) : ScanState()
    object PermissionRequired : ScanState()
}

/**
 * Placeholder for artwork loading functionality
 * This would be implemented similar to the old OnDemandArtworkLoader
 * but following Spotify's ID3/APIC approach
 */
class LocalFileImageLoader private constructor(
    private val context: Context
) {
    companion object {
        @Volatile
        private var INSTANCE: LocalFileImageLoader? = null
        
        fun getInstance(context: Context): LocalFileImageLoader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalFileImageLoader(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    suspend fun initialize() {
        // Initialize artwork loading system
    }
    
    suspend fun loadArtwork(trackUri: String): String? {
        // TODO: Implement ID3/APIC artwork extraction
        return null
    }
}