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
        val mediaItem: MediaItem,
        val id: String = mediaItem.mediaId,
        val addedAt: Long = System.currentTimeMillis(),
        val source: QueueSource = QueueSource.USER_ADDED,
        var position: Int = -1,
        val context: PlayContext? = null
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
    suspend fun addToUserQueue(items: MutableList<MediaItem>, context: PlayContext? = null) = queueMutex.withLock {
        try {
            android.util.Log.d(logTag, "addToUserQueue start items=${items.size} context=$context before user=${userList.size}")
            val baseTime = System.currentTimeMillis()
            var offset = 0
            val queueItems = items.mapNotNull { mediaItem ->
                // Prevent duplicates
                if (queueLookup.containsKey(mediaItem.mediaId)) {
                    android.util.Log.d(logTag, "addToUserQueue skip duplicate id=${mediaItem.mediaId}")
                    null
                } else {
                    QueueItem(
                        mediaItem = mediaItem,
                        addedAt = baseTime + (offset++),
                        position = mainList.size + priorityList.size + userList.size,
                        source = QueueSource.USER_QUEUE,
                        context = context ?: currentContext
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
                    context = context ?: currentContext
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

        // Remove from appropriate queue
        when (item.source) {
            QueueSource.PLAY_NEXT -> {
                priorityList.remove(item)
                queueState?.let { qs ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val current = qs.getPlayNextCount()
                        qs.setPlayNextCount((current - 1).coerceAtLeast(0))
                    }
                }
            }
            QueueSource.USER_QUEUE -> userList.remove(item)
            else -> mainList.remove(item)
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
        return item.source == QueueSource.PLAY_NEXT
    }

    suspend fun removeAt(index: Int) = queueMutex.withLock {
        try {
            removeAtInternal(index)
        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error removing item", e)
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

    fun getQueueSize(): Int = mainList.size + priorityList.size + userList.size

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

    /**
     * Consume the head of the play-next queue only if it matches the finished mediaId.
     * Returns true if a play-next head was consumed and removed from player timeline.
     */
    suspend fun consumePlayNextHeadIfMatches(finishedMediaId: String): Boolean = queueMutex.withLock {
        val head = priorityList.firstOrNull() ?: return@withLock false
        if (head.mediaItem.mediaId != finishedMediaId) return@withLock false

        // Find the head in the combined order (it should appear right after current main item)
        val idx = getCombinedQueue().indexOfFirst { it === head }
        return@withLock if (idx >= 0) {
            android.util.Log.d(logTag, "consumePlayNext matched head id=$finishedMediaId at idx=$idx")
            removeAtInternal(idx)
        } else run {
            // Fallback: if not found in combined (rare), still pop head and update state
            // Remove the head from list manually
            priorityList.remove(head)
            queueLookup.remove(head.id)
            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch {
                    val current = qs.getPlayNextCount()
                    qs.setPlayNextCount((current - 1).coerceAtLeast(0))
                }
            }
            updateUIState()
            android.util.Log.d(logTag, "consumePlayNext fallback pop head id=$finishedMediaId newPri=${priorityList.size}")
            true
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
        // Visible queue starts from item after current and forward
        // List shows Priority (Play Next) items first, then User Queue, then remainder of main after current
        val mainAll = mainList.toList()
        val start = (currentIndex + 1).coerceAtMost(mainAll.size)
        val visible = mutableListOf<QueueItem>()
        visible.addAll(priorityList.toList())
        visible.addAll(userList.toList())
        if (start < mainAll.size) visible.addAll(mainAll.drop(start))
        android.util.Log.d(logTag, "getVisibleQueue pri=${priorityList.size} user=${userList.size} mainTail=${(mainAll.size - start).coerceAtLeast(0)} visible=${visible.size}")
        return visible
    }

    // Private helper methods

    private fun getCombinedQueue(): List<QueueItem> {
        val combined = mutableListOf<QueueItem>()

        val mainAll = mainList.toList()
        val currentInMain = currentIndex.coerceAtMost(mainAll.size - 1)
        if (currentInMain >= 0) combined.addAll(mainAll.take(currentInMain + 1))
        combined.addAll(priorityList.toList())
        combined.addAll(userList.toList())
        if (currentInMain + 1 < mainAll.size) combined.addAll(mainAll.drop(currentInMain + 1))
        return combined
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
    
    private suspend fun trimPlayedBeforeCurrent() = queueMutex.withLock {
        val idx = currentIndex
        if (idx <= 0) return@withLock
        // Remove items at the head repeatedly; this updates player timeline and internal lists
        repeat(idx) { removeAtInternal(0) }
        currentIndex = 0
        updateUIState()
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
}
