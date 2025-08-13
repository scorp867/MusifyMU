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
import kotlin.random.Random

/**
 * Spotify-like Advanced Queue Manager with context-aware queue management
 * Phase 1 Implementation: Context switching, Smart shuffle, Visual feedback
 */
class QueueManager(private val player: ExoPlayer, private val queueState: QueueStateStore? = null) {

    // Multi-layered queue system like Spotify
    private val userQueue = ArrayDeque<QueueItem>()           // "Play Next" items
    private val contextQueue = ArrayDeque<QueueItem>()        // Current album/playlist context
    private val radioQueue = ArrayDeque<QueueItem>()          // Auto-generated similar songs

    // Queue context tracking
    private var currentContext: QueueContext? = null
    private var shuffleHistory = mutableListOf<String>()
    private var shuffleUpcoming = ArrayDeque<QueueItem>()

    // Smart shuffle state
    private var originalContextOrder = ArrayDeque<QueueItem>()
    private var isSmartShuffleEnabled = false
    private val smartShuffle = SmartShuffleAlgorithm()

    // Thread safety
    private val queueMutex = Mutex()

    // Visual feedback state
    private var draggedItemIndex: Int? = null
    private var isDragging = false

    data class QueueItem(
        val mediaItem: MediaItem,
        val id: String = mediaItem.mediaId,
        val addedAt: Long = System.currentTimeMillis(),
        val source: QueueSource = QueueSource.CONTEXT,
        var position: Int = -1,
        val contextId: String? = null,
        val isUserAdded: Boolean = false
    )

    data class QueueContext(
        val type: ContextType,
        val id: String,
        val name: String,
        val originalOrder: List<QueueItem>,
        val startIndex: Int = 0
    )

    enum class ContextType {
        ALBUM, PLAYLIST, ARTIST, LIKED_SONGS, SEARCH, QUEUE, RADIO
    }

    enum class QueueSource {
        CONTEXT,        // From album/playlist
        USER_NEXT,      // User added "Play Next"
        USER_QUEUE,     // User added "Add to Queue"
        RADIO,          // Auto-generated
        SHUFFLE         // Shuffled items
    }

    // Visual feedback data class
    data class QueueVisualState(
        val isDragging: Boolean = false,
        val draggedIndex: Int? = null,
        val dragOffset: Float = 0f,
        val targetIndex: Int? = null
    )

    private var visualState = QueueVisualState()

