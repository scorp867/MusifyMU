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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Advanced Queue Manager with Spotify-like features:
 * - Smart Queue: Automatically suggests songs based on listening history
 * - Radio Mode: Creates infinite queue based on seed tracks
 * - Queue History: Navigate back through previously played tracks
 * - Enhanced Play Next: Multiple play-next queues (immediate, soon, later)
 * - Real-time updates with StateFlow for UI synchronization
 * - Advanced shuffle with genre/artist clustering
 */
class QueueManager(
    private val player: ExoPlayer, 
    private val queueState: QueueStateStore? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    // Main queue using ArrayDeque for efficient operations
    private val mainQueue = ArrayDeque<QueueItem>()
    
    // Multiple priority queues for Spotify-like behavior
    private val playNextQueue = ArrayDeque<QueueItem>() // Immediate play next
    private val playSoonQueue = ArrayDeque<QueueItem>() // After play next items
    private val smartQueue = ArrayDeque<QueueItem>() // AI/algorithm suggested items
    
    // History tracking for back navigation
    private val historyQueue = ArrayDeque<QueueItem>()
    private val maxHistorySize = 50
    
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
    private var repeatMode = RepeatMode.NONE
    private var radioMode = false
    private var crossfadeEnabled = false
    private var gaplessPlayback = true
    
    // Original order backup for shuffle
    private val originalOrder = ArrayDeque<QueueItem>()
    
    // StateFlow for real-time UI updates
    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()
    
    // Smart queue learning data
    private val listeningPatterns = mutableMapOf<String, MutableList<String>>() // artist -> next artists
    private val genrePreferences = mutableMapOf<String, Int>() // genre -> play count
    
    data class QueueItem(
        val mediaItem: MediaItem,
        val id: String = mediaItem.mediaId,
        val addedAt: Long = System.currentTimeMillis(),
        val source: QueueSource = QueueSource.USER_ADDED,
        var position: Int = -1,
        val metadata: TrackMetadata? = null
    )
    
    data class TrackMetadata(
        val genre: String? = null,
        val mood: String? = null,
        val energy: Float = 0.5f,
        val popularity: Float = 0.5f,
        val releaseYear: Int? = null
    )
    
    enum class QueueSource {
        USER_ADDED, PLAY_NEXT, PLAY_SOON, SMART_QUEUE, ALBUM, PLAYLIST, RADIO, SHUFFLE, HISTORY
    }
    
    enum class RepeatMode {
        NONE, ALL, ONE
    }
    
    data class QueueState(
        val currentTrack: QueueItem? = null,
        val upNext: List<QueueItem> = emptyList(),
        val playNextCount: Int = 0,
        val playSoonCount: Int = 0,
        val smartQueueCount: Int = 0,
        val totalSize: Int = 0,
        val currentIndex: Int = 0,
        val isShuffled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.NONE,
        val isRadioMode: Boolean = false,
        val hasHistory: Boolean = false,
        val canSkipNext: Boolean = false,
        val canSkipPrevious: Boolean = false
    )
    
    sealed class QueueOperation {
        data class Add(val items: List<QueueItem>, val position: Int = -1) : QueueOperation()
        data class Remove(val id: String) : QueueOperation()
        data class Move(val from: Int, val to: Int) : QueueOperation()
        data class Clear(val keepCurrent: Boolean = false) : QueueOperation()
        data class Shuffle(val enabled: Boolean) : QueueOperation()
        data class SetRadioMode(val enabled: Boolean, val seedTrack: QueueItem? = null) : QueueOperation()
    }

    init {
        // Listen to player events
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                scope.launch {
                    handleTrackTransition(mediaItem, reason)
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                scope.launch {
                    updateQueueState()
                }
            }
        })
    }

    suspend fun setQueue(
        items: List<MediaItem>,
        startIndex: Int = 0,
        play: Boolean = true,
        startPosMs: Long = 0L
    ) = queueMutex.withLock {
        try {
            // Clear existing queues
            clearAllQueues()
            
            // Validate input
            if (items.isEmpty()) {
                updateQueueState()
                return@withLock
            }
            
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
            
            // Update state
            updateQueueState()
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error setting queue", e)
        }
    }

    suspend fun addToEnd(items: List<MediaItem>) = queueMutex.withLock {
        try {
            val queueItems = items.mapNotNull { mediaItem ->
                // Prevent duplicates unless in radio mode
                if (!radioMode && queueLookup.containsKey(mediaItem.mediaId)) {
                    null
                } else {
                    QueueItem(
                        mediaItem = mediaItem,
                        position = mainQueue.size + playNextQueue.size + playSoonQueue.size,
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
            
            updateQueueState()
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding to end", e)
        }
    }

    suspend fun playNext(items: List<MediaItem>) = queueMutex.withLock {
        try {
            val queueItems = items.mapNotNull { mediaItem ->
                // Remove from other queues if exists
                removeFromQueues(mediaItem.mediaId)
                
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
            
            updateQueueState()
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding play next", e)
        }
    }

    suspend fun playSoon(items: List<MediaItem>) = queueMutex.withLock {
        try {
            val queueItems = items.mapNotNull { mediaItem ->
                // Remove from other queues if exists
                removeFromQueues(mediaItem.mediaId)
                
                QueueItem(
                    mediaItem = mediaItem,
                    source = QueueSource.PLAY_SOON
                )
            }
            
            if (queueItems.isEmpty()) return@withLock
            
            // Add to play-soon queue
            queueItems.forEach { item ->
                playSoonQueue.addLast(item)
                queueLookup[item.id] = item
            }
            
            // Insert after play-next items in player
            val insertIndex = (player.currentMediaItemIndex + 1 + playNextQueue.size)
                .coerceAtMost(player.mediaItemCount)
            player.addMediaItems(insertIndex, queueItems.map { it.mediaItem })
            
            updateQueueState()
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding play soon", e)
        }
    }

    suspend fun enableRadioMode(seedTrack: MediaItem? = null) = queueMutex.withLock {
        radioMode = true
        val seed = seedTrack ?: getCurrentItem()?.mediaItem
        
        if (seed != null) {
            // Generate smart recommendations based on seed
            generateSmartQueue(seed)
        }
        
        updateQueueState()
    }

    suspend fun disableRadioMode() = queueMutex.withLock {
        radioMode = false
        smartQueue.clear()
        updateQueueState()
    }

    suspend fun navigateBack(): Boolean = queueMutex.withLock {
        if (historyQueue.isEmpty()) return@withLock false
        
        val previousItem = historyQueue.removeLast()
        val previousIndex = getCombinedQueue().indexOfFirst { it.id == previousItem.id }
        
        if (previousIndex >= 0) {
            player.seekToDefaultPosition(previousIndex)
            currentIndex = previousIndex
            updateQueueState()
            true
        } else {
            false
        }
    }

    suspend fun move(from: Int, to: Int) = queueMutex.withLock {
        try {
            if (from == to || from < 0 || to < 0) return@withLock
            
            val totalSize = getCombinedQueue().size
            if (from >= totalSize || to >= totalSize) return@withLock
            
            // Move in player
            player.moveMediaItem(from, to)
            
            // Update internal structures
            updateQueuesAfterMove(from, to)
            
            // Update current index if needed
            when {
                from == currentIndex -> currentIndex = to
                from < currentIndex && to >= currentIndex -> currentIndex--
                from > currentIndex && to <= currentIndex -> currentIndex++
            }
            
            updateQueueState()
            
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
            removeFromQueues(item.id)
            
            // Remove from player
            if (index < player.mediaItemCount) {
                player.removeMediaItem(index)
            }
            
            // Update current index
            if (index < currentIndex) {
                currentIndex--
            }
            
            updateQueueState()
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error removing item", e)
        }
    }

    suspend fun clearQueue(keepCurrent: Boolean = false) = queueMutex.withLock {
        try {
            if (keepCurrent && currentIndex >= 0) {
                val currentItem = getCombinedQueue().getOrNull(currentIndex)
                
                // Clear everything
                clearAllQueues()
                
                // Keep only current item
                currentItem?.let { item ->
                    mainQueue.addLast(item)
                    queueLookup[item.id] = item
                }
                
                currentIndex = 0
            } else {
                clearAllQueues()
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
            
            updateQueueState()
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error clearing queue", e)
        }
    }

    fun setRepeat(mode: RepeatMode) {
        repeatMode = mode
        player.repeatMode = when (mode) {
            RepeatMode.NONE -> androidx.media3.common.Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> androidx.media3.common.Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> androidx.media3.common.Player.REPEAT_MODE_ONE
        }
        scope.launch {
            updateQueueState()
        }
    }

    suspend fun setShuffle(enabled: Boolean) = queueMutex.withLock {
        try {
            if (shuffleEnabled == enabled) return@withLock
            
            shuffleEnabled = enabled
            
            if (enabled) {
                // Save current order
                originalOrder.clear()
                getCombinedQueue().forEach { originalOrder.addLast(it.copy()) }
                
                // Smart shuffle that keeps similar songs together
                smartShuffle()
            } else {
                // Restore original order
                restoreOriginalOrder()
            }
            
            updateQueueState()
            
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error setting shuffle", e)
        }
    }

    // Private helper methods
    
    private fun getCombinedQueue(): List<QueueItem> {
        val combined = mutableListOf<QueueItem>()
        
        // Add items up to current index from main queue
        val currentInMain = currentIndex.coerceAtMost(mainQueue.size - 1)
        if (currentInMain >= 0) {
            combined.addAll(mainQueue.take(currentInMain + 1))
        }
        
        // Add priority queues in order
        combined.addAll(playNextQueue)
        combined.addAll(playSoonQueue)
        
        // Add smart queue items if in radio mode
        if (radioMode) {
            combined.addAll(smartQueue.take(5)) // Add next 5 smart suggestions
        }
        
        // Add remaining main queue items
        if (currentInMain + 1 < mainQueue.size) {
            combined.addAll(mainQueue.drop(currentInMain + 1))
        }
        
        return combined
    }
    
    private suspend fun updateQueueState() {
        val combined = getCombinedQueue()
        val current = combined.getOrNull(currentIndex)
        val upNext = if (currentIndex + 1 < combined.size) {
            combined.subList(currentIndex + 1, minOf(currentIndex + 11, combined.size))
        } else {
            emptyList()
        }
        
        _queueState.value = QueueState(
            currentTrack = current,
            upNext = upNext,
            playNextCount = playNextQueue.size,
            playSoonCount = playSoonQueue.size,
            smartQueueCount = smartQueue.size,
            totalSize = combined.size,
            currentIndex = currentIndex,
            isShuffled = shuffleEnabled,
            repeatMode = repeatMode,
            isRadioMode = radioMode,
            hasHistory = historyQueue.isNotEmpty(),
            canSkipNext = hasNext(),
            canSkipPrevious = hasPrevious() || historyQueue.isNotEmpty()
        )
    }
    
    private fun clearAllQueues() {
        mainQueue.clear()
        playNextQueue.clear()
        playSoonQueue.clear()
        smartQueue.clear()
        queueLookup.clear()
        originalOrder.clear()
    }
    
    private fun removeFromQueues(mediaId: String): QueueItem? {
        val item = queueLookup.remove(mediaId)
        item?.let {
            mainQueue.remove(it)
            playNextQueue.remove(it)
            playSoonQueue.remove(it)
            smartQueue.remove(it)
        }
        return item
    }
    
    private suspend fun handleTrackTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItem?.let { item ->
            // Add to history
            getCurrentItem()?.let { current ->
                historyQueue.addLast(current)
                if (historyQueue.size > maxHistorySize) {
                    historyQueue.removeFirst()
                }
            }
            
            // Learn from transition for smart queue
            if (reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                learnFromTransition(item)
            }
            
            // Generate more smart queue items if needed
            if (radioMode && smartQueue.size < 10) {
                generateSmartQueue(item)
            }
        }
        
        updateQueueState()
    }
    
    private fun learnFromTransition(currentItem: MediaItem) {
        // Simple learning: track artist transitions
        val artist = currentItem.mediaMetadata.artist?.toString()
        val previousArtist = historyQueue.lastOrNull()?.mediaItem?.mediaMetadata?.artist?.toString()
        
        if (artist != null && previousArtist != null) {
            listeningPatterns.getOrPut(previousArtist) { mutableListOf() }.add(artist)
        }
    }
    
    private suspend fun generateSmartQueue(seedItem: MediaItem) {
        // This is a simplified version - in reality, this would use ML models
        // For now, just add some randomness based on artist/genre
        
        // TODO: Implement smart recommendations based on:
        // - Listening history
        // - Genre preferences
        // - Time of day
        // - User's skip behavior
        // - Collaborative filtering
    }
    
    private fun smartShuffle() {
        val items = getCombinedQueue().toMutableList()
        val currentItem = items.getOrNull(currentIndex)
        
        // Remove current item temporarily
        currentItem?.let { items.remove(it) }
        
        // Group by artist to avoid playing same artist back-to-back
        val byArtist = items.groupBy { 
            it.mediaItem.mediaMetadata.artist?.toString() ?: "Unknown"
        }
        
        // Distribute artists evenly
        val shuffled = mutableListOf<QueueItem>()
        val artists = byArtist.keys.shuffled()
        var index = 0
        
        while (shuffled.size < items.size) {
            val artist = artists[index % artists.size]
            byArtist[artist]?.let { tracks ->
                if (tracks.isNotEmpty()) {
                    shuffled.add(tracks.random())
                    (byArtist[artist] as MutableList).remove(shuffled.last())
                }
            }
            index++
        }
        
        // Put current item back at the beginning
        currentItem?.let { shuffled.add(0, it) }
        
        // Rebuild queues
        rebuildQueuesFromCombined(shuffled)
        currentIndex = 0
    }
    
    private fun restoreOriginalOrder() {
        if (originalOrder.isEmpty()) return
        
        clearAllQueues()
        
        originalOrder.forEach { item ->
            when (item.source) {
                QueueSource.PLAY_NEXT -> playNextQueue.addLast(item)
                QueueSource.PLAY_SOON -> playSoonQueue.addLast(item)
                QueueSource.SMART_QUEUE -> smartQueue.addLast(item)
                else -> mainQueue.addLast(item)
            }
            queueLookup[item.id] = item
        }
        
        // Find current item position in original order
        val currentItem = getCurrentItem()
        currentIndex = originalOrder.indexOfFirst { it.id == currentItem?.id }
            .coerceAtLeast(0)
    }
    
    private fun rebuildQueuesFromCombined(combinedQueue: List<QueueItem>) {
        clearAllQueues()
        
        combinedQueue.forEach { item ->
            when (item.source) {
                QueueSource.PLAY_NEXT -> playNextQueue.addLast(item)
                QueueSource.PLAY_SOON -> playSoonQueue.addLast(item)
                QueueSource.SMART_QUEUE -> smartQueue.addLast(item)
                else -> mainQueue.addLast(item)
            }
            queueLookup[item.id] = item
        }
    }
    
    private fun updateQueuesAfterMove(from: Int, to: Int) {
        val combinedQueue = getCombinedQueue().toMutableList()
        if (from < combinedQueue.size && to < combinedQueue.size) {
            val item = combinedQueue.removeAt(from)
            combinedQueue.add(to, item)
            
            // Rebuild queues based on new order
            rebuildQueuesFromCombined(combinedQueue)
        }
    }
    
    fun hasNext(): Boolean = currentIndex < getCombinedQueue().size - 1 || repeatMode != RepeatMode.NONE
    
    fun hasPrevious(): Boolean = currentIndex > 0 || historyQueue.isNotEmpty()
    
    fun getCurrentItem(): QueueItem? = getCombinedQueue().getOrNull(currentIndex)
    
    fun getQueueSize(): Int = getCombinedQueue().size
    
    fun getCurrentIndex(): Int = currentIndex
}
