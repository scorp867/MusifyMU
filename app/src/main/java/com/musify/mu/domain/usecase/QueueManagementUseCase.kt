package com.musify.mu.domain.usecase

import androidx.media3.common.MediaItem
import com.musify.mu.playback.QueueManager
import com.musify.mu.data.db.entities.Track
import com.musify.mu.domain.service.PlaybackStateService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for queue management operations.
 * Provides a clean interface for queue operations while ensuring proper state persistence.
 */
@Singleton
class QueueManagementUseCase @Inject constructor(
    private val queueManager: QueueManager,
    private val playbackStateService: PlaybackStateService
) {
    
    /**
     * Get queue state as a flow
     */
    fun getQueueState(): StateFlow<QueueManager.QueueState> {
        return queueManager.queueStateFlow
    }
    
    /**
     * Set a new queue with proper state management
     */
    suspend fun setQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        play: Boolean = true,
        startPositionMs: Long = 0L,
        context: QueueManager.PlayContext? = null
    ) {
        try {
            val mediaItems = tracks.map { it.toMediaItem() }.toMutableList()
            
            queueManager.setQueue(
                items = mediaItems,
                startIndex = startIndex,
                play = play,
                startPosMs = startPositionMs,
                context = context
            )
            
            // Save state after setting queue
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to set queue", e)
            throw e
        }
    }
    
    /**
     * Add tracks to play next (priority queue)
     */
    suspend fun playNext(
        tracks: List<Track>,
        context: QueueManager.PlayContext? = null
    ) {
        try {
            val mediaItems = tracks.map { it.toMediaItem() }.toMutableList()
            
            queueManager.playNext(
                items = mediaItems,
                context = context
            )
            
            // Save state after modifying queue
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to add to play next", e)
            throw e
        }
    }
    
    /**
     * Add tracks to user queue (add to next segment)
     */
    suspend fun addToUserQueue(
        tracks: List<Track>,
        context: QueueManager.PlayContext? = null,
        allowDuplicates: Boolean = true
    ) {
        try {
            val mediaItems = tracks.map { it.toMediaItem() }.toMutableList()
            
            queueManager.addToUserQueue(
                items = mediaItems,
                context = context,
                allowDuplicates = allowDuplicates
            )
            
            // Save state after modifying queue
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to add to user queue", e)
            throw e
        }
    }
    
    /**
     * Move item in queue
     */
    suspend fun moveItem(fromIndex: Int, toIndex: Int) {
        try {
            queueManager.move(fromIndex, toIndex)
            
            // Save state after reordering
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to move queue item", e)
            throw e
        }
    }
    
    /**
     * Remove item from queue
     */
    suspend fun removeItem(index: Int) {
        try {
            queueManager.removeAt(index)
            
            // Save state after removing item
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to remove queue item", e)
            throw e
        }
    }
    
    /**
     * Remove item by UID
     */
    suspend fun removeItemByUid(uid: String) {
        try {
            queueManager.removeByUid(uid)
            
            // Save state after removing item
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to remove queue item by UID", e)
            throw e
        }
    }
    
    /**
     * Clear transient queues (play next and user queue)
     */
    suspend fun clearTransientQueues(keepCurrent: Boolean = true) {
        try {
            queueManager.clearTransientQueues(keepCurrent)
            
            // Save state after clearing
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to clear transient queues", e)
            throw e
        }
    }
    
    /**
     * Clear entire queue
     */
    suspend fun clearQueue(keepCurrent: Boolean = false) {
        try {
            queueManager.clearQueue(keepCurrent)
            
            // Save state after clearing
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to clear queue", e)
            throw e
        }
    }
    
    /**
     * Set shuffle mode
     */
    suspend fun setShuffle(enabled: Boolean) {
        try {
            queueManager.setShuffle(enabled)
            
            // Save state after changing shuffle
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to set shuffle", e)
            throw e
        }
    }
    
    /**
     * Set repeat mode
     */
    fun setRepeat(mode: Int) {
        try {
            queueManager.setRepeat(mode)
            
            // Save state after changing repeat
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to set repeat", e)
            throw e
        }
    }
    
    /**
     * Get current queue snapshot
     */
    fun getQueueSnapshot(): List<QueueManager.QueueItem> {
        return queueManager.getQueueSnapshot()
    }
    
    /**
     * Get visible queue (items after current)
     */
    fun getVisibleQueue(): List<QueueManager.QueueItem> {
        return queueManager.getVisibleQueue()
    }
    
    /**
     * Get queue size
     */
    fun getQueueSize(): Int {
        return queueManager.getQueueSize()
    }
    
    /**
     * Get current playing item
     */
    fun getCurrentItem(): QueueManager.QueueItem? {
        return queueManager.getCurrentItem()
    }
    
    /**
     * Check if has next track
     */
    fun hasNext(): Boolean {
        return queueManager.hasNext()
    }
    
    /**
     * Check if has previous track
     */
    fun hasPrevious(): Boolean {
        return queueManager.hasPrevious()
    }
    
    /**
     * Get current index
     */
    fun getCurrentIndex(): Int {
        return queueManager.getCurrentIndex()
    }
    
    /**
     * Check if index is a play next item
     */
    fun isPlayNextIndex(index: Int): Boolean {
        return queueManager.isPlayNextIndex(index)
    }
    
    /**
     * Get queue statistics for debugging
     */
    fun getQueueStatistics(): QueueManager.QueueStatistics {
        return queueManager.getQueueStatistics()
    }
    
    /**
     * Update source playlist while preserving isolated items
     */
    suspend fun updateSourcePlaylist(
        tracks: List<Track>,
        sourceId: String,
        preserveCurrentPosition: Boolean = true
    ) {
        try {
            val mediaItems = tracks.map { it.toMediaItem() }
            
            queueManager.updateSourcePlaylist(
                newItems = mediaItems,
                sourceId = sourceId,
                preserveCurrentPosition = preserveCurrentPosition
            )
            
            // Save state after updating
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to update source playlist", e)
            throw e
        }
    }
    
    /**
     * Remove items from a specific source
     */
    suspend fun removeItemsFromSource(sourceId: String) {
        try {
            queueManager.removeItemsFromSource(sourceId)
            
            // Save state after removing items
            CoroutineScope(Dispatchers.IO).launch {
                playbackStateService.saveCurrentState()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to remove items from source", e)
            throw e
        }
    }
    
    /**
     * Handle track change event
     */
    fun onTrackChanged(mediaId: String) {
        try {
            queueManager.onTrackChanged(mediaId)
        } catch (e: Exception) {
            android.util.Log.e("QueueManagementUseCase", "Failed to handle track change", e)
        }
    }
}

// Extension function to convert Track to MediaItem
private fun Track.toMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(mediaId)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setGenre(genre)
                .setReleaseYear(year)
                .setTrackNumber(track)
                .setAlbumArtist(albumArtist)
                .build()
        )
        .build()
}