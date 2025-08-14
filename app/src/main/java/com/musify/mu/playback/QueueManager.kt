package com.musify.mu.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.musify.mu.data.repo.QueueStateStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.ArrayDeque
import kotlin.random.Random

/**
 * Advanced Queue Manager with hybrid data structures for optimal performance
 * Features:
 * - Play-next queue similar to Spotify's "Add to Queue"
 * - Context-aware playback: Remembers play context (album, playlist, etc.)
 * - Real-time UI updates with LiveData and StateFlow
 * - Advanced shuffle with anti-repetition algorithms
 * - Efficient queue operations with proper data structures
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

    // Shuffle history to prevent recent repetitions
    private val shuffleHistory = ArrayDeque<String>()
    private val maxShuffleHistory = 50

    // Play context for recommendations
    private var currentContext: PlayContext? = null

    // LiveData for real-time UI updates
    private val _queueState = MutableLiveData<QueueState>()
    val queueStateLiveData: LiveData<QueueState> = _queueState

    // StateFlow for Compose UI
    private val _queueStateFlow = MutableStateFlow(QueueState())
    val queueStateFlow: StateFlow<QueueState> = _queueStateFlow.asStateFlow()

    // Current item LiveData
    private val _currentItem = MutableLiveData<QueueItem?>()
    val currentItemLiveData: LiveData<QueueItem?> = _currentItem

    // Queue changes LiveData for drag and drop updates
    private val _queueChanges = MutableLiveData<QueueChangeEvent>()
    val queueChangesLiveData: LiveData<QueueChangeEvent> = _queueChanges

    data class QueueItem(
        val mediaItem: MediaItem,
        val id: String = mediaItem.mediaId,
        val addedAt: Long = System.currentTimeMillis(),
        val source: QueueSource = QueueSource.USER_ADDED,
        var position: Int = -1,
        val context: PlayContext? = null
    )

    enum class QueueSource {
        USER_ADDED, PLAY_NEXT, ALBUM, PLAYLIST, SHUFFLE, LIKED_SONGS
    }

    data class PlayContext(
        val type: ContextType,
        val id: String,
        val name: String,
        val metadata: Map<String, Any> = emptyMap()
    )

    enum class ContextType {
        ALBUM, PLAYLIST, ARTIST, GENRE, LIKED_SONGS, SEARCH, DISCOVER
    }

    data class QueueState(
        val totalItems: Int = 0,
        val currentIndex: Int = 0,
        val hasNext: Boolean = false,
        val hasPrevious: Boolean = false,
        val shuffleEnabled: Boolean = false,
        val repeatMode: Int = 0,
        val playNextCount: Int = 0,
        val context: PlayContext? = null
    )

    sealed class QueueChangeEvent {
        data class ItemAdded(val item: QueueItem, val position: Int) : QueueChangeEvent()
        data class ItemRemoved(val item: QueueItem, val position: Int) : QueueChangeEvent()
        data class ItemMoved(val from: Int, val to: Int, val item: QueueItem) : QueueChangeEvent()
        data class QueueCleared(val keepCurrent: Boolean) : QueueChangeEvent()
        data class QueueReordered(val newOrder: List<QueueItem>) : QueueChangeEvent()
        object QueueShuffled : QueueChangeEvent()
    }

    sealed class QueueOperation {
        data class Add(val items: List<QueueItem>, val position: Int = -1) : QueueOperation()
        data class Remove(val id: String) : QueueOperation()
        data class Move(val from: Int, val to: Int) : QueueOperation()
        data class Clear(val keepCurrent: Boolean = false) : QueueOperation()
        data class Shuffle(val enabled: Boolean) : QueueOperation()
    }

    init {
        // Initialize with current state
        updateUIState()
    }

    suspend fun setQueue(
        items: List<MediaItem>,
        startIndex: Int = 0,
        play: Boolean = true,
        startPosMs: Long = 0L,
        context: PlayContext? = null
    ) = queueMutex.withLock {
        try {
            // Clear existing queue
            mainQueue.clear()
            playNextQueue.clear()
            queueLookup.clear()
            originalOrder.clear()
            shuffleHistory.clear()

            // Validate input
            if (items.isEmpty()) {
                updateUIState()
                return@withLock
            }

            val validStartIndex = startIndex.coerceIn(0, items.size - 1)

            // Set play context
            currentContext = context

            // Create queue items with proper positioning and context
            val queueItems = items.mapIndexed { index, mediaItem ->
                QueueItem(
                    mediaItem = mediaItem,
                    position = index,
                    source = when (context?.type) {
                        ContextType.ALBUM -> QueueSource.ALBUM
                        ContextType.PLAYLIST -> QueueSource.PLAYLIST
                        ContextType.LIKED_SONGS -> QueueSource.LIKED_SONGS
                        else -> QueueSource.USER_ADDED
                    },
                    context = context
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

            // Update UI
            updateUIState()
            _queueChanges.postValue(QueueChangeEvent.QueueReordered(getCombinedQueue()))

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error setting queue", e)
        }
    }

    suspend fun addToEnd(items: List<MediaItem>, context: PlayContext? = null) = queueMutex.withLock {
        try {
            val queueItems = items.mapNotNull { mediaItem ->
                // Prevent duplicates
                if (queueLookup.containsKey(mediaItem.mediaId)) {
                    null
                } else {
                    QueueItem(
                        mediaItem = mediaItem,
                        position = mainQueue.size + playNextQueue.size,
                        source = QueueSource.USER_ADDED,
                        context = context ?: currentContext
                    )
                }
            }

            if (queueItems.isEmpty()) return@withLock

            // Add to main queue
            queueItems.forEach { item ->
                mainQueue.addLast(item)
                queueLookup[item.id] = item
                _queueChanges.postValue(QueueChangeEvent.ItemAdded(item, mainQueue.size - 1))
            }

            // Add to player
            player.addMediaItems(queueItems.map { it.mediaItem })

            updateUIState()

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding to end", e)
        }
    }

    suspend fun playNext(items: List<MediaItem>, context: PlayContext? = null) = queueMutex.withLock {
        try {
            val queueItems = items.mapNotNull { mediaItem ->
                // Remove from main queue if exists, we'll add to play-next
                queueLookup.remove(mediaItem.mediaId)?.let { existing ->
                    mainQueue.remove(existing)
                    _queueChanges.postValue(QueueChangeEvent.ItemRemoved(existing, existing.position))
                }

                QueueItem(
                    mediaItem = mediaItem,
                    source = QueueSource.PLAY_NEXT,
                    context = context ?: currentContext
                )
            }

            if (queueItems.isEmpty()) return@withLock

            // Add to play-next queue (LIFO for multiple items)
            queueItems.reversed().forEach { item ->
                playNextQueue.addFirst(item)
                queueLookup[item.id] = item
                _queueChanges.postValue(QueueChangeEvent.ItemAdded(item, currentIndex + 1))
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

            updateUIState()

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

                // Notify UI
                _queueChanges.postValue(QueueChangeEvent.ItemMoved(from, to, item))
                updateUIState()
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

            // Notify UI
            _queueChanges.postValue(QueueChangeEvent.ItemRemoved(item, index))
            updateUIState()

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

            // Notify UI
            _queueChanges.postValue(QueueChangeEvent.QueueCleared(keepCurrent))
            updateUIState()

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error clearing queue", e)
        }
    }

    fun setRepeat(mode: Int) {
        repeatMode = mode
        player.repeatMode = mode
        updateUIState()
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

                // Smart shuffle the queue
                smartShuffleQueue()
            } else {
                // Restore original order
                restoreOriginalOrder()
            }

            _queueChanges.postValue(QueueChangeEvent.QueueShuffled)
            updateUIState()

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

    fun getCurrentItem(): QueueItem? {
        val current = getCombinedQueue().getOrNull(currentIndex)
        _currentItem.postValue(current)
        return current
    }

    fun getVisibleQueue(): List<QueueItem> {
        // Return the main user-visible queue
        val visible = mutableListOf<QueueItem>()
        visible.addAll(mainQueue.take(currentIndex + 1))
        visible.addAll(playNextQueue)
        if (currentIndex + 1 < mainQueue.size) {
            visible.addAll(mainQueue.drop(currentIndex + 1))
        }
        return visible
    }

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

    private fun smartShuffleQueue() {
        val allItems = getCombinedQueue().toMutableList()
        val currentItem = allItems.getOrNull(currentIndex)

        // Remove current item temporarily
        currentItem?.let { allItems.remove(it) }

        // Smart shuffle with anti-repetition
        val shuffled = smartShuffle(allItems)

        // Put current item back at the beginning
        val result = mutableListOf<QueueItem>()
        currentItem?.let { result.add(it) }
        result.addAll(shuffled)

        // Rebuild queues
        rebuildQueuesFromCombined(result)
        currentIndex = 0
    }

    private fun smartShuffle(items: List<QueueItem>): List<QueueItem> {
        if (items.size <= 1) return items

        val result = mutableListOf<QueueItem>()
        val remaining = items.toMutableList()

        // First pass: avoid recent tracks
        val recentlyPlayed = shuffleHistory.take(20).toSet()
        val nonRecent = remaining.filter { it.id !in recentlyPlayed }
        val recent = remaining.filter { it.id in recentlyPlayed }

        // Shuffle non-recent first
        remaining.clear()
        remaining.addAll(nonRecent.shuffled())
        remaining.addAll(recent.shuffled())

        // Advanced shuffle: avoid same artist consecutively
        while (remaining.isNotEmpty()) {
            val candidates = if (result.isEmpty()) {
                remaining
            } else {
                val lastArtist = result.last().mediaItem.mediaMetadata.artist?.toString()
                remaining.filter { it.mediaItem.mediaMetadata.artist?.toString() != lastArtist }
                    .ifEmpty { remaining }
            }

            val selected = candidates.random()
            result.add(selected)
            remaining.remove(selected)
        }

        return result
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

    private fun updateUIState() {
        val state = QueueState(
            totalItems = getQueueSize(),
            currentIndex = currentIndex,
            hasNext = hasNext(),
            hasPrevious = hasPrevious(),
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            playNextCount = playNextQueue.size,
            context = currentContext
        )

        _queueState.postValue(state)
        _queueStateFlow.value = state
    }

    // Called when track changes to update history
    fun onTrackChanged(mediaId: String) {
        shuffleHistory.addFirst(mediaId)
        if (shuffleHistory.size > maxShuffleHistory) {
            shuffleHistory.removeLast()
        }
        getCurrentItem()
    }
}
