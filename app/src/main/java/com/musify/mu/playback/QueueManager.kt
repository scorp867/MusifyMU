package com.musify.mu.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.musify.mu.data.repo.QueueStateStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.ArrayDeque

/**
 * Advanced Queue Manager with hybrid data structures for optimal performance
 * Combines multiple data structures for different use cases:
 * - ArrayDeque for main queue operations (O(1) add/remove at ends)
 * - LinkedHashMap for fast lookups and duplicate detection (O(1))
 * - ConcurrentLinkedQueue for thread-safe operations
 * - Separate play-next queue for immediate playback
 */
class QueueManager(private val player: ExoPlayer, private val queueState: QueueStateStore? = null) {

    // Main queue using ArrayDeque for efficient operations
    private val mainQueue = ArrayDeque<QueueItem>()
    
    // Play-next queue for songs to be played immediately after current
    private val playNextQueue = ArrayDeque<QueueItem>()
    
    // Fast lookup map for duplicate detection and quick access
    private val queueLookup = LinkedHashMap<String, QueueItem>()
    
    // Thread-safe queue for concurrent operations
    private val operationQueue = ConcurrentLinkedQueue<QueueOperation>()
    
    // Mutex for thread-safe queue operations
    private val queueMutex = Mutex()
    
    // Current playing index in the combined queue
    private var currentIndex = 0
    
    // Queue metadata
    private var shuffleEnabled = false
    private var repeatMode = 0 // 0: none, 1: all, 2: one
    
    // Original order backup for shuffle
    private val originalOrder = ArrayDeque<QueueItem>()
    
    data class QueueItem(
        val mediaItem: MediaItem,
        val id: String = mediaItem.mediaId,
        val addedAt: Long = System.currentTimeMillis(),
        val source: QueueSource = QueueSource.USER_ADDED,
        var position: Int = -1
    )
    
    enum class QueueSource {
        USER_ADDED, PLAY_NEXT, ALBUM, PLAYLIST, RADIO, SHUFFLE
    }
    
    sealed class QueueOperation {
        data class Add(val items: List<QueueItem>, val position: Int = -1) : QueueOperation()
        data class Remove(val id: String) : QueueOperation()
        data class Move(val from: Int, val to: Int) : QueueOperation()
        data class Clear(val keepCurrent: Boolean = false) : QueueOperation()
        data class Shuffle(val enabled: Boolean) : QueueOperation()
    }

