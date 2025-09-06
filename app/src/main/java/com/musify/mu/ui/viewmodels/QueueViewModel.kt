package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.playback.QueueManager
import com.musify.mu.util.toMediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {
    
    companion object {
        private const val TAG = "QueueViewModel"
    }
    
    // Queue items
    private val _queueItems = MutableStateFlow<List<QueueManager.QueueItem>>(emptyList())
    val queueItems: StateFlow<List<QueueManager.QueueItem>> = _queueItems.asStateFlow()
    
    // Current playing index
    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()
    
    // Queue state
    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()
    
    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious: StateFlow<Boolean> = _hasPrevious.asStateFlow()
    
    // Play next count
    private val _playNextCount = MutableStateFlow(0)
    val playNextCount: StateFlow<Int> = _playNextCount.asStateFlow()
    
    // User queue count
    private val _userQueueCount = MutableStateFlow(0)
    val userQueueCount: StateFlow<Int> = _userQueueCount.asStateFlow()
    
    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // References
    private var queueManager: QueueManager? = null
    private var mediaController: MediaController? = null
    
    /**
     * Set the queue manager
     */
    fun setQueueManager(manager: QueueManager?) {
        queueManager = manager
        
        // Observe queue state
        manager?.let { qm ->
            viewModelScope.launch {
                qm.queueStateFlow.collect { state ->
                    _currentIndex.value = state.currentIndex
                    _hasNext.value = state.hasNext
                    _hasPrevious.value = state.hasPrevious
                    _playNextCount.value = state.playNextCount
                    _userQueueCount.value = state.userQueueCount
                }
            }
        }
        
        refreshQueue()
    }
    
    /**
     * Set the media controller
     */
    fun setMediaController(controller: MediaController?) {
        mediaController = controller
    }
    
    /**
     * Refresh queue from queue manager
     */
    fun refreshQueue() {
        queueManager?.let { manager ->
            val visible = manager.getVisibleQueue()
            _queueItems.value = visible
        }
    }
    
    /**
     * Move item in queue
     */
    fun moveItem(from: Int, to: Int) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                
                queueManager?.let { manager ->
                    // Convert visible indices to combined indices
                    val combinedFrom = manager.getVisibleToCombinedIndexMapping(from)
                    val combinedTo = manager.getVisibleToCombinedIndexMapping(to)
                    
                    if (combinedFrom >= 0 && combinedTo >= 0) {
                        manager.move(combinedFrom, combinedTo)
                        refreshQueue()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error moving queue item", e)
                _error.value = "Failed to move item"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Remove item from queue
     */
    fun removeItem(index: Int) {
        viewModelScope.launch {
            try {
                queueManager?.let { manager ->
                    val combinedIndex = manager.getVisibleToCombinedIndexMapping(index)
                    if (combinedIndex >= 0) {
                        manager.removeAt(combinedIndex)
                        refreshQueue()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing queue item", e)
                _error.value = "Failed to remove item"
            }
        }
    }
    
    /**
     * Clear transient queues (Play Next and User Queue)
     */
    fun clearTransientQueues() {
        viewModelScope.launch {
            try {
                queueManager?.clearTransientQueues(keepCurrent = true)
                refreshQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing transient queues", e)
                _error.value = "Failed to clear queues"
            }
        }
    }
    
    /**
     * Clear entire queue
     */
    fun clearQueue(keepCurrent: Boolean = true) {
        viewModelScope.launch {
            try {
                queueManager?.clearQueue(keepCurrent)
                refreshQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing queue", e)
                _error.value = "Failed to clear queue"
            }
        }
    }
    
    /**
     * Play item from queue
     */
    fun playFromQueue(index: Int) {
        try {
            queueManager?.let { manager ->
                val combinedIndex = manager.getVisibleToCombinedIndexMapping(index)
                if (combinedIndex >= 0) {
                    mediaController?.seekToDefaultPosition(combinedIndex)
                    mediaController?.play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing from queue", e)
            _error.value = "Failed to play track"
        }
    }
    
    /**
     * Add tracks to play next
     */
    fun addToPlayNext(tracks: List<Track>) {
        viewModelScope.launch {
            try {
                val mediaItems = tracks.map { it.toMediaItem() }.toMutableList()
                queueManager?.playNext(mediaItems)
                refreshQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Error adding to play next", e)
                _error.value = "Failed to add tracks"
            }
        }
    }
    
    /**
     * Add tracks to user queue
     */
    fun addToUserQueue(tracks: List<Track>) {
        viewModelScope.launch {
            try {
                val mediaItems = tracks.map { it.toMediaItem() }.toMutableList()
                queueManager?.addToUserQueue(mediaItems)
                refreshQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Error adding to user queue", e)
                _error.value = "Failed to add tracks"
            }
        }
    }
    
    /**
     * Get queue statistics
     */
    fun getQueueStats(): String {
        return queueManager?.let { manager ->
            val stats = manager.getQueueStatistics()
            buildString {
                append("Total: ${stats.totalItems}")
                if (stats.priorityItems > 0) {
                    append(" • Play Next: ${stats.priorityItems}")
                }
                if (stats.userQueueItems > 0) {
                    append(" • User Queue: ${stats.userQueueItems}")
                }
            }
        } ?: "No queue"
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }
}