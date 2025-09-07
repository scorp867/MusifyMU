package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.playback.QueueManager
import com.musify.mu.util.toMediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

/**
 * ViewModel for managing playback state across the application.
 * Provides a single source of truth for playback-related data.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackStateStore: PlaybackStateStore
) : ViewModel() {
    
    companion object {
        private const val TAG = "PlaybackViewModel"
    }
    
    // Current playing track
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()
    
    // Playback state
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    // Queue state
    private val _hasPlayableQueue = MutableStateFlow(false)
    val hasPlayableQueue: StateFlow<Boolean> = _hasPlayableQueue.asStateFlow()
    
    // Preview track (last played when no active queue)
    private val _previewTrack = MutableStateFlow<Track?>(null)
    val previewTrack: StateFlow<Track?> = _previewTrack.asStateFlow()
    
    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Media controller reference
    private var mediaController: MediaController? = null
    
    /**
     * Initialize the ViewModel with a MediaController
     */
    fun setMediaController(controller: MediaController?) {
        mediaController = controller
        controller?.let { initializeFromController(it) }
    }
    
    /**
     * Initialize state from the current controller state
     */
    private fun initializeFromController(controller: MediaController) {
        viewModelScope.launch {
            try {
                updateFromMediaItem(controller.currentMediaItem)
                _isPlaying.value = controller.isPlaying
                _hasPlayableQueue.value = controller.mediaItemCount > 0
                
                // If no active queue, load preview of last played track
                if (controller.mediaItemCount == 0) {
                    loadLastPlaybackState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing from controller", e)
                _error.value = "Failed to initialize playback state"
            }
        }
    }
    
    /**
     * Update current track from media item
     */
    fun updateFromMediaItem(mediaItem: MediaItem?, reason: Int = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
        viewModelScope.launch {
            try {
                if (mediaItem == null) {
                    _currentTrack.value = null
                    return@launch
                }
                
                // Try to get track from repository
                val track = libraryRepository.getTrackByMediaId(mediaItem.mediaId)
                
                if (track != null) {
                    _currentTrack.value = track
                    // Record as played if it's a new track (not repeat)
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                        libraryRepository.recordPlayed(mediaItem.mediaId)
                    }
                } else {
                    // Create a basic track from media metadata
                    _currentTrack.value = Track(
                        mediaId = mediaItem.mediaId,
                        title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown",
                        artist = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown",
                        album = mediaItem.mediaMetadata.albumTitle?.toString() ?: "",
                        durationMs = 0L,
                        artUri = mediaItem.mediaMetadata.artworkUri?.toString(),
                        albumId = null
                    )
                }
                
                // Clear preview track when we have an active track
                if (_hasPlayableQueue.value) {
                    _previewTrack.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating from media item", e)
            }
        }
    }
    
    /**
     * Update playback state
     */
    fun updatePlaybackState(isPlaying: Boolean, hasQueue: Boolean) {
        _isPlaying.value = isPlaying
        _hasPlayableQueue.value = hasQueue
        
        // Clear preview track when we have an active queue
        if (hasQueue) {
            _previewTrack.value = null
        }
    }
    
    /**
     * Play a list of tracks
     */
    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
                mediaController?.let { controller ->
                    controller.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0)
                    controller.prepare()
                    controller.play()
                    
                    // Save queue state
                    saveQueueState(tracks, startIndex)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play tracks", e)
                _error.value = "Failed to start playback"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        mediaController?.let { controller ->
            if (controller.mediaItemCount == 0 && _previewTrack.value != null) {
                // Restore last queue if no active queue
                restoreLastQueue()
            } else {
                if (controller.isPlaying) {
                    controller.pause()
                } else {
                    controller.play()
                }
            }
        }
    }
    
    /**
     * Skip to next track
     */
    fun skipToNext() {
        mediaController?.let { controller ->
            if (controller.mediaItemCount == 0 && _previewTrack.value != null) {
                restoreLastQueue()
            } else {
                controller.seekToNext()
                if (!controller.isPlaying) controller.play()
            }
        }
    }
    
    /**
     * Skip to previous track
     */
    fun skipToPrevious() {
        mediaController?.let { controller ->
            if (controller.mediaItemCount == 0 && _previewTrack.value != null) {
                restoreLastQueue()
            } else {
                controller.seekToPrevious()
                if (!controller.isPlaying) controller.play()
            }
        }
    }
    
    /**
     * Save current playback state
     */
    fun savePlaybackState() {
        viewModelScope.launch {
            try {
                mediaController?.let { controller ->
                    if (controller.mediaItemCount > 0) {
                        val mediaIds = (0 until controller.mediaItemCount).map { 
                            controller.getMediaItemAt(it).mediaId 
                        }
                        
                        playbackStateStore.save(
                            ids = mediaIds,
                            index = controller.currentMediaItemIndex,
                            posMs = controller.currentPosition,
                            repeat = controller.repeatMode,
                            shuffle = controller.shuffleModeEnabled,
                            play = controller.isPlaying
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving playback state", e)
            }
        }
    }
    
    /**
     * Load last playback state as preview
     */
    private suspend fun loadLastPlaybackState() {
        try {
            val state = playbackStateStore.load()
            if (state != null && state.mediaIds.isNotEmpty()) {
                val track = libraryRepository.getTrackByMediaId(state.mediaIds[state.index])
                _previewTrack.value = track
                _currentTrack.value = track
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading last playback state", e)
        }
    }
    
    /**
     * Restore last queue and start playing
     */
    private fun restoreLastQueue() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                
                val state = playbackStateStore.load()
                if (state != null && state.mediaIds.isNotEmpty()) {
                    val tracks = state.mediaIds.mapNotNull { mediaId ->
                        libraryRepository.getTrackByMediaId(mediaId)
                    }
                    
                    if (tracks.isNotEmpty()) {
                        mediaController?.let { controller ->
                            controller.setMediaItems(
                                tracks.map { it.toMediaItem() },
                                state.index.coerceIn(0, tracks.size - 1),
                                state.posMs
                            )
                            controller.prepare()
                            
                            // Restore playback settings
                            controller.repeatMode = state.repeat
                            controller.shuffleModeEnabled = state.shuffle
                            
                            if (state.play) {
                                controller.play()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring queue", e)
                _error.value = "Failed to restore playback"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Save queue state for a list of tracks
     */
    private suspend fun saveQueueState(tracks: List<Track>, startIndex: Int) {
        try {
            val mediaIds = tracks.map { it.mediaId }
            mediaController?.let { controller ->
                playbackStateStore.save(
                    ids = mediaIds,
                    index = startIndex,
                    posMs = 0L,
                    repeat = controller.repeatMode,
                    shuffle = controller.shuffleModeEnabled,
                    play = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving queue state", e)
        }
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        // Save state when ViewModel is cleared
        savePlaybackState()
    }
}