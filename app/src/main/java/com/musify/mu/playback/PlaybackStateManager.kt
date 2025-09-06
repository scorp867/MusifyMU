package com.musify.mu.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized manager for playback state persistence and restoration.
 * Ensures consistent state saving across the app.
 */
@Singleton
class PlaybackStateManager @Inject constructor(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val playbackStateStore: PlaybackStateStore,
    private val queueStateStore: QueueStateStore
) {
    companion object {
        private const val TAG = "PlaybackStateManager"
        private const val SAVE_DELAY_MS = 1000L // Debounce state saves
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // State flows
    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()
    
    private val _lastSavedPosition = MutableStateFlow(0L)
    val lastSavedPosition: StateFlow<Long> = _lastSavedPosition.asStateFlow()
    
    // Save job for debouncing
    private var saveJob: kotlinx.coroutines.Job? = null
    
    /**
     * Save current playback state
     */
    fun saveState(
        controller: MediaController,
        queueManager: QueueManager? = null,
        immediate: Boolean = false
    ) {
        // Cancel previous save job if not immediate
        if (!immediate) {
            saveJob?.cancel()
        }
        
        val job = scope.launch {
            if (!immediate) {
                kotlinx.coroutines.delay(SAVE_DELAY_MS)
            }
            
            try {
                val mediaItemCount = controller.mediaItemCount
                if (mediaItemCount == 0) {
                    Log.d(TAG, "No items to save")
                    return@launch
                }
                
                // Save main playback state
                val mediaIds = (0 until mediaItemCount).map { 
                    controller.getMediaItemAt(it).mediaId 
                }
                
                val currentIndex = controller.currentMediaItemIndex
                val position = controller.currentPosition
                val repeatMode = controller.repeatMode
                val shuffleEnabled = controller.shuffleModeEnabled
                val isPlaying = controller.isPlaying
                
                playbackStateStore.save(
                    ids = mediaIds,
                    index = currentIndex,
                    posMs = position,
                    repeat = repeatMode,
                    shuffle = shuffleEnabled,
                    play = isPlaying
                )
                
                _lastSavedPosition.value = position
                
                // Save queue state if queue manager is provided
                queueManager?.let { qm ->
                    val playNextItems = qm.getPlayNextQueue()
                    val userQueueItems = qm.getUserQueue()
                    val stats = qm.getQueueStatistics()
                    
                    queueStateStore.saveQueueState(
                        playNextItems = playNextItems,
                        userQueueItems = userQueueItems,
                        currentMainIndex = stats.currentIndex
                    )
                }
                
                Log.d(TAG, "Playback state saved: index=$currentIndex, pos=$position, items=${mediaIds.size}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error saving playback state", e)
            }
        }
        
        if (!immediate) {
            saveJob = job
        }
    }
    
    /**
     * Restore playback state
     */
    suspend fun restoreState(
        controller: MediaController,
        queueManager: QueueManager? = null,
        autoPlay: Boolean = false
    ): Boolean {
        _isRestoring.value = true
        
        return try {
            // Load saved state
            val state = playbackStateStore.load()
            if (state == null || state.mediaIds.isEmpty()) {
                Log.d(TAG, "No saved state to restore")
                return false
            }
            
            // Convert media IDs to tracks
            val tracks = state.mediaIds.mapNotNull { mediaId ->
                libraryRepository.getTrackByMediaId(mediaId)
            }
            
            if (tracks.isEmpty()) {
                Log.w(TAG, "No valid tracks found for saved state")
                return false
            }
            
            // Validate index
            val validIndex = state.index.coerceIn(0, tracks.size - 1)
            
            // Set media items
            controller.setMediaItems(
                tracks.map { it.toMediaItem() },
                validIndex,
                state.posMs
            )
            
            // Restore player settings
            controller.repeatMode = state.repeat
            controller.shuffleModeEnabled = state.shuffle
            
            // Prepare player
            controller.prepare()
            
            // Restore queue state if queue manager is provided
            queueManager?.let { qm ->
                val queueState = queueStateStore.loadQueueState()
                if (queueState != null) {
                    // Restore play next items
                    val playNextTracks = queueState.playNextItems.mapNotNull { mediaId ->
                        libraryRepository.getTrackByMediaId(mediaId)
                    }
                    if (playNextTracks.isNotEmpty()) {
                        qm.playNext(playNextTracks.map { it.toMediaItem() }.toMutableList())
                    }
                    
                    // Restore user queue items
                    val userQueueTracks = queueState.userQueueItems.mapNotNull { mediaId ->
                        libraryRepository.getTrackByMediaId(mediaId)
                    }
                    if (userQueueTracks.isNotEmpty()) {
                        qm.addToUserQueue(userQueueTracks.map { it.toMediaItem() }.toMutableList())
                    }
                }
            }
            
            // Start playback if requested or was playing before
            if (autoPlay || state.play) {
                controller.play()
            }
            
            Log.d(TAG, "Playback state restored: ${tracks.size} tracks, index=$validIndex, pos=${state.posMs}")
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring playback state", e)
            false
        } finally {
            _isRestoring.value = false
        }
    }
    
    /**
     * Get last played track for preview
     */
    suspend fun getLastPlayedTrack(): com.musify.mu.data.db.entities.Track? {
        return try {
            val state = playbackStateStore.load()
            if (state != null && state.mediaIds.isNotEmpty()) {
                val index = state.index.coerceIn(0, state.mediaIds.size - 1)
                libraryRepository.getTrackByMediaId(state.mediaIds[index])
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last played track", e)
            null
        }
    }
    
    /**
     * Clear saved state
     */
    suspend fun clearState() {
        try {
            playbackStateStore.clear()
            queueStateStore.clearQueueState()
            _lastSavedPosition.value = 0L
            Log.d(TAG, "Playback state cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing playback state", e)
        }
    }
    
    /**
     * Set up automatic state saving based on player events
     */
    fun setupAutoSave(controller: MediaController, queueManager: QueueManager? = null) {
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    saveState(controller, queueManager)
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveState(controller, queueManager)
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveState(controller, queueManager)
            }
            
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    saveState(controller, queueManager)
                }
            }
            
            override fun onRepeatModeChanged(repeatMode: Int) {
                saveState(controller, queueManager)
            }
            
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                saveState(controller, queueManager)
            }
        })
    }
}