    /**
     * Play from a specific context (album, playlist, etc.) - Spotify-like behavior
     */
    suspend fun playFromContext(
        context: QueueContext,
        startIndex: Int = 0,
        play: Boolean = true,
        shuffle: Boolean = false
    ) = queueMutex.withLock {
        try {
            // Clear existing state
            userQueue.clear()
            contextQueue.clear()
            radioQueue.clear()
            shuffleHistory.clear()
            shuffleUpcoming.clear()

            // Set new context
            currentContext = context
            originalContextOrder.clear()
            originalContextOrder.addAll(context.originalOrder)

            if (shuffle) {
                enableSmartShuffle(context.originalOrder, startIndex)
            } else {
                contextQueue.addAll(context.originalOrder)
                isSmartShuffleEnabled = false
            }

            // Build combined queue for player
            val combinedQueue = buildCombinedQueue()
            val playerStartIndex = if (shuffle) 0 else startIndex

            // Set media items in player
            player.setMediaItems(combinedQueue.map { it.mediaItem }, playerStartIndex, 0L)
            player.prepare()

            if (play) player.play()

            // Update state store
            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch {
                    qs.setPlayNextCount(userQueue.size)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error playing from context", e)
        }
    }

    /**
     * Add items to user queue (Play Next) - these take priority
     */
    suspend fun addToUserQueue(items: List<MediaItem>, playNext: Boolean = true) = queueMutex.withLock {
        try {
            val queueItems = items.map { mediaItem ->
                QueueItem(
                    mediaItem = mediaItem,
                    source = if (playNext) QueueSource.USER_NEXT else QueueSource.USER_QUEUE,
                    isUserAdded = true,
                    contextId = currentContext?.id
                )
            }

            if (playNext) {
                // Add to front of user queue (LIFO for multiple items)
                queueItems.reversed().forEach { userQueue.addFirst(it) }
            } else {
                // Add to end of user queue
                queueItems.forEach { userQueue.addLast(it) }
            }

            // Rebuild player queue
            updatePlayerQueue()

            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch {
                    qs.setPlayNextCount(userQueue.size)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error adding to user queue", e)
        }
    }

    /**
     * Smart shuffle implementation - avoids recently played, spreads artists
     */
    private suspend fun enableSmartShuffle(items: List<QueueItem>, currentIndex: Int) {
        isSmartShuffleEnabled = true
        val shuffledItems = smartShuffle.generateSmartShuffle(
            items = items,
            currentIndex = currentIndex,
            recentlyPlayed = shuffleHistory
        )

        contextQueue.clear()
        contextQueue.addAll(shuffledItems)

        // Track shuffle history
        shuffleHistory.clear()
        shuffleUpcoming.clear()
        shuffleUpcoming.addAll(shuffledItems.drop(1)) // All except current
    }

    /**
     * Enhanced move with visual feedback
     */
    suspend fun moveWithVisualFeedback(
        from: Int,
        to: Int,
        onVisualUpdate: (QueueVisualState) -> Unit
    ) = queueMutex.withLock {
        try {
            // Update visual state
            visualState = visualState.copy(
                isDragging = true,
                draggedIndex = from,
                targetIndex = to
            )
            onVisualUpdate(visualState)

            // Perform the actual move
            move(from, to)

            // Reset visual state
            visualState = QueueVisualState()
            onVisualUpdate(visualState)

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error moving with visual feedback", e)
            visualState = QueueVisualState()
            onVisualUpdate(visualState)
        }
    }

    /**
     * Standard move operation
     */
    suspend fun move(from: Int, to: Int) = queueMutex.withLock {
        try {
            if (from == to || from < 0 || to < 0) return@withLock

            val combinedQueue = buildCombinedQueue().toMutableList()
            if (from >= combinedQueue.size || to >= combinedQueue.size) return@withLock

            val item = combinedQueue.removeAt(from)
            combinedQueue.add(to, item)

            // Rebuild internal queues
            rebuildQueuesFromCombined(combinedQueue)

            // Move in player
            if (from < player.mediaItemCount && to < player.mediaItemCount) {
                player.moveMediaItem(from, to)
            }

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error moving item", e)
        }
    }

    /**
     * Remove item with context awareness
     */
    suspend fun removeAt(index: Int) = queueMutex.withLock {
        try {
            val combinedQueue = buildCombinedQueue()
            if (index < 0 || index >= combinedQueue.size) return@withLock

            val item = combinedQueue[index]

            // Remove from appropriate queue
            when {
                userQueue.contains(item) -> userQueue.remove(item)
                contextQueue.contains(item) -> {
                    contextQueue.remove(item)
                    // If removing from context, might need to add more items
                    if (contextQueue.size < 5 && radioQueue.isNotEmpty()) {
                        // Move some radio items to context
                        repeat(minOf(3, radioQueue.size)) {
                            radioQueue.removeFirstOrNull()?.let { contextQueue.addLast(it) }
                        }
                    }
                }
                radioQueue.contains(item) -> radioQueue.remove(item)
            }

            // Remove from player
            if (index < player.mediaItemCount) {
                player.removeMediaItem(index)
            }

            updateStateStore()

        } catch (e: Exception) {
            android.util.Log.e("QueueManager", "Error removing item", e)
        }
    }

    /**
     * Get queue sections for UI display
     */
    fun getQueueSections(): QueueSections {
        return QueueSections(
            nowPlaying = getCurrentItem(),
            userQueue = userQueue.toList(),
            contextQueue = contextQueue.take(10), // Limit preview
            contextName = currentContext?.name ?: "Queue",
            hasMoreInContext = contextQueue.size > 10,
            radioQueue = radioQueue.take(5)
        )
    }

    /**
     * Build combined queue in priority order
     */
    private fun buildCombinedQueue(): List<QueueItem> {
        val combined = mutableListOf<QueueItem>()

        // Add user queue first (highest priority)
        combined.addAll(userQueue)

        // Add context queue
        combined.addAll(contextQueue)

        // Add radio queue if context is empty
        if (contextQueue.isEmpty()) {
            combined.addAll(radioQueue)
        }

        return combined
    }

    /**
     * Update player queue after internal changes
     */
    private suspend fun updatePlayerQueue() {
        val combinedQueue = buildCombinedQueue()
        val currentIndex = player.currentMediaItemIndex

        // Clear and rebuild player queue
        player.clearMediaItems()
        if (combinedQueue.isNotEmpty()) {
            player.setMediaItems(
                combinedQueue.map { it.mediaItem },
                currentIndex.coerceIn(0, combinedQueue.size - 1),
                player.currentPosition
            )
        }
    }

    /**
     * Rebuild internal queues from combined list
     */
    private fun rebuildQueuesFromCombined(combinedQueue: List<QueueItem>) {
        userQueue.clear()
        contextQueue.clear()
        radioQueue.clear()

        combinedQueue.forEach { item ->
            when (item.source) {
                QueueSource.USER_NEXT, QueueSource.USER_QUEUE -> userQueue.addLast(item)
                QueueSource.CONTEXT, QueueSource.SHUFFLE -> contextQueue.addLast(item)
                QueueSource.RADIO -> radioQueue.addLast(item)
            }
        }
    }

    private fun updateStateStore() {
        queueState?.let { qs ->
            CoroutineScope(Dispatchers.IO).launch {
                qs.setPlayNextCount(userQueue.size)
            }
        }
    }

    // Existing methods with improvements
    fun getCurrentItem(): QueueItem? =
        buildCombinedQueue().getOrNull(player.currentMediaItemIndex)

    fun getQueueSize(): Int = buildCombinedQueue().size

    fun hasNext(): Boolean = player.currentMediaItemIndex < getQueueSize() - 1

    fun hasPrevious(): Boolean = player.currentMediaItemIndex > 0

    fun snapshotIds(): List<String> = buildCombinedQueue().map { it.id }

    fun setRepeat(mode: Int) {
        player.repeatMode = mode
    }

    suspend fun setShuffle(enabled: Boolean) = queueMutex.withLock {
        currentContext?.let { context ->
            if (enabled && !isSmartShuffleEnabled) {
                enableSmartShuffle(context.originalOrder, player.currentMediaItemIndex)
                updatePlayerQueue()
            } else if (!enabled && isSmartShuffleEnabled) {
                // Restore original order
                contextQueue.clear()
                contextQueue.addAll(originalContextOrder)
                isSmartShuffleEnabled = false
                updatePlayerQueue()
            }
        }
        player.shuffleModeEnabled = enabled
    }

    data class QueueSections(
        val nowPlaying: QueueItem?,
        val userQueue: List<QueueItem>,
        val contextQueue: List<QueueItem>,
        val contextName: String,
        val hasMoreInContext: Boolean,
        val radioQueue: List<QueueItem>
    )
}

/**
 * Smart Shuffle Algorithm - avoids repetition and creates better flow
 */
class SmartShuffleAlgorithm {
    fun generateSmartShuffle(
        items: List<QueueManager.QueueItem>,
        currentIndex: Int,
        recentlyPlayed: List<String>
    ): List<QueueManager.QueueItem> {
        if (items.size <= 1) return items

        val current = items.getOrNull(currentIndex)
        val remaining = items.toMutableList()
        current?.let { remaining.remove(it) }

        // Shuffle with smart distribution
        val shuffled = remaining.shuffled().toMutableList()

        // Optimize order to avoid artist/album clustering
        optimizeArtistDistribution(shuffled)

        // Avoid recently played songs in next few positions
        avoidRecentlyPlayed(shuffled, recentlyPlayed)

        // Build final list with current item first
        return listOfNotNull(current) + shuffled
    }

    private fun optimizeArtistDistribution(items: MutableList<QueueManager.QueueItem>) {
        // Spread out songs from same artist
        val artistGroups = items.groupBy { extractArtist(it.mediaItem) }

        artistGroups.values.filter { it.size > 1 }.forEach { artistSongs ->
            // Redistribute songs from same artist
            val indices = artistSongs.map { items.indexOf(it) }.sorted()
            val redistributed = redistributeEvenly(indices, items.size)

            redistributed.forEachIndexed { i, newIndex ->
                val oldIndex = indices[i]
                if (oldIndex != newIndex && newIndex < items.size) {
                    val item = items.removeAt(oldIndex)
                    items.add(newIndex.coerceIn(0, items.size), item)
                }
            }
        }
    }

    private fun avoidRecentlyPlayed(
        items: MutableList<QueueManager.QueueItem>,
        recentlyPlayed: List<String>
    ) {
        val recentIds = recentlyPlayed.take(5).toSet()

        // Move recently played songs away from the beginning
        items.filter { it.id in recentIds }.forEach { recentItem ->
            val currentIndex = items.indexOf(recentItem)
            if (currentIndex < 3) { // If in first 3 positions
                items.removeAt(currentIndex)
                val newIndex = (items.size * 0.3).toInt().coerceAtLeast(3)
                items.add(newIndex.coerceIn(3, items.size), recentItem)
            }
        }
    }

    private fun redistributeEvenly(indices: List<Int>, totalSize: Int): List<Int> {
        if (indices.size <= 1) return indices

        val step = totalSize / indices.size
        return indices.mapIndexed { i, _ ->
            (i * step + Random.nextInt(step / 2)).coerceIn(0, totalSize - 1)
        }
    }

    private fun extractArtist(mediaItem: MediaItem): String {
        return mediaItem.mediaMetadata.artist?.toString() ?: "Unknown"
    }
}