    suspend fun setQueue(
        items: List<MediaItem>,
        startIndex: Int = 0,
        play: Boolean = true,
        startPosMs: Long = 0L
    ) = queueMutex.withLock {
        try {
            // Clear existing queue
            mainQueue.clear()
            playNextQueue.clear()
            queueLookup.clear()
            originalOrder.clear()
            
            // Validate input
            if (items.isEmpty()) return@withLock
            
            val validStartIndex = startIndex.coerceIn(0, items.size - 1)
            
            // Create queue items with proper positioning
            val queueItems = items.mapIndexed { index, mediaItem ->
                QueueItem(
                    mediaItem = mediaItem,
                    position = index,
                    source = QueueSource.USER_ADDED
                )
            }
            
            // Add to main queue and lookup
            queueItems.forEach { item ->
                mainQueue.addLast(item)
                queueLookup[item.id] = item
                originalOrder.addLast(item.copy())
            }
            
            // Set media items in player
            player.setMediaItems(items, validStartIndex, 0L)
            player.prepare()
            
            currentIndex = validStartIndex
            
            // Handle start position
            if (startPosMs > 0L) {
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == androidx.media3.common.Player.STATE_READY) {
                            val duration = player.duration
                            if (duration != androidx.media3.common.C.TIME_UNSET && startPosMs < duration) {
                                player.seekTo(startPosMs)
                            }
                            player.removeListener(this)
                        }
                    }
                })
            }
            
            if (play) player.play()
            
            // Reset play-next count
            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch { qs.setPlayNextCount(0) }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error setting queue", e)
        }
    }

    suspend fun addToEnd(items: List<MediaItem>) = queueMutex.withLock {
        try {
            val queueItems = items.mapNotNull { mediaItem ->
                // Prevent duplicates
                if (queueLookup.containsKey(mediaItem.mediaId)) {
                    null
                } else {
                    QueueItem(
                        mediaItem = mediaItem,
                        position = mainQueue.size + playNextQueue.size,
                        source = QueueSource.USER_ADDED
                    )
                }
            }
            
            if (queueItems.isEmpty()) return@withLock
            
            // Add to main queue
            queueItems.forEach { item ->
                mainQueue.addLast(item)
                queueLookup[item.id] = item
            }
            
            // Add to player
            player.addMediaItems(queueItems.map { it.mediaItem })
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding to end", e)
        }
    }

    suspend fun playNext(items: List<MediaItem>) = queueMutex.withLock {
        try {
            val queueItems = items.mapNotNull { mediaItem ->
                // Remove from main queue if exists, we'll add to play-next
                queueLookup.remove(mediaItem.mediaId)?.let { existing ->
                    mainQueue.remove(existing)
                }
                
                QueueItem(
                    mediaItem = mediaItem,
                    source = QueueSource.PLAY_NEXT
                )
            }
            
            if (queueItems.isEmpty()) return@withLock
            
            // Add to play-next queue (LIFO for multiple items)
            queueItems.reversed().forEach { item ->
                playNextQueue.addFirst(item)
                queueLookup[item.id] = item
            }
            
            // Insert after current item in player
            val insertIndex = (player.currentMediaItemIndex + 1)
                .coerceAtMost(player.mediaItemCount)
            player.addMediaItems(insertIndex, queueItems.map { it.mediaItem })
            
            // Update play-next count
            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch {
                    val current = qs.getPlayNextCount()
                    qs.setPlayNextCount(current + queueItems.size)
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding play next", e)
        }
    }

    suspend fun move(from: Int, to: Int) = queueMutex.withLock {
        try {
            if (from == to || from < 0 || to < 0) return@withLock
            
            val totalSize = mainQueue.size + playNextQueue.size
            if (from >= totalSize || to >= totalSize) return@withLock
            
            // Get the combined queue for position calculations
            val combinedQueue = getCombinedQueue()
            
            if (from < combinedQueue.size && to < combinedQueue.size) {
                val item = combinedQueue[from]
                
                // Update internal structures
                updateQueueAfterMove(from, to)
                
                // Move in player
                player.moveMediaItem(from, to)
                
                // Update current index if needed
                when {
                    from == currentIndex -> currentIndex = to
                    from < currentIndex && to >= currentIndex -> currentIndex--
                    from > currentIndex && to <= currentIndex -> currentIndex++
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error moving item", e)
        }
    }

    suspend fun removeAt(index: Int) = queueMutex.withLock {
        try {
            val combinedQueue = getCombinedQueue()
            if (index < 0 || index >= combinedQueue.size) return@withLock
            
            val item = combinedQueue[index]
            
            // Remove from appropriate queue
            when (item.source) {
                QueueSource.PLAY_NEXT -> {
                    playNextQueue.remove(item)
                    queueState?.let { qs ->
                        CoroutineScope(Dispatchers.IO).launch {
                            val current = qs.getPlayNextCount()
                            qs.setPlayNextCount((current - 1).coerceAtLeast(0))
                        }
                    }
                }
                else -> mainQueue.remove(item)
            }
            
            // Remove from lookup
            queueLookup.remove(item.id)
            
            // Remove from player
            if (index < player.mediaItemCount) {
                player.removeMediaItem(index)
            }
            
            // Update current index
            if (index < currentIndex) {
                currentIndex--
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error removing item", e)
        }
    }

    suspend fun clearQueue(keepCurrent: Boolean = false) = queueMutex.withLock {
        try {
            if (keepCurrent && currentIndex >= 0) {
                val currentItem = getCombinedQueue().getOrNull(currentIndex)
                
                // Clear everything
                mainQueue.clear()
                playNextQueue.clear()
                queueLookup.clear()
                
                // Keep only current item
                currentItem?.let { item ->
                    mainQueue.addLast(item)
                    queueLookup[item.id] = item
                }
                
                currentIndex = 0
            } else {
                mainQueue.clear()
                playNextQueue.clear()
                queueLookup.clear()
                currentIndex = 0
            }
            
            // Clear player queue
            if (!keepCurrent) {
                player.clearMediaItems()
            } else {
                // Keep only current item
                val currentMediaItem = player.currentMediaItem
                player.clearMediaItems()
                currentMediaItem?.let { player.setMediaItem(it) }
            }
            
            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch { qs.setPlayNextCount(0) }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error clearing queue", e)
        }
    }

    fun setRepeat(mode: Int) {
        repeatMode = mode
        player.repeatMode = mode
    }

    suspend fun setShuffle(enabled: Boolean) = queueMutex.withLock {
        try {
            if (shuffleEnabled == enabled) return@withLock
            
            shuffleEnabled = enabled
            player.shuffleModeEnabled = enabled
            
            if (enabled) {
                // Save current order
                originalOrder.clear()
                getCombinedQueue().forEach { originalOrder.addLast(it.copy()) }
                
                // Shuffle the queue
                shuffleQueue()
            } else {
                // Restore original order
                restoreOriginalOrder()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error setting shuffle", e)
        }
    }

    fun snapshotIds(): List<String> = try {
        getCombinedQueue().map { it.id }
    } catch (e: Exception) {
        android.util.Log.e("QueueManager", "Error getting snapshot", e)
        emptyList()
    }

    fun getQueueSize(): Int = mainQueue.size + playNextQueue.size

    fun getCurrentIndex(): Int = currentIndex

    fun hasNext(): Boolean = currentIndex < getQueueSize() - 1

    fun hasPrevious(): Boolean = currentIndex > 0

    fun getCurrentItem(): QueueItem? = getCombinedQueue().getOrNull(currentIndex)

    // Private helper methods
    
    private fun getCombinedQueue(): List<QueueItem> {
        val combined = mutableListOf<QueueItem>()
        
        // Add items up to current index from main queue
        val currentInMain = currentIndex.coerceAtMost(mainQueue.size - 1)
        if (currentInMain >= 0) {
            combined.addAll(mainQueue.take(currentInMain + 1))
        }
        
        // Add play-next queue
        combined.addAll(playNextQueue)
        
        // Add remaining main queue items
        if (currentInMain + 1 < mainQueue.size) {
            combined.addAll(mainQueue.drop(currentInMain + 1))
        }
        
        return combined
    }
    
    private fun updateQueueAfterMove(from: Int, to: Int) {
        val combinedQueue = getCombinedQueue().toMutableList()
        if (from < combinedQueue.size && to < combinedQueue.size) {
            val item = combinedQueue.removeAt(from)
            combinedQueue.add(to, item)
            
            // Rebuild queues based on new order
            rebuildQueuesFromCombined(combinedQueue)
        }
    }
    
    private fun rebuildQueuesFromCombined(combinedQueue: List<QueueItem>) {
        mainQueue.clear()
        playNextQueue.clear()
        
        combinedQueue.forEach { item ->
            when (item.source) {
                QueueSource.PLAY_NEXT -> playNextQueue.addLast(item)
                else -> mainQueue.addLast(item)
            }
        }
    }
    
    private fun shuffleQueue() {
        val allItems = getCombinedQueue().toMutableList()
        val currentItem = allItems.getOrNull(currentIndex)
        
        // Remove current item temporarily
        currentItem?.let { allItems.remove(it) }
        
        // Shuffle remaining items
        allItems.shuffle()
        
        // Put current item back at the beginning
        currentItem?.let { allItems.add(0, it) }
        
        // Rebuild queues
        rebuildQueuesFromCombined(allItems)
        currentIndex = 0
    }
    
    private fun restoreOriginalOrder() {
        if (originalOrder.isEmpty()) return
        
        mainQueue.clear()
        playNextQueue.clear()
        
        originalOrder.forEach { item ->
            when (item.source) {
                QueueSource.PLAY_NEXT -> playNextQueue.addLast(item)
                else -> mainQueue.addLast(item)
            }
        }
        
        // Find current item position in original order
        val currentItem = getCurrentItem()
        currentIndex = originalOrder.indexOfFirst { it.id == currentItem?.id }
            .coerceAtLeast(0)
    }
}
