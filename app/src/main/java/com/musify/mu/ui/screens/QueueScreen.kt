package com.musify.mu.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.musify.mu.ui.components.TopBar
import com.musify.mu.ui.components.EnhancedQueueTrackItem
import com.musify.mu.ui.components.EnhancedSwipeBackground
import com.musify.mu.data.db.entities.Track
import com.musify.mu.playback.LocalMediaController
import com.musify.mu.playback.QueueManager
import com.musify.mu.playback.QueueManagerProvider
import com.musify.mu.playback.LocalQueueManager
import com.musify.mu.playback.rememberQueueState
import com.musify.mu.playback.rememberQueueChanges
import com.musify.mu.playback.rememberCurrentItem
import com.musify.mu.playback.rememberQueueOperations
import com.musify.mu.playback.rememberPendingOperations
import com.musify.mu.playback.QueueContextHelper
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.rememberDismissState
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
 
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import org.burnoutcrew.reorderable.*
import com.musify.mu.ui.components.EnhancedDragAndDrop.smoothDraggable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun QueueScreen(navController: NavController) {
    val controller = LocalMediaController.current
    val hapticFeedback = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { com.musify.mu.data.repo.LibraryRepository.get(context) }

    android.util.Log.d("QueueScreenDBG", "Composed QueueScreen()")

    SideEffect {
        android.util.Log.d("QueueScreenDBG", "SideEffect: composition applied")
    }

    DisposableEffect(Unit) {
        android.util.Log.d("QueueScreenDBG", "DisposableEffect: entered screen")
        onDispose {
            android.util.Log.d("QueueScreenDBG", "DisposableEffect: leaving screen")
        }
    }

    // Advanced QueueManager integration with reactive state
    val queueManager = LocalQueueManager()
    val queueState by rememberQueueState()
    val queueChanges by rememberQueueChanges()
    val currentItem by rememberCurrentItem()
    val queueOperations = rememberQueueOperations()
    val pendingOperations by rememberPendingOperations()

    // Visual state for drag operations
    var isDragging by remember { mutableStateOf(false) }
    var dropIndex by remember { mutableStateOf(-1) }
    var visualQueueItems by remember { mutableStateOf<List<QueueManager.QueueItem>>(emptyList()) }

    // Real-time queue data tracked as State and updated on queue events
    var queueItems by remember { mutableStateOf<List<QueueManager.QueueItem>>(emptyList()) }
    fun computeQueueItems(): List<QueueManager.QueueItem> {
        val items = queueOperations.getVisibleQueue()
        if (items.isNotEmpty()) return items
        val c = controller
        return if (c != null) {
            (0 until c.mediaItemCount).mapNotNull { idx ->
                c.getMediaItemAt(idx)?.let { mediaItem ->
                    QueueManager.QueueItem(
                        mediaItem = mediaItem,
                        position = idx,
                        source = QueueManager.QueueSource.USER_ADDED
                    )
                }
            }
        } else emptyList()
    }
    LaunchedEffect(controller, queueManager) {
        queueItems = computeQueueItems()
    }

    // Recompute on any queue state change (e.g., count/index/shuffle/repeat)
    LaunchedEffect(queueState) {
        queueItems = computeQueueItems()
        if (!isDragging) visualQueueItems = queueItems
        // Reset drop index on state refresh to avoid stale placeholders
        dropIndex = -1
    }

    // Initialize QueueManager with controller data if needed
    LaunchedEffect(controller, queueManager) {
        if (controller != null && queueManager != null && queueState.totalItems == 0 && controller.mediaItemCount > 0) {
            android.util.Log.d(
                "QueueScreen",
                "Initializing QueueManager with ${controller.mediaItemCount} items from controller"
            )

            // Get all media items from controller
            val mediaItems = (0 until controller.mediaItemCount).mapNotNull { idx ->
                controller.getMediaItemAt(idx)
            }

            if (mediaItems.isNotEmpty()) {
                // Initialize QueueManager with current playlist
                queueOperations.setQueueWithContext(
                    items = mediaItems,
                    startIndex = controller.currentMediaItemIndex.coerceAtLeast(0),
                    play = false, // Don't start playing, just set the queue
                    startPosMs = 0L,
                    context = null
                )
                queueItems = computeQueueItems()
            }
        }
    }

    // Debug: Log queue state
    LaunchedEffect(queueItems, queueState, visualQueueItems) {
        android.util.Log.d(
            "QueueScreenDBG",
            "queueItems=${queueItems.size} visible=${visualQueueItems.size} pri=${queueState.playNextCount} user=${queueState.userQueueCount} curIdx=${queueState.currentIndex}"
        )
    }

    // Function to convert QueueItem to Track for UI
    fun QueueManager.QueueItem.toTrack(): Track {
        val md = mediaItem.mediaMetadata
        return Track(
            mediaId = mediaItem.mediaId,
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            durationMs = 0L,
            artUri = md.artworkUri?.toString(),
            albumId = null
        )
    }

    // Sync visual queue with real queue data
    LaunchedEffect(queueItems) {
        if (!isDragging) {
            visualQueueItems = queueItems.toList()
        }
    }

    // React to queue changes for real-time updates
    LaunchedEffect(queueChanges) {
        queueChanges?.let { change ->
            scope.launch {
                when (change) {
                    is QueueManager.QueueChangeEvent.ItemAdded -> {
                        android.util.Log.d(
                            "QueueScreenDBG",
                            "ItemAdded at ${change.position} id=${change.item.id}"
                        )
                        snackbarHostState.showSnackbar(
                            message = "Track added to queue",
                            duration = SnackbarDuration.Short
                        )
                    }

                    is QueueManager.QueueChangeEvent.ItemRemoved -> {
                        android.util.Log.d(
                            "QueueScreenDBG",
                            "ItemRemoved at ${change.position} id=${change.item.id}"
                        )
                        snackbarHostState.showSnackbar(
                            message = "Track removed from queue",
                            duration = SnackbarDuration.Short
                        )
                    }

                    is QueueManager.QueueChangeEvent.ItemMoved -> {
                        android.util.Log.d(
                            "QueueScreenDBG",
                            "ItemMoved from=${change.from} to=${change.to} id=${change.item.id}"
                        )
                        snackbarHostState.showSnackbar(
                            message = "Track moved in queue",
                            duration = SnackbarDuration.Short
                        )
                    }

                    is QueueManager.QueueChangeEvent.QueueShuffled -> {
                        android.util.Log.d(
                            "QueueScreenDBG",
                            "QueueShuffled enabled=${queueState.shuffleEnabled}"
                        )
                        snackbarHostState.showSnackbar(
                            message = if (queueState.shuffleEnabled) "Shuffle enabled" else "Shuffle disabled",
                            duration = SnackbarDuration.Short
                        )
                    }

                    is QueueManager.QueueChangeEvent.QueueCleared -> {
                        android.util.Log.d(
                            "QueueScreenDBG",
                            "QueueCleared keepCurrent=${change.keepCurrent}"
                        )
                        snackbarHostState.showSnackbar(
                            message = "Queue cleared",
                            duration = SnackbarDuration.Short
                        )
                    }

                    is QueueManager.QueueChangeEvent.QueueReordered -> {
                        android.util.Log.d(
                            "QueueScreenDBG",
                            "QueueReordered newSize=${change.newOrder.size}"
                        )
                        snackbarHostState.showSnackbar(
                            message = "Queue reordered",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
            // After any queue change event, refresh lists immediately on main thread
            queueItems = computeQueueItems()
            if (!isDragging) visualQueueItems = queueItems
        }
    }

    // Enhanced drag state with optimized configuration
    val dragConfig = remember {
        com.musify.mu.ui.components.DragDropConfig(
            longPressTimeout = 250L, // Instant drag feel
            animationDuration = 150,
            enableHardwareAcceleration = true,
            enableAutoScroll = true,
            autoScrollThreshold = 60f // More responsive edge scrolling
        )
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            android.util.Log.d(
                "QueueScreenDBG",
                "onMove from=${from.index} to=${to.index} beforeSize=${visualQueueItems.size}"
            )
            visualQueueItems = visualQueueItems.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            isDragging = true
        },
        onDragEnd = { from, to ->
            val fromIdx = from
            val toIdxRaw = to
            val lastIndex = (visualQueueItems.size - 1).coerceAtLeast(0)
            val toIdx = toIdxRaw.coerceIn(0, lastIndex)
            if (fromIdx != toIdx && fromIdx in 0..lastIndex && toIdx in 0..lastIndex) {
                scope.launch {
                    try {
                        android.util.Log.d(
                            "QueueScreenDBG",
                            "onDragEnd commit from=$fromIdx to=$toIdx last=$lastIndex"
                        )
                        val snapBefore = queueOperations.getQueueSnapshot()
                        val fromItem = snapBefore.getOrNull(fromIdx)
                        if (fromItem != null) {
                            val pnIndices = snapBefore.withIndex()
                                .filter { it.value.source == QueueManager.QueueSource.PLAY_NEXT }
                                .map { it.index }
                            val userIndices = snapBefore.withIndex()
                                .filter { it.value.source == QueueManager.QueueSource.USER_QUEUE }
                                .map { it.index }
                            val hasPn = pnIndices.isNotEmpty()
                            val hasUser = userIndices.isNotEmpty()
                            val pnStart = if (hasPn) pnIndices.first() else -1
                            val pnEnd = if (hasPn) pnIndices.last() + 1 else -1
                            val userStart = if (hasUser) userIndices.first() else -1
                            val userEnd = if (hasUser) userIndices.last() + 1 else -1
                            val intoPn = hasPn && toIdx in pnStart until pnEnd
                            val intoUser = hasUser && toIdx in userStart until userEnd

                            if (fromItem.source != QueueManager.QueueSource.PLAY_NEXT && intoPn) {
                                val ctx = queueState.context
                                    ?: QueueContextHelper.createSearchContext("drag_to_playnext")
                                queueOperations.removeItemById(fromItem.id)
                                val correctedTarget = if (fromIdx < toIdx) toIdx - 1 else toIdx
                                queueOperations.playNextWithContext(
                                    items = listOf(fromItem.mediaItem),
                                    context = ctx
                                )
                                val snap2 = queueOperations.getQueueSnapshot()
                                val pn2 = snap2.withIndex()
                                    .filter { it.value.source == QueueManager.QueueSource.PLAY_NEXT }
                                    .map { it.index }
                                if (pn2.isNotEmpty()) {
                                    val pnStart2 = pn2.first()
                                    val pnEnd2 = pn2.last() + 1
                                    val appendedIdx =
                                        snap2.indexOfLast { it.source == QueueManager.QueueSource.PLAY_NEXT && it.id == fromItem.id }
                                    if (appendedIdx >= 0) {
                                        val desired = correctedTarget.coerceIn(pnStart2, pnEnd2 - 1)
                                        if (appendedIdx != desired) queueOperations.moveItem(
                                            appendedIdx,
                                            desired
                                        )
                                    }
                                }
                            } else if (fromItem.source == QueueManager.QueueSource.PLAY_NEXT && !intoPn) {
                                queueOperations.removeItemById(fromItem.id)
                                var snap3 = queueOperations.getQueueSnapshot()
                                var mainIdx =
                                    snap3.indexOfFirst { it.source != QueueManager.QueueSource.PLAY_NEXT && it.id == fromItem.id }
                                if (mainIdx < 0) {
                                    queueOperations.addToUserQueueWithContext(
                                        items = listOf(
                                            fromItem.mediaItem
                                        ), context = fromItem.context
                                    )
                                    snap3 = queueOperations.getQueueSnapshot()
                                    mainIdx =
                                        snap3.indexOfLast { it.source != QueueManager.QueueSource.PLAY_NEXT && it.id == fromItem.id }
                                }
                                val last = (snap3.size - 1).coerceAtLeast(0)
                                val pn3 = snap3.withIndex()
                                    .filter { it.value.source == QueueManager.QueueSource.PLAY_NEXT }
                                    .map { it.index }
                                val pnStart3 = pn3.firstOrNull() ?: -1
                                val pnEnd3 = if (pn3.isNotEmpty()) pn3.last() + 1 else -1
                                val correctedTarget = if (fromIdx < toIdx) toIdx - 1 else toIdx
                                var desired = correctedTarget.coerceIn(0, last)
                                if (pnStart3 >= 0 && desired in pnStart3 until pnEnd3) desired =
                                    pnEnd3
                                if (mainIdx >= 0 && mainIdx != desired) queueOperations.moveItem(
                                    mainIdx,
                                    desired
                                )
                            } else if (fromItem.source != QueueManager.QueueSource.USER_QUEUE && intoUser) {
                                val ctx = queueState.context
                                    ?: QueueContextHelper.createSearchContext("drag_to_userqueue")
                                queueOperations.removeItemById(fromItem.id)
                                val correctedTarget = if (fromIdx < toIdx) toIdx - 1 else toIdx
                                queueOperations.addToUserQueueWithContext(
                                    items = listOf(fromItem.mediaItem),
                                    context = ctx
                                )
                                val snapU = queueOperations.getQueueSnapshot()
                                val uIdxs = snapU.withIndex()
                                    .filter { it.value.source == QueueManager.QueueSource.USER_QUEUE }
                                    .map { it.index }
                                if (uIdxs.isNotEmpty()) {
                                    val uStart = uIdxs.first()
                                    val uEnd = uIdxs.last() + 1
                                    val appended =
                                        snapU.indexOfLast { it.source == QueueManager.QueueSource.USER_QUEUE && it.id == fromItem.id }
                                    if (appended >= 0) {
                                        val desired = correctedTarget.coerceIn(uStart, uEnd - 1)
                                        if (appended != desired) queueOperations.moveItem(
                                            appended,
                                            desired
                                        )
                                    }
                                }
                            } else if (fromItem.source == QueueManager.QueueSource.USER_QUEUE && !intoUser) {
                                queueOperations.removeItemById(fromItem.id)
                                var snapM = queueOperations.getQueueSnapshot()
                                var mainIdx =
                                    snapM.indexOfFirst { it.source != QueueManager.QueueSource.USER_QUEUE && it.id == fromItem.id }
                                if (mainIdx < 0) {
                                    // create main copy by re-adding to user queue then move after both PN and USER segments
                                    queueOperations.addToUserQueueWithContext(
                                        items = listOf(
                                            fromItem.mediaItem
                                        ), context = fromItem.context
                                    )
                                    snapM = queueOperations.getQueueSnapshot()
                                    mainIdx =
                                        snapM.indexOfLast { it.source != QueueManager.QueueSource.USER_QUEUE && it.id == fromItem.id }
                                }
                                if (mainIdx >= 0 && mainIdx != toIdx) queueOperations.moveItem(
                                    mainIdx,
                                    toIdx
                                )
                            } else {
                                queueOperations.moveItem(fromIdx, toIdx)
                            }
                        }
                        visualQueueItems = queueOperations.getVisibleQueue()
                        android.util.Log.d(
                            "QueueScreenDBG",
                            "after commit visibleSize=${visualQueueItems.size}"
                        )
                    } finally {
                        isDragging = false
                    }
                }
            } else {
                isDragging = false
                visualQueueItems = queueItems.toList()
            }
        }
    )

    // Background gradient with better visual hierarchy
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        )
    )

    // Helper to map visual index to combined queue index (priority + user + main remainder)
    fun mapVisualToCombinedIndex(visualIndex: Int): Int = queueState.currentIndex + 1 + visualIndex

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    LaunchedEffect(Unit) {
        // Open half height first, user can drag to full
        sheetState.show()
        sheetState.partialExpand()
    }

    ModalBottomSheet(
        onDismissRequest = { navController.popBackStack() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundGradient)
                .navigationBarsPadding()
        ) {
            // Removed top sticky header; queue appears inside a sheet like Spotify

            if (visualQueueItems.isEmpty()) { EmptyQueueMessage(
                    queueState = queueState,
                    controllerItemCount = controller?.mediaItemCount ?: 0,
                    queueManagerConnected = queueManager != null
                )
            } else {
                // Enhanced auto-scroll state for smooth edge scrolling
                val autoScrollState = com.musify.mu.ui.components.EnhancedDragAndDrop
                    .rememberAutoScrollState(
                        listState = reorderState.listState,
                        threshold = dragConfig.autoScrollThreshold,
                        maxScrollSpeed = 400f
                    )

                // Track on-screen bounds for each item and render a floating overlay while dragging
                val itemBounds = remember { mutableStateMapOf<String, Rect>() }
                var dragOverlayKey by remember { mutableStateOf<String?>(null) }
                var dragOverlayTrack by remember { mutableStateOf<Track?>(null) }
                var dragOverlayIndex by remember { mutableStateOf(0) }
                val density = LocalDensity.current

                fun computeDropIndexFromOverlay(): Int {
                    val key = dragOverlayKey ?: return -1
                    val bounds = itemBounds[key] ?: return -1
                    // Use overlay middle to find nearest slot visually
                    val overlayCenter = bounds.top + bounds.height / 2f
                    var candidate = -1
                    for (i in visualQueueItems.indices) {
                        val k = "queue_${visualQueueItems[i].id}_${visualQueueItems[i].addedAt}"
                        val b = itemBounds[k] ?: continue
                        val center = b.top + b.height / 2f
                        if (overlayCenter < center) {
                            candidate = i; break
                        }
                        candidate = i
                    }
                    return candidate
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { clip = false }
                            .reorderable(reorderState),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Section headers for Priority and User queues
                        if (queueState.playNextCount > 0) {
                            item(key = "queue_section_playnext_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Play Next (${queueState.playNextCount})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp).weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                val snap = queueOperations.getQueueSnapshot()
                                                // Remove all Play Next items
                                                snap.filter { it.source == QueueManager.QueueSource.PLAY_NEXT }
                                                    .forEach { queueOperations.removeItemById(it.id) }
                                            }
                                        }
                                    ) {
                                        Text("Clear")
                                    }
                                }
                            }
                        }
                        if (queueState.userQueueCount > 0) {
                            item(key = "queue_section_userqueue_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Added By You (${queueState.userQueueCount})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(start = 8.dp).weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                val snap = queueOperations.getQueueSnapshot()
                                                snap.filter { it.source == QueueManager.QueueSource.USER_QUEUE }
                                                    .forEach { queueOperations.removeItemById(it.id) }
                                            }
                                        }
                                    ) { Text("Clear") }
                                }
                            }
                        }
                        itemsIndexed(
                            visualQueueItems, // Use visual queue for display
                            key = { _, item -> "queue_${item.id}_${item.addedAt}" }
                        ) { idx, queueItem ->
                            val itemKey = "queue_${queueItem.id}_${queueItem.addedAt}"
                            // Try to get the full track data from repository first to ensure we have pre-extracted artwork
                            val track = repo.getTrackByMediaId(queueItem.mediaItem.mediaId)
                                ?: queueItem.toTrack()
                            val dismissState = rememberDismissState(
                                confirmStateChange = { value ->
                                    when (value) {
                                        DismissValue.DismissedToEnd -> {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            // Use QueueManager's advanced playNext functionality
                                            scope.launch {
                                                try {
                                                    // Create context for the play next operation
                                                    val context = queueState.context
                                                        ?: QueueContextHelper.createSearchContext("manual_add")

                                                    // Use QueueManager's advanced playNext with context
                                                    queueOperations.playNextWithContext(
                                                        items = listOf(track.toMediaItem()),
                                                        context = context
                                                    )

                                                    snackbarHostState.showSnackbar(
                                                        message = "Added to play next",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                } catch (e: Exception) {
                                                    snackbarHostState.showSnackbar(
                                                        message = "Failed to add to play next: ${e.message}",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                            }
                                            false // Don't dismiss the item
                                        }

                                        DismissValue.DismissedToStart -> {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            // Use QueueManager's advanced remove functionality
                                            scope.launch {
                                                try {
                                                    // Store item for potential undo
                                                    val removedItem = queueItem

                                                    // Use QueueManager's removeAt function for immediate execution
                                                    queueOperations.removeItemById(removedItem.id)

                                                    val result = snackbarHostState.showSnackbar(
                                                        message = "Removed \"${track.title}\" from queue",
                                                        actionLabel = "Undo",
                                                        duration = SnackbarDuration.Long
                                                    )
                                                    if (result == SnackbarResult.ActionPerformed) {
                                                        // Restore item using QueueManager's addToEnd with context
                                                        queueOperations.addToEndWithContext(
                                                            items = listOf(removedItem.mediaItem),
                                                            context = removedItem.context
                                                        )
                                                    }
                                                } catch (e: Exception) {
                                                    snackbarHostState.showSnackbar(
                                                        message = "Failed to remove track: ${e.message}",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                            }
                                            true // Dismiss the item
                                        }

                                        else -> false
                                    }
                                }
                            )

                            val isDraggingItem = false
                            val isPlaceholderSlot = (dropIndex == idx)
                            // Track bounds for overlay positioning
                            LaunchedEffect(isDraggingItem) {
                                val key = itemKey
                                if (isDraggingItem) {
                                    dragOverlayKey = key
                                    dragOverlayTrack = track
                                    dragOverlayIndex = idx
                                } else if (dragOverlayKey == key) {
                                    dragOverlayKey = null
                                    dragOverlayTrack = null
                                }
                            }
                            ReorderableItem(reorderState, key = itemKey) { isDraggingItem ->
                                Box(
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        itemBounds[itemKey] = coords.boundsInRoot()
                                    }
                                ) {
                                    if (isPlaceholderSlot) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(60.dp)
                                                .graphicsLayer { clip = false }
                                        ) {}
                                    } else {
                                        SwipeToDismiss(
                                            state = dismissState,
                                            directions = if (isDraggingItem) emptySet() else setOf(
                                                DismissDirection.StartToEnd,
                                                DismissDirection.EndToStart
                                            ),
                                            background = { EnhancedSwipeBackground(dismissState.dismissDirection) },
                                            dismissContent = {
                                                com.musify.mu.ui.components.CompactTrackRow(
                                                    title = track.title,
                                                    subtitle = "${track.artist} • ${track.album}",
                                                    artData = track.artUri,
                                                    contentDescription = track.title,
                                                    isPlaying = (currentItem?.mediaItem?.mediaId == queueItem.mediaItem.mediaId),
                                                    onClick = {
                                                        if (dragConfig.enableHapticFeedback) {
                                                            hapticFeedback.performHapticFeedback(
                                                                HapticFeedbackType.TextHandleMove
                                                            )
                                                        }
                                                        val combinedIndex =
                                                            mapVisualToCombinedIndex(idx)
                                                        controller?.seekToDefaultPosition(
                                                            combinedIndex
                                                        )
                                                    },
                                                    modifier = Modifier
                                                        .zIndex(if (isDraggingItem) 10f else 0f)
                                                        .graphicsLayer {
                                                            if (isDraggingItem) alpha = 0f
                                                        }
                                                        .graphicsLayer { clip = false },
                                                    extraArtOverlay = {
                                                        if (queueItem.source == QueueManager.QueueSource.PLAY_NEXT) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .align(Alignment.TopEnd)
                                                                    .padding(2.dp)
                                                            ) {
                                                                Surface(
                                                                    color = MaterialTheme.colorScheme.tertiary,
                                                                    shape = RoundedCornerShape(6.dp),
                                                                ) {
                                                                    Row(
                                                                        verticalAlignment = Alignment.CenterVertically,
                                                                        modifier = Modifier.padding(
                                                                            horizontal = 6.dp,
                                                                            vertical = 2.dp
                                                                        )
                                                                    ) {
                                                                        Icon(
                                                                            imageVector = Icons.Rounded.SkipNext,
                                                                            contentDescription = null,
                                                                            tint = MaterialTheme.colorScheme.onTertiary,
                                                                            modifier = Modifier.size(
                                                                                12.dp
                                                                            )
                                                                        )
                                                                        Spacer(Modifier.width(4.dp))
                                                                        Text(
                                                                            text = "NEXT",
                                                                            style = MaterialTheme.typography.labelSmall,
                                                                            color = MaterialTheme.colorScheme.onTertiary
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    },
                                                    trailingContent = {
                                                        val isCurrent =
                                                            (currentItem?.mediaItem?.mediaId == queueItem.mediaItem.mediaId)
                                                        IconButton(
                                                            onClick = { },
                                                            enabled = !isCurrent,
                                                            modifier = Modifier.detectReorderAfterLongPress(
                                                                reorderState
                                                            )
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.DragHandle,
                                                                contentDescription = if (isCurrent) "Now playing (drag disabled)" else "Drag to reorder",
                                                                tint = MaterialTheme.colorScheme.onSurface.copy(
                                                                    alpha = if (isCurrent) 0.3f else 0.7f
                                                                )
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                            // Removed trailing header to avoid implicit receiver composable errors
                        }
                    }
                    // Floating drag overlay
                    if (dragOverlayKey != null && dragOverlayTrack != null) {
                        val bounds = itemBounds[dragOverlayKey!!]
                        if (bounds != null) {
                            val y = bounds.top
                            val width = bounds.width
                            val height = bounds.height
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        translationY = y
                                        clip = false
                                    }
                            ) {
                                Card(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .height(with(density) { height.toDp() }),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(
                                            alpha = 0.95f
                                        )
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    com.musify.mu.ui.components.CompactTrackRow(
                                        title = dragOverlayTrack!!.title,
                                        subtitle = "${dragOverlayTrack!!.artist} • ${dragOverlayTrack!!.album}",
                                        artData = dragOverlayTrack!!.artUri,
                                        contentDescription = dragOverlayTrack!!.title,
                                        isPlaying = (currentItem?.mediaItem?.mediaId == dragOverlayTrack!!.mediaId),
                                        onClick = {},
                                        useGlass = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
    private fun EnhancedQueueHeader(
        queueSize: Int,
        currentIndex: Int,
        queueState: QueueManager.QueueState,
        currentItem: QueueManager.QueueItem? = null,
        queueOperations: com.musify.mu.playback.EnhancedQueueOperations,
        pendingOperations: Int = 0,
        onShuffleToggle: (Boolean) -> Unit,
        onRepeatModeChange: (Int) -> Unit = {},
        onClearQueue: (Boolean) -> Unit = {}
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Main header info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Now Playing Queue",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = buildString {
                                append("$queueSize ${if (queueSize == 1) "song" else "songs"}")
                                if (queueSize > 0) {
                                    append(" • Position ${currentIndex + 1}")
                                }
                                if (queueState.context != null) {
                                    append(" • ${queueState.context.name}")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                // Advanced queue controls
                if (queueSize > 0) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // First row of controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Shuffle toggle
                        FilterChip(
                            onClick = { onShuffleToggle(!queueState.shuffleEnabled) },
                            label = { Text("Shuffle") },
                            selected = queueState.shuffleEnabled,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )

                        // Repeat mode toggle
                        FilterChip(
                            onClick = {
                                val nextMode = when (queueState.repeatMode) {
                                    0 -> 1 // None -> All
                                    1 -> 2 // All -> One
                                    2 -> 0 // One -> None
                                    else -> 0
                                }
                                onRepeatModeChange(nextMode)
                            },
                            label = {
                                Text(
                                    when (queueState.repeatMode) {
                                        1 -> "Repeat All"
                                        2 -> "Repeat One"
                                        else -> "No Repeat"
                                    }
                                )
                            },
                            selected = queueState.repeatMode != 0,
                            leadingIcon = {
                                Icon(
                                    imageVector = when (queueState.repeatMode) {
                                        1 -> Icons.Rounded.Repeat
                                        2 -> Icons.Rounded.RepeatOne
                                        else -> Icons.Rounded.Repeat
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Second row of controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Clear queue options
                        var showClearMenu by remember { mutableStateOf(false) }

                        Box {
                            FilterChip(
                                onClick = { showClearMenu = true },
                                label = { Text("Clear Queue") },
                                selected = false,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )

                            DropdownMenu(
                                expanded = showClearMenu,
                                onDismissRequest = { showClearMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Clear All") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Clear,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        onClearQueue(false)
                                        showClearMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Keep Current") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.SkipNext,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        onClearQueue(true)
                                        showClearMenu = false
                                    }
                                )
                            }
                        }

                        // Queue info chip
                        if (queueState.playNextCount > 0) {
                            FilterChip(
                                onClick = { },
                                label = { Text("${queueState.playNextCount} in Play Next") },
                                selected = false,
                                enabled = false,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.PlaylistPlay,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }

                        // Pending operations indicator
                        if (pendingOperations > 0) {
                            FilterChip(
                                onClick = { },
                                label = { Text("$pendingOperations pending") },
                                selected = true,
                                enabled = false,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.HourglassEmpty,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
    private fun EmptyQueueMessage(
        queueState: QueueManager.QueueState = QueueManager.QueueState(),
        controllerItemCount: Int = 0,
        queueManagerConnected: Boolean = false
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = "Queue is empty",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "Add songs to start building your queue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )

                // Debug information
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Debug Info:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "QueueManager Connected: $queueManagerConnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Controller Items: $controllerItemCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Queue State Total: ${queueState.totalItems}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Current Index: ${queueState.currentIndex}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

@Composable
    private fun EnhancedSwipeBackground(dismissDirection: DismissDirection?) {
        val color = when (dismissDirection) {
            DismissDirection.StartToEnd -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            DismissDirection.EndToStart -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            else -> MaterialTheme.colorScheme.surface
        }

        val icon = when (dismissDirection) {
            DismissDirection.StartToEnd -> Icons.Rounded.QueueMusic
            DismissDirection.EndToStart -> Icons.Default.Delete
            else -> null
        }

        val text = when (dismissDirection) {
            DismissDirection.StartToEnd -> "Play Next"
            DismissDirection.EndToStart -> "Remove"
            else -> ""
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color, RoundedCornerShape(12.dp))
                .padding(16.dp),
            contentAlignment = when (dismissDirection) {
                DismissDirection.StartToEnd -> Alignment.CenterStart
                DismissDirection.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
        ) {
            if (icon != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = text,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
    }


    private fun androidx.media3.common.MediaItem.toTrack(): Track {
        val md = mediaMetadata
        return Track(
            mediaId = mediaId,
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            durationMs = 0L,
            artUri = md.artworkUri?.toString(),
            albumId = null
        )
    }
