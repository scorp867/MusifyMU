package com.musify.mu.playback

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.platform.LocalLifecycleOwner

/**
 * Compose provider for QueueManager integration
 * Provides reactive access to queue state and operations
 */
object QueueManagerProvider {
    private var instance: QueueManager? = null
    
    fun setInstance(queueManager: QueueManager) {
        instance = queueManager
    }
    
    fun getInstance(): QueueManager? = instance
}

/**
 * Composable to access QueueManager instance
 */
@Composable
fun LocalQueueManager(): QueueManager? {
    return QueueManagerProvider.getInstance()
}

/**
 * Composable to observe QueueManager state changes using LiveData
 */
@Composable
fun rememberQueueState(): State<QueueManager.QueueState> {
    val queueManager = LocalQueueManager()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var queueState by remember { mutableStateOf(QueueManager.QueueState()) }
    
    LaunchedEffect(queueManager, lifecycleOwner) {
        queueManager?.queueStateLiveData?.observe(lifecycleOwner, Observer { state ->
            queueState = state
        })
    }
    
    return remember { derivedStateOf { queueState } }
}

/**
 * Composable to observe QueueManager state changes using StateFlow (alternative)
 */
@Composable
fun rememberQueueStateFlow(): State<QueueManager.QueueState> {
    val queueManager = LocalQueueManager()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    return if (queueManager != null) {
        queueManager.queueStateFlow.collectAsStateWithLifecycle(
            initialValue = QueueManager.QueueState(),
            lifecycleOwner = lifecycleOwner
        )
    } else {
        remember { mutableStateOf(QueueManager.QueueState()) }
    }
}

/**
 * Composable to observe queue changes
 */
@Composable
fun rememberQueueChanges(): State<QueueManager.QueueChangeEvent?> {
    val queueManager = LocalQueueManager()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var queueChange by remember { mutableStateOf<QueueManager.QueueChangeEvent?>(null) }
    
    LaunchedEffect(queueManager, lifecycleOwner) {
        queueManager?.queueChangesLiveData?.observe(lifecycleOwner, Observer { change ->
            queueChange = change
        })
    }
    
    return remember { derivedStateOf { queueChange } }
}

/**
 * Composable to observe current playing item
 */
@Composable
fun rememberCurrentItem(): State<QueueManager.QueueItem?> {
    val queueManager = LocalQueueManager()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var currentItem by remember { mutableStateOf<QueueManager.QueueItem?>(null) }
    
    LaunchedEffect(queueManager, lifecycleOwner) {
        queueManager?.currentItemLiveData?.observe(lifecycleOwner, Observer { item ->
            currentItem = item
        })
    }
    
    return remember { derivedStateOf { currentItem } }
}

/**
 * Enhanced queue operations with proper context and non-blocking execution
 */
class EnhancedQueueOperations(private val queueManager: QueueManager?) {
    
    suspend fun addToEndWithContext(
        items: List<androidx.media3.common.MediaItem>,
        context: QueueManager.PlayContext? = null
    ) {
        queueManager?.addToEnd(items, context)
    }
    
    suspend fun playNextWithContext(
        items: List<androidx.media3.common.MediaItem>,
        context: QueueManager.PlayContext? = null
    ) {
        queueManager?.playNext(items, context)
    }
    
    suspend fun setQueueWithContext(
        items: List<androidx.media3.common.MediaItem>,
        startIndex: Int = 0,
        play: Boolean = true,
        startPosMs: Long = 0L,
        context: QueueManager.PlayContext? = null
    ) {
        queueManager?.setQueue(items, startIndex, play, startPosMs, context)
    }
    
    suspend fun moveItem(from: Int, to: Int) {
        // Use existing move function for immediate execution
        queueManager?.move(from, to)
    }
    
    suspend fun removeItem(index: Int) {
        // Use existing removeAt function for immediate execution
        queueManager?.removeAt(index)
    }
    
