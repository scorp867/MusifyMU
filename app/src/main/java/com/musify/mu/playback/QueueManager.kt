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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentLinkedQueue
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import kotlin.collections.ArrayDeque
import kotlin.random.Random
import java.util.UUID

/**
 * Advanced Queue Manager with hybrid data structures for optimal performance
 * Features:
 * - Play-next queue similar to Spotify's "Add to Queue"
 * - Context-aware playback: Remembers play context (album, playlist, etc.)
 * - Real-time UI updates with LiveData and StateFlow
 * - Advanced shuffle with anti-repetition algorithms
 * - Efficient queue operations with proper data structures
 */
class QueueManager(
    private val player: ExoPlayer, 
    private val queueState: QueueStateStore? = null
) {
    private val logTag = "QueueManagerDBG"

    // Internal queues are maintained as MutableLists for reorderable operations

    // Three-queue model
    // - priorityList: Play Next (highest priority)
    // - userList: User Queue (Add to next)
    // - mainList: Main context queue (playlist/album/etc.)
    private val mainList = mutableListOf<QueueItem>()
    private val priorityList = mutableListOf<QueueItem>()
    private val userList = mutableListOf<QueueItem>()

    // Fast lookup map for duplicate detection and quick access
    private val queueLookup = LinkedHashMap<String, QueueItem>()

    // Channel for background operations (non-blocking, unbounded)
    private val operationChannel = Channel<QueueOperation>(Channel.UNLIMITED)

    // Pending operation count for UI feedback
    private val _pendingOperations = MutableStateFlow(0)
    val pendingOperations: StateFlow<Int> = _pendingOperations.asStateFlow()

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
        val uid: String = UUID.randomUUID().toString(),
        val mediaItem: MediaItem,
        val id: String = mediaItem.mediaId,
        val addedAt: Long = System.currentTimeMillis(),
        var source: QueueSource = QueueSource.USER_ADDED,
        var position: Int = -1,
        val context: PlayContext? = null,
        val isIsolated: Boolean = false, // True if item should not be affected by source changes
        val originalSourceId: String? = null, // Track original source for reference
        val userMetadata: Map<String, Any> = emptyMap() // Additional user-defined metadata
    )

    enum class QueueSource {
        USER_ADDED, // legacy main additions
        PLAY_NEXT,  // priority queue
        USER_QUEUE, // user queue (add to next)
        ALBUM,
        PLAYLIST,
        LIKED_SONGS
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
        val userQueueCount: Int = 0,
        val context: PlayContext? = null
    )

    sealed class QueueChangeEvent {
        data class ItemAdded(val item: QueueItem, val position: Int) : QueueChangeEvent()
        data class ItemRemoved(val item: QueueItem, val position: Int) : QueueChangeEvent()
        data class ItemMoved(val from: Int, val to: Int, val item: QueueItem) : QueueChangeEvent()
        data class QueueCleared(val keepCurrent: Boolean) : QueueChangeEvent()
        data class QueueReordered(val newOrder: List<QueueItem>) : QueueChangeEvent()
        data class QueueCleanup(val removedCount: Int, val keptCount: Int) : QueueChangeEvent()
        object QueueShuffled : QueueChangeEvent()
    }

    sealed class QueueOperation {
        data class Add(val items: List<QueueItem>, val position: Int = -1) : QueueOperation()
    }

    init {
        // Initialize with current state
        updateUIState()

        // Start background operation processing
        CoroutineScope(Dispatchers.IO).launch {
            processOperationQueue()
        }
    }

    /**
     * Background operation processor that handles queued operations
     * This ensures all operations are processed sequentially and safely
     */
    private suspend fun processOperationQueue() {
        for (operation in operationChannel) {
            try {
                executeOperation(operation)
            } catch (e: Exception) {
                android.util.Log.e("QueueManager", "Error executing operation: $operation", e)
            } finally {
                _pendingOperations.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    /**
     * Execute individual queue operations
     */
    private suspend fun executeOperation(operation: QueueOperation) = queueMutex.withLock {
        when (operation) {
            is QueueOperation.Add -> {
                val position = if (operation.position == -1) mainList.size else operation.position
                android.util.Log.d(logTag, "queueAdd op: items=${operation.items.size} at=$position before mainSize=${mainList.size}")
                operation.items.forEachIndexed { index, item ->
                    val insertIndex = (position + index).coerceIn(0, mainList.size)
                    mainList.add(insertIndex, item)
                    queueLookup[item.id] = item
                    _queueChanges.postValue(QueueChangeEvent.ItemAdded(item, insertIndex))
                }
                android.util.Log.d(logTag, "queueAdd op done: mainSize=${mainList.size} totalSize=${getQueueSize()}")
                updateUIState()
            }
        }
    }

    suspend fun setQueue(
        items: MutableList<MediaItem>,
        startIndex: Int = 0,
        play: Boolean = true,
        startPosMs: Long = 0L,
        context: PlayContext? = null
    ) = queueMutex.withLock {
        try {
            android.util.Log.d(logTag, "setQueue start items=${items.size} startIndex=$startIndex context=$context")
            // Clear existing queue
            mainList.clear()
            priorityList.clear()
            userList.clear()
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
            val baseTime = System.currentTimeMillis()
            val queueItems = items.mapIndexed { index, mediaItem ->
                QueueItem(
                    mediaItem = mediaItem,
                    addedAt = baseTime + index,
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

            // Add to main list and lookup
            queueItems.forEach { item ->
                mainList.add(item)
                queueLookup[item.id] = item
                originalOrder.addLast(item.copy())
            }

            // Set media items in player
            player.setMediaItems(items, validStartIndex, 0L)
            player.prepare()

            currentIndex = validStartIndex
            android.util.Log.d(logTag, "setQueue prepared: main=${mainList.size} pri=${priorityList.size} user=${userList.size} currentIndex=$currentIndex")

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

    /**
     * Add items to the User Queue ("Add to next"). They will play after all priority items.
     */
    suspend fun addToUserQueue(items: MutableList<MediaItem>, context: PlayContext? = null, allowDuplicates: Boolean = true) = queueMutex.withLock {
        try {
            android.util.Log.d(logTag, "addToUserQueue start items=${items.size} context=$context before user=${userList.size} allowDuplicates=$allowDuplicates")
            val baseTime = System.currentTimeMillis()
            var offset = 0
            val queueItems = items.mapNotNull { mediaItem ->
                // Only prevent duplicates if explicitly requested (for bulk operations)
                if (!allowDuplicates && queueLookup.containsKey(mediaItem.mediaId)) {
                    android.util.Log.d(logTag, "addToUserQueue skip duplicate id=${mediaItem.mediaId}")
                    null
                } else {
                    QueueItem(
                        mediaItem = mediaItem,
                        addedAt = baseTime + (offset++),
                        position = mainList.size + priorityList.size + userList.size,
                        source = QueueSource.USER_QUEUE,
                        context = context ?: currentContext,
                        isIsolated = true, // User queue items are isolated from source changes
                        originalSourceId = context?.id,
                        userMetadata = mapOf("addedByUser" to true, "timestamp" to baseTime)
                    )
                }
            }

            if (queueItems.isEmpty()) return@withLock

            // Add to user queue list (FIFO at end of user segment)
            queueItems.forEach { item ->
                userList.add(item)
                queueLookup[item.id] = item
                // Calculate visual insert position in combined timeline (after current + priority + existing user)
                val insertIndex = (player.currentMediaItemIndex + 1 + priorityList.size + (userList.size - 1))
                    .coerceAtMost(player.mediaItemCount)
                // Insert in player timeline
                player.addMediaItems(insertIndex, listOf(item.mediaItem))
                _queueChanges.postValue(QueueChangeEvent.ItemAdded(item, insertIndex))
            }

            android.util.Log.d(logTag, "addToUserQueue done user=${userList.size} playerCount=${player.mediaItemCount}")
            updateUIState()

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding to user queue", e)
        }
    }

    /**
     * Add items to the Priority Queue (Play Next). They will play immediately after the current item.
     */
    suspend fun playNext(items: MutableList<MediaItem>, context: PlayContext? = null) = queueMutex.withLock {
        try {
            android.util.Log.d(logTag, "playNext start items=${items.size} context=$context before pri=${priorityList.size}")
            val baseTime = System.currentTimeMillis()
            var offset = 0
            val queueItems = items.map { mediaItem ->
                // Keep originals; add duplicates to play-next segment
                QueueItem(
                    mediaItem = mediaItem,
                    addedAt = baseTime + (offset++),
                    source = QueueSource.PLAY_NEXT,
                    context = context ?: currentContext,
                    isIsolated = true, // Priority queue items are isolated from source changes
                    originalSourceId = context?.id,
                    userMetadata = mapOf("addedByUser" to true, "priority" to true, "timestamp" to baseTime)
                )
            }

            if (queueItems.isEmpty()) return@withLock

            // Add to priority queue FIFO
            val existingPlayNext = priorityList.size
            queueItems.forEach { item ->
                priorityList.add(item)
                queueLookup[item.id] = item
            }

            // Insert after current item + existing play-next items in player timeline
            val insertIndex = (player.currentMediaItemIndex + 1 + existingPlayNext)
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
            android.util.Log.d(logTag, "playNext done pri=${priorityList.size} playerCount=${player.mediaItemCount}")

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding play next", e)
        }
    }

    suspend fun move(from: Int, to: Int) = queueMutex.withLock {
        try {
            android.util.Log.d(logTag, "move request from=$from to=$to total=${getQueueSize()}")
            if (from == to || from < 0 || to < 0) return@withLock

            val totalSize = mainList.size + priorityList.size + userList.size
            if (from >= totalSize || to >= totalSize) return@withLock

            // Get the combined queue for position calculations
            val combinedQueue = getCombinedQueue()

            if (from < combinedQueue.size && to < combinedQueue.size) {
                val item = combinedQueue[from]

                // Calculate current segment boundaries in combined indices
                val mainAll = mainList.toList()
                val currentInMain = currentIndex.coerceAtMost((mainAll.size - 1).coerceAtLeast(-1))
                val priStart = (currentInMain + 1).coerceAtLeast(0)
                val priEndExclusive = priStart + priorityList.size
                val userStart = priEndExclusive
                val userEndExclusive = userStart + userList.size
                val mainTailStart = userEndExclusive

                // Determine target segment by destination index
                val newSource = when {
                    to in priStart until priEndExclusive -> QueueSource.PLAY_NEXT
                    to in userStart until userEndExclusive -> QueueSource.USER_QUEUE
                    else -> when (currentContext?.type) {
                        ContextType.ALBUM -> QueueSource.ALBUM
                        ContextType.PLAYLIST -> QueueSource.PLAYLIST
                        ContextType.LIKED_SONGS -> QueueSource.LIKED_SONGS
                        else -> QueueSource.USER_ADDED
                    }
                }

                // Update item source to reflect cross-section move
                if (item.source != newSource) {
                    item.source = newSource
                }

                // Update internal structures based on the new combined order
                updateQueueAfterMove(from, to)

                // Move in player timeline
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
                android.util.Log.d(logTag, "move done currentIndex=$currentIndex")
            }

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error moving item", e)
        }
    }

    private fun removeAtInternal(index: Int): Boolean {
        android.util.Log.d(logTag, "removeAtInternal index=$index total=${getQueueSize()}")
        val combinedQueue = getCombinedQueue()
        if (index < 0 || index >= combinedQueue.size) return false

        val item = combinedQueue[index]

        // Resolve the actual instance from internal lists by uid first, then id
        fun findAndRemoveFromList(list: MutableList<QueueItem>): Boolean {
            val idx = list.indexOfFirst { it === item || it.uid == item.uid || it.id == item.id }
            return if (idx >= 0) {
                list.removeAt(idx)
                true
            } else false
        }

        var removedFrom: QueueSource? = null
        if (findAndRemoveFromList(priorityList)) {
            removedFrom = QueueSource.PLAY_NEXT
            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch {
                    val current = qs.getPlayNextCount()
                    qs.setPlayNextCount((current - 1).coerceAtLeast(0))
                }
            }
        } else if (findAndRemoveFromList(userList)) {
            removedFrom = QueueSource.USER_QUEUE
        } else if (findAndRemoveFromList(mainList)) {
            removedFrom = item.source
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
        android.util.Log.d(logTag, "removeAtInternal done removedSource=${item.source} currentIndex=$currentIndex")
        return removedFrom == QueueSource.PLAY_NEXT
    }

    suspend fun removeAt(index: Int) = queueMutex.withLock {
        try {
            removeAtInternal(index)
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error removing item", e)
        }
    }

    suspend fun removeByUid(uid: String) = queueMutex.withLock {
        try {
            val idx = getCombinedQueue().indexOfFirst { it.uid == uid }
            if (idx >= 0) removeAtInternal(idx)
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error removing item by uid", e)
        }
    }

    /**
     * Clear both Play Next and User Queue segments. Optionally keep the current item even if transient.
     * Uses selective removal to avoid playback hiccups.
     */
    suspend fun clearTransientQueues(keepCurrent: Boolean = true) = queueMutex.withLock {
        try {
            val combined = getCombinedQueue()
            val current = getCurrentItem()
            val currentPlayerIndex = player.currentMediaItemIndex
            val wasPlaying = player.isPlaying

            android.util.Log.d(logTag, "clearTransientQueues start: total=${combined.size} currentIdx=$currentPlayerIndex keepCurrent=$keepCurrent")

            // Find items to remove (transient items, but optionally keep current)
            val itemsToRemove = mutableListOf<Int>()
            combined.forEachIndexed { index, item ->
                val isTransient = (item.source == QueueSource.PLAY_NEXT || item.source == QueueSource.USER_QUEUE)
                if (isTransient) {
                    // If keeping current and this is the current item, don't remove it
                    val isCurrentItem = (keepCurrent && current != null && item.uid == current.uid)
                    if (!isCurrentItem) {
                        itemsToRemove.add(index)
                    }
                }
            }

            // Remove items from back to front to maintain indices
            var removedCount = 0
            itemsToRemove.sortedDescending().forEach { index ->
                try {
                    // Remove from player without rebuilding entire queue
                    if (index < player.mediaItemCount) {
                        player.removeMediaItem(index)
                        removedCount++
                    }

                    // Update our internal state
                    val item = combined.getOrNull(index)
                    item?.let {
                        when (it.source) {
                            QueueSource.PLAY_NEXT -> priorityList.removeAll { qi -> qi.uid == it.uid }
                            QueueSource.USER_QUEUE -> userList.removeAll { qi -> qi.uid == it.uid }
                            else -> {} // Don't remove main items
                        }
                        queueLookup.remove(it.id)
                    }

                    // Adjust current index if needed
                    if (index <= currentIndex) {
                        currentIndex = (currentIndex - 1).coerceAtLeast(0)
                    }
                } catch (e: Exception) {
                    android.util.Log.w(logTag, "Failed to remove item at index $index", e)
                }
            }

            // Reset play-next count in the store
            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch { qs.setPlayNextCount(0) }
            }

            updateUIState()
            _queueChanges.postValue(QueueChangeEvent.QueueCleared(keepCurrent))

            android.util.Log.d(logTag, "clearTransientQueues completed: removed=$removedCount newTotal=${player.mediaItemCount} wasPlaying=$wasPlaying")

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error clearing transient queues", e)
        }
    }

    suspend fun clearQueue(keepCurrent: Boolean = false) = queueMutex.withLock {
        try {
            android.util.Log.d(logTag, "clearQueue keepCurrent=$keepCurrent total=${getQueueSize()}")
            if (keepCurrent && currentIndex >= 0) {
                val currentItem = getCombinedQueue().getOrNull(currentIndex)

                // Clear everything
                mainList.clear()
                priorityList.clear()
                userList.clear()
                queueLookup.clear()

                // Keep only current item
                currentItem?.let { item ->
                    mainList.add(item)
                    queueLookup[item.id] = item
                }

                currentIndex = 0
            } else {
                mainList.clear()
                priorityList.clear()
                userList.clear()
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

    fun getQueueSnapshot(): List<QueueItem> = try {
        getCombinedQueue().toList()
    } catch (e: Exception) {
        android.util.Log.e("QueueManager", "Error getting queue snapshot", e)
        emptyList()
    }

    fun getQueueSize(): Int = try { onPlayerThread { player.mediaItemCount } } catch (_: Exception) { mainList.size + priorityList.size + userList.size }

    fun getCurrentIndex(): Int = currentIndex

    fun hasNext(): Boolean = currentIndex < getQueueSize() - 1

    fun hasPrevious(): Boolean = currentIndex > 0

    fun getCurrentItem(): QueueItem? {
        val current = getCombinedQueue().getOrNull(currentIndex)
        _currentItem.postValue(current)
        return current
    }

    fun isPlayNextIndex(index: Int): Boolean {
        return getCombinedQueue().getOrNull(index)?.source == QueueSource.PLAY_NEXT
    }

    suspend fun removeFirstPlayNextByMediaId(mediaId: String): Boolean = queueMutex.withLock {
        val idx = getCombinedQueue().indexOfFirst { it.source == QueueSource.PLAY_NEXT && it.mediaItem.mediaId == mediaId }
        return@withLock if (idx >= 0) removeAtInternal(idx) else false
    }

    suspend fun removeFirstUserQueueByMediaId(mediaId: String): Boolean = queueMutex.withLock {
        // Find the first UserQueue item with matching mediaId in the combined queue
        val combinedQueue = getCombinedQueue()
        val userQueueIndex = combinedQueue.indexOfFirst {
            it.source == QueueSource.USER_QUEUE && it.mediaItem.mediaId == mediaId
        }

        return@withLock if (userQueueIndex >= 0) {
            android.util.Log.d(logTag, "removeUserQueue found matching item id=$mediaId at idx=$userQueueIndex")
            removeAtInternal(userQueueIndex)
            true
        } else {
            android.util.Log.d(logTag, "removeUserQueue no matching UserQueue item found for id=$mediaId")
            false
        }
    }

    /**
     * Remove the first PlayNext item that matches the finished mediaId, regardless of position.
     * This ensures PlayNext items are consumed after being played, even if there are duplicates.
     */
    suspend fun consumePlayNextHeadIfMatches(finishedMediaId: String): Boolean = queueMutex.withLock {
        // Find the first PlayNext item with matching mediaId in the combined queue
        val combinedQueue = getCombinedQueue()
        val playNextIndex = combinedQueue.indexOfFirst {
            it.source == QueueSource.PLAY_NEXT && it.mediaItem.mediaId == finishedMediaId
        }

        return@withLock if (playNextIndex >= 0) {
            android.util.Log.d(logTag, "consumePlayNext found matching item id=$finishedMediaId at idx=$playNextIndex")
            removeAtInternal(playNextIndex)
            true
        } else {
            android.util.Log.d(logTag, "consumePlayNext no matching PlayNext item found for id=$finishedMediaId")
            false
        }
    }

    /**
     * Synchronize internal queues from the player's current media items.
     * This is used when a controller externally sets a new playlist so our
     * state reflects the true player timeline.
     */
    suspend fun syncFromPlayer(newPlayer: ExoPlayer) = queueMutex.withLock {
        try {
            val count = newPlayer.mediaItemCount
            val items = (0 until count).mapNotNull { idx -> newPlayer.getMediaItemAt(idx) }
            // Preserve existing sources/metadata by matching existing combined queue by mediaId (in order)
            val existingCombined = getCombinedQueue().toMutableList()
            val buckets = LinkedHashMap<String, ArrayDeque<QueueItem>>()
            existingCombined.forEach { qi ->
                val dq = buckets.getOrPut(qi.id) { ArrayDeque() }
                dq.addLast(qi)
            }

            mainList.clear()
            priorityList.clear()
            userList.clear()
            queueLookup.clear()
            originalOrder.clear()

            items.forEachIndexed { index, mediaItem ->
                val preserved = buckets[mediaItem.mediaId]?.removeFirstOrNull()
                val qi = preserved?.copy(mediaItem = mediaItem, position = index)
                    ?: QueueItem(
                        mediaItem = mediaItem,
                        position = index,
                        source = QueueSource.USER_ADDED,
                        context = currentContext
                    )
                when (qi.source) {
                    QueueSource.PLAY_NEXT -> priorityList.add(qi)
                    QueueSource.USER_QUEUE -> userList.add(qi)
                    else -> mainList.add(qi)
                }
                queueLookup[qi.id] = qi
                originalOrder.addLast(qi.copy())
            }

            currentIndex = newPlayer.currentMediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            shuffleEnabled = newPlayer.shuffleModeEnabled
            repeatMode = newPlayer.repeatMode

            updateUIState()
            _queueChanges.postValue(QueueChangeEvent.QueueReordered(getCombinedQueue()))
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Failed to sync from player", e)
        }
    }

    fun getVisibleQueue(): List<QueueItem> {
        // Build from authoritative player timeline
        val combined = getCombinedQueue()
        val start = (currentIndex + 1).coerceAtMost((combined.size))
        val visible = if (start < combined.size) combined.drop(start) else emptyList()
        android.util.Log.d(logTag, "getVisibleQueue currentIndex=$currentIndex total=${combined.size} visible=${visible.size}")
        return visible
    }

    /**
     * Get the index mapping from visible queue position to combined queue position
     */
    fun getVisibleToCombinedIndexMapping(visibleIndex: Int): Int {
        val visibleQueue = getVisibleQueue()
        if (visibleIndex < 0 || visibleIndex >= visibleQueue.size) return -1

        val item = visibleQueue[visibleIndex]
        val combinedQueue = getCombinedQueue()
        // Prefer identity match to handle duplicates reliably
        val identityIdx = combinedQueue.indexOfFirst { it === item }
        if (identityIdx >= 0) return identityIdx
        // Fallback to stable UID match if identity is not preserved
        return combinedQueue.indexOfFirst { it.uid == item.uid }
    }

    // Private helper methods

    private fun <T> onPlayerThread(block: () -> T): T {
        val appLooper = player.applicationLooper
        return if (Looper.myLooper() == appLooper) {
            block()
        } else {
            val latch = CountDownLatch(1)
            var result: T? = null
            Handler(appLooper).post {
                try {
                    result = block()
                } finally {
                    latch.countDown()
                }
            }
            try { latch.await() } catch (_: InterruptedException) { }
            @Suppress("UNCHECKED_CAST")
            (result as T)
        }
    }

    private fun getCombinedQueue(): List<QueueItem> {
        // Construct the combined queue directly from the player's timeline to ensure accurate ordering
        return try {
            // Read a consistent snapshot of the player's items on the player thread
            val playerItems: List<MediaItem> = onPlayerThread {
                val size = player.mediaItemCount
                if (size <= 0) emptyList() else buildList(size) {
                    for (i in 0 until size) {
                        val mi = try { player.getMediaItemAt(i) } catch (_: Exception) { null }
                        if (mi != null) add(mi)
                    }
                }
            }
            if (playerItems.isEmpty()) {
                // Fallback to local lists if player is empty
                val fallback = mutableListOf<QueueItem>()
                fallback.addAll(mainList)
                fallback.addAll(priorityList)
                fallback.addAll(userList)
                fallback
            } else {
                val buckets = LinkedHashMap<String, ArrayDeque<QueueItem>>()
                fun addToBuckets(list: List<QueueItem>) {
                    list.forEach { qi ->
                        val dq = buckets.getOrPut(qi.id) { ArrayDeque() }
                        dq.addLast(qi)
                    }
                }
                // Collect in a deterministic order: priority, user, then main
                // to prefer mapping current queued items before any stale copies
                addToBuckets(priorityList)
                addToBuckets(userList)
                addToBuckets(mainList)

                val result = mutableListOf<QueueItem>()
                for ((index, mi) in playerItems.withIndex()) {
                    val match = buckets[mi.mediaId]?.removeFirstOrNull()
                    if (match != null) {
                        // Keep reference identity to support === mapping
                        result.add(match)
                    } else {
                        // Fallback: synthesize a minimal QueueItem preserving metadata
                        result.add(
                            QueueItem(
                                mediaItem = mi,
                                position = index,
                                source = QueueSource.USER_ADDED,
                                context = currentContext
                            )
                        )
                    }
                }
                result
            }
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error building combined queue from player", e)
            // Safe fallback
            (mainList + priorityList + userList).toList()
        }
    }

    private fun updateQueueAfterMove(from: Int, to: Int) {
        // Efficient move using lists: rebuild from combined order
        val combinedQueue = getCombinedQueue().toMutableList()
        if (from < combinedQueue.size && to < combinedQueue.size) {
            val item = combinedQueue.removeAt(from)
            combinedQueue.add(to, item)
            rebuildQueuesFromCombined(combinedQueue)
        }
    }

    private fun rebuildQueuesFromCombined(combinedQueue: List<QueueItem>) {
        mainList.clear()
        priorityList.clear()
        userList.clear()

        combinedQueue.forEach { item ->
            when (item.source) {
                QueueSource.PLAY_NEXT -> priorityList.add(item)
                QueueSource.USER_QUEUE -> userList.add(item)
                else -> mainList.add(item)
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

        mainList.clear()
        priorityList.clear()
        userList.clear()

        originalOrder.forEach { item ->
            when (item.source) {
                QueueSource.PLAY_NEXT -> priorityList.add(item)
                QueueSource.USER_QUEUE -> userList.add(item)
                else -> mainList.add(item)
            }
        }

        // Find current item position in original order
        val currentItem = getCurrentItem()
        currentIndex = originalOrder.indexOfFirst { it.id == currentItem?.id }
            .coerceAtLeast(0)
    }

    private fun buildCombinedFromInternalListsWithCurrent(): Pair<List<QueueItem>, Int> {
        val mainAll = mainList.toList()
        val current = getCurrentItem()
        val currentInMain = current?.let { c -> mainAll.indexOfFirst { it.id == c.id && it.addedAt == c.addedAt } }
            ?: -1

        val combined = mutableListOf<QueueItem>()
        if (currentInMain >= 0) {
            // Up to and including current main item
            combined.addAll(mainAll.take(currentInMain + 1))
        } else if (current != null) {
            // Current item not in main; ensure it is at the head of combined
            combined.add(current)
        }
        // Insert priority and user segments next
        combined.addAll(priorityList)
        combined.addAll(userList)
        // Append the remainder of main items if current belongs to main
        if (currentInMain + 1 < mainAll.size && currentInMain >= 0) {
            combined.addAll(mainAll.drop(currentInMain + 1))
        } else if (currentInMain < 0) {
            // Current not in main, append all main items
            combined.addAll(mainAll)
        }

        val newCurrentIndex = combined.indexOfFirst { it.id == current?.id && it.addedAt == current?.addedAt }
            .let { if (it >= 0) it else 0 }
        return combined to newCurrentIndex
    }

    private fun updateUIState() {
        val state = QueueState(
            totalItems = getQueueSize(),
            currentIndex = currentIndex,
            hasNext = hasNext(),
            hasPrevious = hasPrevious(),
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            playNextCount = priorityList.size,
            userQueueCount = userList.size,
            context = currentContext
        )

        _queueState.postValue(state)
        _queueStateFlow.value = state
        android.util.Log.d(logTag, "updateUIState total=${state.totalItems} curIdx=${state.currentIndex} pri=${state.playNextCount} user=${state.userQueueCount}")
    }

    // Called when track changes to update history
    fun onTrackChanged(mediaId: String) {
        // Sync our current index with the player's current index to keep trimming and visibility accurate
        val playerIndex = try {
            onPlayerThread { player.currentMediaItemIndex }
        } catch (_: Exception) { -1 }
        if (playerIndex >= 0) {
            currentIndex = playerIndex.coerceIn(0, (getQueueSize() - 1).coerceAtLeast(0))
        }
        shuffleHistory.addFirst(mediaId)
        if (shuffleHistory.size > maxShuffleHistory) {
            shuffleHistory.removeLast()
        }
        getCurrentItem()
        // Trim all items that have already been played from the front of the combined queue
        // Keep them out of the visible queue but we still preserve originalOrder for restoration/shuffle context
        CoroutineScope(Dispatchers.IO).launch {
            try { trimPlayedBeforeCurrent() } catch (_: Exception) {}
        }
        android.util.Log.d(logTag, "onTrackChanged mediaId=$mediaId currentIndex=$currentIndex total=${getQueueSize()}")
    }

    /**
     * Enhanced intelligent trimming - removes played songs while preserving user choices
     * Only removes items that were actually played, not just items before current index
     */
    private suspend fun trimPlayedBeforeCurrent() = queueMutex.withLock {
        val idx = currentIndex
        if (idx <= 0) return@withLock

        try {
            android.util.Log.d(logTag, "trimPlayedBeforeCurrent: checking $idx played items for removal")

            val combinedQueue = getCombinedQueue()
            val itemsToCheck = combinedQueue.take(idx)

            // Only remove transient items (PlayNext/UserQueue) that have been played
            // Keep main queue items as they represent the user's chosen playlist/album
            var removedCount = 0
            val itemsToRemove = itemsToCheck.filter { item ->
                item.source == QueueSource.PLAY_NEXT || item.source == QueueSource.USER_QUEUE
            }

            // Remove played transient items from the front
            itemsToRemove.forEach { item ->
                val itemIndex = getCombinedQueue().indexOfFirst { it.uid == item.uid }
                if (itemIndex >= 0 && itemIndex < currentIndex) {
                    val success = removeAtInternal(itemIndex)
                    if (success) {
                        removedCount++
                    }
                }
            }

            // Update current index after removals
            currentIndex = (idx - removedCount).coerceAtLeast(0)

            // Notify about cleanup
            if (removedCount > 0) {
                _queueChanges.postValue(QueueChangeEvent.QueueCleanup(removedCount, itemsToCheck.size - removedCount))
            }

            updateUIState()
            android.util.Log.d(logTag, "trimPlayedBeforeCurrent completed: removed=$removedCount newCurrentIndex=$currentIndex")

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error during intelligent trimming", e)
            updateUIState()
        }
    }

    // Operation queue methods for external use

    /**
     * Queue an add operation for background processing (for bulk operations)
     */
    fun queueAdd(items: List<QueueItem>, position: Int = -1) {
        val sent = operationChannel.trySend(QueueOperation.Add(items, position)).isSuccess
        if (sent) {
            _pendingOperations.update { it + 1 }
        }
    }

    /**
     * Get pending operation count for UI feedback
     */
    fun getPendingOperationCount(): Int = pendingOperations.value

    /**
     * Enhanced queue isolation methods for Spotify-like behavior
     */

    /**
     * Update source playlist without affecting isolated queue items (Priority/User queues)
     */
    suspend fun updateSourcePlaylist(
        newItems: List<MediaItem>,
        sourceId: String,
        preserveCurrentPosition: Boolean = true
    ) = queueMutex.withLock {
        try {
            android.util.Log.d(logTag, "updateSourcePlaylist sourceId=$sourceId newItems=${newItems.size}")

            // Only update main queue items that match the source and are not isolated
            val currentItem = if (preserveCurrentPosition) getCurrentItem() else null
            val isolatedItems = mutableListOf<QueueItem>()

            // Collect all isolated items (priority + user queues)
            isolatedItems.addAll(priorityList.filter { it.isIsolated })
            isolatedItems.addAll(userList.filter { it.isIsolated })

            // Filter main list to keep only isolated items or items from different sources
            val preservedMainItems = mainList.filter { item ->
                item.isIsolated || (item.context?.id != sourceId)
            }

            // Create new main items from the updated source
            val baseTime = System.currentTimeMillis()
            val newMainItems = newItems.mapIndexed { index, mediaItem ->
                QueueItem(
                    mediaItem = mediaItem,
                    addedAt = baseTime + index,
                    position = index,
                    source = when (currentContext?.type) {
                        ContextType.ALBUM -> QueueSource.ALBUM
                        ContextType.PLAYLIST -> QueueSource.PLAYLIST
                        ContextType.LIKED_SONGS -> QueueSource.LIKED_SONGS
                        else -> QueueSource.USER_ADDED
                    },
                    context = currentContext,
                    isIsolated = false, // Main queue items are not isolated
                    originalSourceId = sourceId
                )
            }

            // Rebuild main list
            mainList.clear()
            mainList.addAll(preservedMainItems)
            mainList.addAll(newMainItems)

            // Update lookup table
            queueLookup.clear()
            mainList.forEach { queueLookup[it.id] = it }
            priorityList.forEach { queueLookup[it.id] = it }
            userList.forEach { queueLookup[it.id] = it }

            // Restore current position if requested
            if (preserveCurrentPosition && currentItem != null) {
                val newCurrentIndex = getCombinedQueue().indexOfFirst { it.id == currentItem.id }
                if (newCurrentIndex >= 0) {
                    currentIndex = newCurrentIndex
                }
            }

            // Update player timeline
            val combinedQueue = getCombinedQueue()
            val mediaItems = combinedQueue.map { it.mediaItem }
            player.setMediaItems(mediaItems, currentIndex, 0L)

            updateUIState()
            _queueChanges.postValue(QueueChangeEvent.QueueReordered(combinedQueue))

            android.util.Log.d(logTag, "updateSourcePlaylist completed: main=${mainList.size} total=${getQueueSize()}")

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error updating source playlist", e)
        }
    }

    /**
     * Remove all items from a specific source while preserving isolated items
     */
    suspend fun removeItemsFromSource(sourceId: String) = queueMutex.withLock {
        try {
            android.util.Log.d(logTag, "removeItemsFromSource sourceId=$sourceId")

            val removedItems = mutableListOf<QueueItem>()

            // Remove from main list (only non-isolated items from this source)
            val mainIterator = mainList.iterator()
            while (mainIterator.hasNext()) {
                val item = mainIterator.next()
                if (!item.isIsolated && item.originalSourceId == sourceId) {
                    mainIterator.remove()
                    queueLookup.remove(item.id)
                    removedItems.add(item)
                }
            }

            // Update player if items were removed
            if (removedItems.isNotEmpty()) {
                val combinedQueue = getCombinedQueue()
                val mediaItems = combinedQueue.map { it.mediaItem }
                player.setMediaItems(mediaItems, currentIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)), 0L)

                updateUIState()
                removedItems.forEach { item ->
                    _queueChanges.postValue(QueueChangeEvent.ItemRemoved(item, -1))
                }
            }

            android.util.Log.d(logTag, "removeItemsFromSource completed: removed=${removedItems.size}")

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error removing items from source", e)
        }
    }

    /**
     * Get queue statistics for debugging and UI display
     */
    // Public methods to get queue items for state saving
    fun getPlayNextQueue(): List<String> = priorityList.map { it.id }
    fun getUserQueue(): List<String> = userList.map { it.id }

    fun getQueueStatistics(): QueueStatistics {
        val combined = getCombinedQueue()
        return QueueStatistics(
            totalItems = combined.size,
            priorityItems = priorityList.size,
            userQueueItems = userList.size,
            mainItems = mainList.size,
            isolatedItems = combined.count { it.isIsolated },
            sourcesCount = combined.mapNotNull { it.originalSourceId }.distinct().size,
            currentIndex = currentIndex,
            hasNext = hasNext(),
            hasPrevious = hasPrevious()
        )
    }

    data class QueueStatistics(
        val totalItems: Int,
        val priorityItems: Int,
        val userQueueItems: Int,
        val mainItems: Int,
        val isolatedItems: Int,
        val sourcesCount: Int,
        val currentIndex: Int,
        val hasNext: Boolean,
        val hasPrevious: Boolean
    )
}
