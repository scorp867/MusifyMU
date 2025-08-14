package com.musify.mu.data.media

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import android.util.Log
import java.util.concurrent.TimeUnit
import androidx.annotation.WorkerThread

/**
 * Background data manager that handles initial loading and caching of all music data
 * including album art. Ensures data persistence across app sessions.
 */
class BackgroundDataManager private constructor(
    private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "BackgroundDataManager"
        private const val WORK_NAME = "background_music_scan"
        
        @Volatile
        private var INSTANCE: BackgroundDataManager? = null
        
        fun getInstance(context: Context): BackgroundDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackgroundDataManager(
                    context.applicationContext,
                    com.musify.mu.data.db.DatabaseProvider.get(context)
                ).also { INSTANCE = it }
            }
        }
    }
    
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanner by lazy { MediaStoreScanner(context, database) }
    private val artworkResolver by lazy { ArtworkResolver(context, database) }
    private val embeddedArtCache by lazy { EmbeddedArtCache }
    
    // In-memory cache of all tracks to avoid database queries
    private var cachedTracks: List<Track> = emptyList()
    private var isDataLoaded = false
    
    // Flow for real-time loading progress
    private val _loadingProgress = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingProgress: StateFlow<LoadingState> = _loadingProgress.asStateFlow()
    
    /**
     * Initialize data loading in the background
     */
    fun initializeData() {
        backgroundScope.launch {
            try {
                _loadingProgress.value = LoadingState.Loading(0f, "Checking cached data...")
                
                // First check if we have cached data
                val cachedData = database.dao().getAllTracks()
                Log.d(TAG, "Found ${cachedData.size} cached tracks")
                
                if (cachedData.isNotEmpty() && !shouldRefreshData(cachedData)) {
                    Log.d(TAG, "Using cached data: ${cachedData.size} tracks")
                    cachedTracks = cachedData
                    isDataLoaded = true
                    _loadingProgress.value = LoadingState.Completed(cachedData.size)
                    
                    // Preload artwork for recently played and favorites in background
                    preloadPriorityArtwork(cachedData)
                    return@launch
                }
                
                // Scan for new data
                _loadingProgress.value = LoadingState.Loading(0f, "Scanning music library...")
                
                // Debug: Check total audio files first
                val totalAudio = scanner.debugCountAllAudio()
                Log.d(TAG, "Debug scan found $totalAudio total audio files")
                
                scanAndLoadAllData()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing data", e)
                _loadingProgress.value = LoadingState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Get all tracks from cache (fast access)
     */
    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.Main) {
        if (cachedTracks.isNotEmpty()) {
            cachedTracks
        } else {
            // Fallback to database if cache is empty
            database.dao().getAllTracks().also { 
                cachedTracks = it
            }
        }
    }
    
    /**
     * Force refresh of all data
     */
    suspend fun forceRefresh() {
        scanAndLoadAllData()
    }
    
    private suspend fun scanAndLoadAllData() = withContext(Dispatchers.IO) {
        try {
            var scannedTracks = emptyList<Track>()
            var processedCount = 0
            
            // Use the flow-based scanner for progress updates
            scanner.scanAndCacheFlow().collect { progress ->
                when (progress) {
                    is MediaStoreScanner.ScanProgress.Started -> {
                        _loadingProgress.value = LoadingState.Loading(0f, "Starting scan...")
                    }
                    is MediaStoreScanner.ScanProgress.Progress -> {
                        processedCount = progress.scanned
                        _loadingProgress.value = LoadingState.Loading(
                            if (progress.total > 0) progress.scanned.toFloat() / progress.total.toFloat() else 0f,
                            "Scanned ${progress.scanned}/${progress.total} tracks"
                        )
                    }
                    is MediaStoreScanner.ScanProgress.Completed -> {
                        scannedTracks = progress.allTracks
                        _loadingProgress.value = LoadingState.Loading(
                            0.8f, 
                            "Resolving album artwork..."
                        )
                    }
                    is MediaStoreScanner.ScanProgress.ChunkError -> {
                        // Log chunk error but continue processing
                        Log.w(TAG, "Chunk error at offset ${progress.offset}: ${progress.error}")
                    }
                    is MediaStoreScanner.ScanProgress.NoTracksFound -> {
                        _loadingProgress.value = LoadingState.Completed(0)
                        return@collect
                    }
                    is MediaStoreScanner.ScanProgress.Error -> {
                        _loadingProgress.value = LoadingState.Error(progress.message)
                        return@collect
                    }
                }
            }
            
            // If flow-based scanning didn't find anything, try original scanning method
            if (scannedTracks.isEmpty()) {
                Log.w(TAG, "Flow-based scanning found no tracks, trying original scanning method...")
                _loadingProgress.value = LoadingState.Loading(0.5f, "Trying alternative scan method...")
                scannedTracks = scanner.scanAndCache()
                Log.d(TAG, "Original scanning method found ${scannedTracks.size} tracks")
            }
            
            // Update cache first
            cachedTracks = scannedTracks
            isDataLoaded = true
            
            if (scannedTracks.isNotEmpty()) {
                // Now load artwork for all tracks in background
                loadAllArtwork(scannedTracks)
                _loadingProgress.value = LoadingState.Completed(scannedTracks.size)
                Log.i(TAG, "Data loading completed: ${scannedTracks.size} tracks with artwork")
            } else {
                _loadingProgress.value = LoadingState.Completed(0)
                Log.w(TAG, "No tracks found after all scanning attempts")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during data scan", e)
            _loadingProgress.value = LoadingState.Error(e.message ?: "Scan failed")
        }
    }
    
    private suspend fun loadAllArtwork(tracks: List<Track>) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting artwork loading for ${tracks.size} tracks")
        
        // Process in chunks to avoid memory pressure
        val chunkSize = 50
        var processedCount = 0
        
        tracks.chunked(chunkSize).forEach { chunk ->
            try {
                // Load embedded artwork for chunk
                val audioUris = chunk.map { it.mediaId }
                embeddedArtCache.preload(context, audioUris)
                
                // Update database with artwork paths for tracks that got embedded art
                chunk.forEach { track ->
                    val cachedArt = embeddedArtCache.getFromMemory(track.mediaId)
                    if (cachedArt != null) {
                        // Save to persistent cache directory
                        val artPath = saveArtworkToPersistentCache(track.mediaId, cachedArt)
                        if (artPath != null) {
                            database.dao().updateTrackArt(track.mediaId, artPath)
                        }
                    }
                }
                
                processedCount += chunk.size
                val artworkProgress = if (tracks.isNotEmpty()) {
                    0.8f + (processedCount.toFloat() / tracks.size.toFloat()) * 0.2f
                } else 1.0f
                _loadingProgress.value = LoadingState.Loading(
                    artworkProgress,
                    "Loading artwork: $processedCount/${tracks.size}"
                )
                
                // Small delay to prevent blocking
                delay(10)
                
            } catch (e: Exception) {
                Log.w(TAG, "Error processing artwork chunk", e)
            }
        }
        
        // Resolve missing artwork using the resolver
        artworkResolver.resolveMissingArtAsync()
    }
    
    private suspend fun preloadPriorityArtwork(tracks: List<Track>) = withContext(Dispatchers.IO) {
        // Preload artwork for recently added and frequently played tracks
        val priorityTracks = tracks.sortedByDescending { it.dateAddedSec }.take(50)
        val audioUris = priorityTracks.map { it.mediaId }
        embeddedArtCache.preload(context, audioUris)
    }
    
    private suspend fun saveArtworkToPersistentCache(trackId: String, bitmap: android.graphics.Bitmap): String? {
        return try {
            val cacheDir = java.io.File(context.filesDir, "persistent_artwork")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            val fileName = "${trackId.hashCode()}.jpg"
            val file = java.io.File(cacheDir, fileName)
            
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save persistent artwork", e)
            null
        }
    }
    
    private fun shouldRefreshData(cachedData: List<Track>): Boolean {
        // Check if data is older than 24 hours or if there are too few tracks
        val lastScanTime = context.getSharedPreferences("music_cache", Context.MODE_PRIVATE)
            .getLong("last_scan_time", 0)
        val now = System.currentTimeMillis()
        val dayInMs = 24 * 60 * 60 * 1000L
        
        return (now - lastScanTime > dayInMs) || cachedData.size < 10
    }
    
    /**
     * Schedule periodic background refresh
     */
    fun schedulePeriodicRefresh() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(false)
                .build()
            
            val refreshWork = PeriodicWorkRequestBuilder<MusicScanWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    refreshWork
                )
            
            Log.d(TAG, "Periodic background refresh scheduled successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule periodic refresh - WorkManager not available: ${e.message}")
            // App can still function without periodic background refresh
        }
    }
    
    sealed class LoadingState {
        object Idle : LoadingState()
        data class Loading(val progress: Float, val message: String) : LoadingState()
        data class Completed(val totalTracks: Int) : LoadingState()
        data class Error(val message: String) : LoadingState()
    }
}

/**
 * WorkManager worker for periodic background scanning
 */
class MusicScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val dataManager = BackgroundDataManager.getInstance(applicationContext)
            dataManager.forceRefresh()
            Result.success()
        } catch (e: Exception) {
            Log.e("MusicScanWorker", "Background scan failed", e)
            Result.retry()
        }
    }
}