    suspend fun removeItemById(id: String) {
        // Find index and use removeAt for immediate execution
        val items = queueManager?.getQueueSnapshot() ?: return
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            queueManager?.removeAt(index)
        }
    }
    
    suspend fun clearQueue(keepCurrent: Boolean = false) {
        // Use existing clearQueue function for immediate execution
        queueManager?.clearQueue(keepCurrent)
    }
    
    fun setRepeatMode(mode: Int) {
        queueManager?.setRepeat(mode)
    }
    
    suspend fun toggleShuffle(enabled: Boolean) {
        // Use existing setShuffle function for immediate execution
        queueManager?.setShuffle(enabled)
    }
    
    /**
     * Add items using operation queue for better performance
     */
    suspend fun queueAddItems(
        items: List<androidx.media3.common.MediaItem>,
        position: Int = -1,
        context: QueueManager.PlayContext? = null
    ) {
        val queueItems = items.map { mediaItem ->
            QueueManager.QueueItem(
                mediaItem = mediaItem,
                source = QueueManager.QueueSource.USER_ADDED,
                context = context
            )
        }
        queueManager?.queueAdd(queueItems, position)
    }
    
    /**
     * Get pending operation count for UI feedback
     */
    fun getPendingOperationCount(): Int {
        return queueManager?.getPendingOperationCount() ?: 0
    }
    
    fun getCurrentIndex(): Int {
        return queueManager?.getCurrentIndex() ?: 0
    }
    
    fun getQueueSize(): Int {
        return queueManager?.getQueueSize() ?: 0
    }
    
    fun hasNext(): Boolean {
        return queueManager?.hasNext() ?: false
    }
    
    fun hasPrevious(): Boolean {
        return queueManager?.hasPrevious() ?: false
    }
    
    fun getCurrentItem(): QueueManager.QueueItem? {
        return queueManager?.getCurrentItem()
    }
    
    fun getVisibleQueue(): List<QueueManager.QueueItem> {
        return queueManager?.getVisibleQueue() ?: emptyList()
    }
    
    fun getQueueSnapshot(): List<QueueManager.QueueItem> {
        return queueManager?.getQueueSnapshot() ?: emptyList()
    }
    
    fun onTrackChanged(mediaId: String) {
        queueManager?.onTrackChanged(mediaId)
    }
}

/**
 * Composable to get enhanced queue operations
 */
@Composable
fun rememberQueueOperations(): EnhancedQueueOperations {
    val queueManager = LocalQueueManager()
    return remember(queueManager) {
        EnhancedQueueOperations(queueManager)
    }
}

/**
 * Composable to monitor pending operations for UI feedback
 */
@Composable
fun rememberPendingOperations(): State<Int> {
    val queueOperations = rememberQueueOperations()
    var pendingCount by remember { mutableStateOf(0) }
    
    LaunchedEffect(queueOperations) {
        while (true) {
            pendingCount = queueOperations.getPendingOperationCount()
            kotlinx.coroutines.delay(100) // Update every 100ms
        }
    }
    
    return remember { derivedStateOf { pendingCount } }
}

/**
 * Queue context helpers
 */
object QueueContextHelper {
    
    fun createAlbumContext(albumId: String, albumName: String): QueueManager.PlayContext {
        return QueueManager.PlayContext(
            type = QueueManager.ContextType.ALBUM,
            id = albumId,
            name = albumName,
            metadata = mapOf("type" to "album")
        )
    }
    
    fun createPlaylistContext(playlistId: String, playlistName: String): QueueManager.PlayContext {
        return QueueManager.PlayContext(
            type = QueueManager.ContextType.PLAYLIST,
            id = playlistId,
            name = playlistName,
            metadata = mapOf("type" to "playlist")
        )
    }
    
    fun createArtistContext(artistId: String, artistName: String): QueueManager.PlayContext {
        return QueueManager.PlayContext(
            type = QueueManager.ContextType.ARTIST,
            id = artistId,
            name = artistName,
            metadata = mapOf("type" to "artist")
        )
    }
    
    fun createGenreContext(genreId: String, genreName: String): QueueManager.PlayContext {
        return QueueManager.PlayContext(
            type = QueueManager.ContextType.GENRE,
            id = genreId,
            name = genreName,
            metadata = mapOf("type" to "genre")
        )
    }
    
    fun createSearchContext(query: String): QueueManager.PlayContext {
        return QueueManager.PlayContext(
            type = QueueManager.ContextType.SEARCH,
            id = "search",
            name = "Search: $query",
            metadata = mapOf("query" to query, "type" to "search")
        )
    }
    
    fun createLikedSongsContext(): QueueManager.PlayContext {
        return QueueManager.PlayContext(
            type = QueueManager.ContextType.LIKED_SONGS,
            id = "liked_songs",
            name = "Liked Songs",
            metadata = mapOf("type" to "liked_songs")
        )
    }
    
    fun createDiscoverContext(source: String): QueueManager.PlayContext {
        return QueueManager.PlayContext(
            type = QueueManager.ContextType.DISCOVER,
            id = "discover",
            name = "Discover: $source",
            metadata = mapOf("source" to source, "type" to "discover")
        )
    }
}
