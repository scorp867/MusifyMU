package com.musify.mu.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.musify.mu.data.repo.QueueStateStore

/**
 * Simple Queue Manager for ExoPlayer
 * Handles basic queue operations and state persistence
 */
class QueueManager(
    private val player: ExoPlayer, 
    private val queueStateStore: QueueStateStore? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    /**
     * Set the queue with items and start playing
     */
    suspend fun setQueue(
        items: List<MediaItem>, 
        startIndex: Int = 0, 
        play: Boolean = true,
        startPosMs: Long = 0L
    ) {
        if (items.isEmpty()) return
        
        val validIndex = startIndex.coerceIn(0, items.size - 1)
        
        player.clearMediaItems()
        player.setMediaItems(items, validIndex, startPosMs)
        player.prepare()
        
        if (play) {
            player.play()
        }
    }
    
    /**
     * Get current queue as list of media IDs
     */
    fun getQueueIds(): List<String> {
        return (0 until player.mediaItemCount).mapNotNull { index ->
            player.getMediaItemAt(index)?.mediaId
        }
    }
}
