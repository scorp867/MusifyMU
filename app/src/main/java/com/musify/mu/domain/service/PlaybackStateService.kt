package com.musify.mu.domain.service

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.musify.mu.domain.usecase.PlaybackUseCase
import com.musify.mu.domain.usecase.StateManagementUseCase
import com.musify.mu.domain.usecase.AppState
import com.musify.mu.playback.QueueManager
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for managing playback state persistence and restoration.
 * Ensures users can continue listening where they left off across app restarts.
 */
@Singleton
class PlaybackStateService @Inject constructor(
    private val context: Context,
    private val playbackUseCase: PlaybackUseCase,
    private val stateManagementUseCase: StateManagementUseCase,
    private val queueManager: QueueManager,
    private val player: ExoPlayer
) : DefaultLifecycleObserver {
    
    companion object {
        private const val TAG = "PlaybackStateService"
        private const val AUTO_SAVE_INTERVAL_MS = 30000L // 30 seconds
        private const val POSITION_SAVE_THRESHOLD_MS = 5000L // 5 seconds
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // State tracking
    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()
    
    private var lastSavedPosition = 0L
    private var lastSavedIndex = -1
    private var autoSaveJob: Job? = null
    private var isInitialized = false
    
    /**
     * Initialize the service and set up automatic state saving
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "PlaybackStateService already initialized")
            return
        }
        
        try {
            Log.d(TAG, "Initializing PlaybackStateService...")
            
            // Register lifecycle observer
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            
            // Start automatic state saving
            startAutoSave()
            
            isInitialized = true
            Log.d(TAG, "PlaybackStateService initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PlaybackStateService", e)
        }
    }
    
    /**
     * Save current playback state
     */
    suspend fun saveCurrentState() {
        try {
            val currentMediaItems = mutableListOf<String>()
            for (i in 0 until player.mediaItemCount) {
                currentMediaItems.add(player.getMediaItemAt(i).mediaId)
            }
            
            if (currentMediaItems.isEmpty()) {
                Log.d(TAG, "No media items to save")
                return
            }
            
            val currentIndex = player.currentMediaItemIndex
            val currentPosition = player.currentPosition
            val repeatMode = player.repeatMode
            val shuffleEnabled = player.shuffleModeEnabled
            val isPlaying = player.isPlaying
            
            // Save main playback state
            playbackUseCase.savePlaybackState(
                mediaIds = currentMediaItems,
                currentIndex = currentIndex,
                positionMs = currentPosition,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                isPlaying = isPlaying
            )
            
            // Save queue state
            val playNextItems = queueManager.getPlayNextQueue()
            val userQueueItems = queueManager.getUserQueue()
            
            playbackUseCase.saveQueueState(
                playNextItems = playNextItems,
                userQueueItems = userQueueItems,
                currentMainIndex = currentIndex
            )
            
            // Save app state
            val appState = AppState(
                lastPlayedTrack = currentMediaItems.getOrNull(currentIndex),
                lastPlayedPosition = currentPosition,
                lastPlayedTimestamp = System.currentTimeMillis(),
                shuffleMode = shuffleEnabled,
                repeatMode = repeatMode,
                volumeLevel = player.volume
            )
            
            stateManagementUseCase.saveAppState(appState)
            
            lastSavedPosition = currentPosition
            lastSavedIndex = currentIndex
            
            Log.d(TAG, "Saved playback state: ${currentMediaItems.size} items, index=$currentIndex, position=${currentPosition}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save current state", e)
        }
    }
    
    /**
     * Restore playback state on app launch
     */
    suspend fun restoreState(): Boolean {
        return try {
            _isRestoring.value = true
            
            val shouldRestore = stateManagementUseCase.shouldRestoreState()
            if (!shouldRestore) {
                Log.d(TAG, "State restoration not needed")
                _isRestoring.value = false
                return false
            }
            
            Log.d(TAG, "Restoring playback state...")
            
            val appState = stateManagementUseCase.loadAppState()
            val playbackState = playbackUseCase.loadPlaybackState()
            val queueState = playbackUseCase.loadQueueState()
            
            if (playbackState == null || playbackState.mediaIds.isEmpty()) {
                Log.d(TAG, "No playback state to restore")
                _isRestoring.value = false
                return false
            }
            
            // Restore main queue
            val mediaItems = playbackState.mediaIds.mapNotNull { mediaId ->
                // Convert mediaId to MediaItem (this would need to be implemented)
                createMediaItemFromId(mediaId)
            }.toMutableList()
            
            if (mediaItems.isEmpty()) {
                Log.d(TAG, "No valid media items found for restoration")
                _isRestoring.value = false
                return false
            }
            
            // Set up the queue
            queueManager.setQueue(
                items = mediaItems,
                startIndex = playbackState.index,
                play = false, // Don't auto-play on restore
                startPosMs = playbackState.posMs
            )
            
            // Restore player settings
            player.repeatMode = playbackState.repeat
            player.shuffleModeEnabled = playbackState.shuffle
            if (appState.volumeLevel > 0) {
                player.volume = appState.volumeLevel
            }
            
            // Restore queue state after a delay to let the main queue initialize
            scope.launch {
                delay(1000)
                
                queueState?.let { qs ->
                    // Restore play-next items
                    if (qs.playNextItems.isNotEmpty()) {
                        val playNextMediaItems = qs.playNextItems.mapNotNull { mediaId ->
                            createMediaItemFromId(mediaId)
                        }.toMutableList()
                        
                        if (playNextMediaItems.isNotEmpty()) {
                            queueManager.playNext(playNextMediaItems)
                        }
                    }
                    
                    // Restore user queue items
                    if (qs.userQueueItems.isNotEmpty()) {
                        val userQueueMediaItems = qs.userQueueItems.mapNotNull { mediaId ->
                            createMediaItemFromId(mediaId)
                        }.toMutableList()
                        
                        if (userQueueMediaItems.isNotEmpty()) {
                            queueManager.addToUserQueue(userQueueMediaItems)
                        }
                    }
                }
            }
            
            Log.d(TAG, "Successfully restored playback state: ${mediaItems.size} items, position=${playbackState.posMs}ms")
            _isRestoring.value = false
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore state", e)
            _isRestoring.value = false
            false
        }
    }
    
    /**
     * Clear all saved state
     */
    suspend fun clearSavedState() {
        try {
            Log.d(TAG, "Clearing all saved state...")
            stateManagementUseCase.clearAppState()
            Log.d(TAG, "Saved state cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear saved state", e)
        }
    }
    
    /**
     * Check if state should be saved (to avoid excessive saves)
     */
    private fun shouldSaveState(): Boolean {
        val currentPosition = player.currentPosition
        val currentIndex = player.currentMediaItemIndex
        
        // Save if position changed significantly or index changed
        return (kotlin.math.abs(currentPosition - lastSavedPosition) > POSITION_SAVE_THRESHOLD_MS) ||
               (currentIndex != lastSavedIndex) ||
               (player.mediaItemCount > 0 && lastSavedIndex == -1)
    }
    
    /**
     * Start automatic state saving
     */
    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            while (true) {
                try {
                    delay(AUTO_SAVE_INTERVAL_MS)
                    if (shouldSaveState()) {
                        saveCurrentState()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in auto-save loop", e)
                }
            }
        }
    }
    
    /**
     * Stop automatic state saving
     */
    private fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }
    
    // Lifecycle callbacks
    
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // Save state when app goes to background
        scope.launch {
            saveCurrentState()
        }
    }
    
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        cleanup()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
            stopAutoSave()
            Log.d(TAG, "PlaybackStateService cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    // Helper method - this would need to be implemented based on your MediaItem creation logic
    private fun createMediaItemFromId(mediaId: String): androidx.media3.common.MediaItem? {
        return try {
            androidx.media3.common.MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(mediaId)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create MediaItem for: $mediaId", e)
            null
        }
    }
}