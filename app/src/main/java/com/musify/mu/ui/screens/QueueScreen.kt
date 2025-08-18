package com.musify.mu.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
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
import org.burnoutcrew.reorderable.*
import org.burnoutcrew.reorderable.ItemPosition
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
 
import android.content.ContentUris
import android.provider.MediaStore

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun QueueScreen(navController: NavController) {
    val controller = LocalMediaController.current
    val hapticFeedback = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { com.musify.mu.data.repo.LibraryRepository.get(context) }

    // Advanced QueueManager integration with reactive state
    val queueManager = LocalQueueManager()
    val queueState by rememberQueueState()
    val queueChanges by rememberQueueChanges()
    val currentItem by rememberCurrentItem()
    val queueOperations = rememberQueueOperations()
    val pendingOperations by rememberPendingOperations()
    
    // Visual state for drag operations
    var isDragging by remember { mutableStateOf(false) }
    var visualQueueItems by remember { mutableStateOf<List<QueueManager.QueueItem>>(emptyList()) }
    
    // Get real-time queue data from QueueManager with fallback to controller
    val queueItems = remember(queueManager, controller) {
        derivedStateOf { 
            val queueManagerItems = queueOperations.getVisibleQueue()
            if (queueManagerItems.isEmpty() && controller != null) {
                // Fallback: Get items directly from MediaController
                (0 until controller.mediaItemCount).mapNotNull { idx ->
                    controller.getMediaItemAt(idx)?.let { mediaItem ->
                        QueueManager.QueueItem(
                            mediaItem = mediaItem,
                            position = idx,
                            source = QueueManager.QueueSource.USER_ADDED
                        )
                    }
                }
            } else {
                queueManagerItems
            }
        }
    }
    
    // Initialize QueueManager with controller data if needed
    LaunchedEffect(controller, queueManager) {
        if (controller != null && queueManager != null && queueState.totalItems == 0 && controller.mediaItemCount > 0) {
            android.util.Log.d("QueueScreen", "Initializing QueueManager with ${controller.mediaItemCount} items from controller")
            
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
            }
        }
    }
    
    // Debug: Log queue state
    LaunchedEffect(queueItems.value, queueState) {
        android.util.Log.d("QueueScreen", "Queue items count: ${queueItems.value.size}")
        android.util.Log.d("QueueScreen", "Queue state total: ${queueState.totalItems}")
        android.util.Log.d("QueueScreen", "Controller items: ${controller?.mediaItemCount ?: 0}")
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
    LaunchedEffect(queueItems.value) {
                    if (!isDragging) {
            visualQueueItems = queueItems.value.toList()
                }
            }

    // React to queue changes for real-time updates
    LaunchedEffect(queueChanges) {
        queueChanges?.let { change ->
            scope.launch {
                when (change) {
                    is QueueManager.QueueChangeEvent.ItemAdded -> {
                        snackbarHostState.showSnackbar(
                            message = "Track added to queue",
                            duration = SnackbarDuration.Short
                        )
                    }
                    is QueueManager.QueueChangeEvent.ItemRemoved -> {
                        snackbarHostState.showSnackbar(
                            message = "Track removed from queue",
                            duration = SnackbarDuration.Short
                        )
                    }
                    is QueueManager.QueueChangeEvent.ItemMoved -> {
                        snackbarHostState.showSnackbar(
                            message = "Track moved in queue",
                            duration = SnackbarDuration.Short
                        )
                    }
                    is QueueManager.QueueChangeEvent.QueueShuffled -> {
                        snackbarHostState.showSnackbar(
                            message = if (queueState.shuffleEnabled) "Shuffle enabled" else "Shuffle disabled",
                            duration = SnackbarDuration.Short
                        )
                    }
                    is QueueManager.QueueChangeEvent.QueueCleared -> {
                        snackbarHostState.showSnackbar(
                            message = "Queue cleared",
                            duration = SnackbarDuration.Short
                        )
                    }
                    is QueueManager.QueueChangeEvent.QueueReordered -> {
                        snackbarHostState.showSnackbar(
                            message = "Queue reordered",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
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

    val state = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Immediate haptic feedback for better UX
            if (dragConfig.enableHapticFeedback) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }

            isDragging = true
            
            // Update ONLY visual state for smooth UI feedback
            val newVisualItems = visualQueueItems.toMutableList()
            if (from.index < newVisualItems.size && to.index <= newVisualItems.size) {
                val item = newVisualItems.removeAt(from.index)
                newVisualItems.add(to.index, item)
                visualQueueItems = newVisualItems
            }
        },
        onDragEnd = { from, to ->
            if (dragConfig.enableHapticFeedback) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            
            // Commit the actual move operation using QueueManager
            val fromIdx = from  // from is Int in onDragEnd
            val toIdx = to      // to is Int in onDragEnd
            
            if (fromIdx != toIdx && fromIdx < queueItems.value.size && toIdx <= queueItems.value.size) {
                scope.launch {
                    try {
                        // Use QueueManager's move function for immediate execution
                        queueOperations.moveItem(fromIdx, toIdx)
                        
                        snackbarHostState.showSnackbar(
                            message = "Track moved successfully",
                            duration = SnackbarDuration.Short
                        )
                    } catch (e: Exception) {
                        // Revert visual state on error
                        visualQueueItems = queueItems.value.toList()
                        
                        snackbarHostState.showSnackbar(
                            message = "Failed to move track: ${e.message}",
                            duration = SnackbarDuration.Long
                        )
                    } finally {
                        isDragging = false
                    }
                }
            } else {
                isDragging = false
                visualQueueItems = queueItems.value.toList()
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

    Scaffold(
        topBar = {
            TopBar(title = "Queue")
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundGradient)
                .padding(paddingValues)
        ) {
            // Enhanced queue header with advanced controls
            EnhancedQueueHeader(
                queueSize = queueState.totalItems,
                currentIndex = queueState.currentIndex,
                queueState = queueState,
                currentItem = currentItem,
                queueOperations = queueOperations,
                pendingOperations = pendingOperations,
                onShuffleToggle = { enabled ->
                    scope.launch {
                        // Use QueueManager's setShuffle for immediate execution
                        queueOperations.toggleShuffle(enabled)
                    }
                },
                onRepeatModeChange = { mode ->
                    queueOperations.setRepeatMode(mode)
                },
                onClearQueue = { keepCurrent ->
                    scope.launch {
                        // Use QueueManager's clearQueue for immediate execution
                        queueOperations.clearQueue(keepCurrent)
                    }
                }
            )

            if (visualQueueItems.isEmpty()) {
                EmptyQueueMessage(
                    queueState = queueState,
                    controllerItemCount = controller?.mediaItemCount ?: 0,
                    queueManagerConnected = queueManager != null
                )
            } else {
                // Enhanced auto-scroll state for smooth edge scrolling
                val autoScrollState = com.musify.mu.ui.components.EnhancedDragAndDrop
                    .rememberAutoScrollState(
                        listState = state.listState,
                        threshold = dragConfig.autoScrollThreshold,
                        maxScrollSpeed = 400f
                    )

                // Track on-screen bounds for each item and render a floating overlay while dragging
                val itemBounds = remember { mutableStateMapOf<String, Rect>() }
                var dragOverlayKey by remember { mutableStateOf<String?>(null) }
                var dragOverlayTrack by remember { mutableStateOf<Track?>(null) }
                var dragOverlayIndex by remember { mutableStateOf(0) }
                val density = LocalDensity.current

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = state.listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .reorderable(state),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                    if (queueState.playNextCount > 0 && queueState.currentIndex + 1 < visualQueueItems.size) {
                        item(key = "queue_section_playnext_header") {
                            Text(
                                text = "Play Next (${queueState.playNextCount})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }
                    }
                    itemsIndexed(
                        visualQueueItems, // Use visual queue for display
                        key = { _, item -> "queue_${item.id}" }
                    ) { idx, queueItem ->
                        // Try to get the full track data from repository first to ensure we have pre-extracted artwork
                        val track = repo.getTrackByMediaId(queueItem.mediaItem.mediaId) ?: queueItem.toTrack()
                        val dismissState = rememberDismissState(
                            confirmStateChange = { value ->
                                when (value) {
                                    DismissValue.DismissedToEnd -> {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Use QueueManager's advanced playNext functionality
                                        scope.launch {
                                            try {
                                                // Create context for the play next operation
                                                val context = queueState.context ?: QueueContextHelper.createSearchContext("manual_add")
                                                
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

                        SwipeToDismiss(
                            state = dismissState,
                            directions = setOf(DismissDirection.StartToEnd, DismissDirection.EndToStart),
                            background = {
                                EnhancedSwipeBackground(dismissState.dismissDirection)
                            },
                            dismissContent = {
                                ReorderableItem(state, key = "queue_${queueItem.id}") { isDragging ->
                                    val itemKey = "queue_${queueItem.id}"
                                    if (isDragging) {
                                        dragOverlayKey = itemKey
                                        dragOverlayTrack = track
                                        dragOverlayIndex = idx
                                    } else if (dragOverlayKey == itemKey) {
                                        dragOverlayKey = null
                                        dragOverlayTrack = null
                                    }
                                    EnhancedQueueTrackItem(
                                        track = track,
                                        isCurrentlyPlaying = idx == queueState.currentIndex,
                                        isDragging = isDragging,
                                        isMarkedPlayNext = queueItem.source == QueueManager.QueueSource.PLAY_NEXT,
                                        position = idx + 1,
                                        onClick = {
                                            if (dragConfig.enableHapticFeedback) {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            controller?.seekToDefaultPosition(idx)
                                        },
                                        reorderState = state,
                                        config = dragConfig,
                                        modifier = Modifier
                                            .zIndex(if (isDragging) 2f else 0f)
                                            .onGloballyPositioned { coordinates ->
                                                itemBounds[itemKey] = coordinates.boundsInRoot()
                                            }
                                            .graphicsLayer { if (isDragging) alpha = 0f }
                                    )
                                }
                            }
                        )
                    }
                    if (queueState.playNextCount > 0 && queueState.currentIndex + queueState.playNextCount + 1 < visualQueueItems.size) {
                        item(key = "queue_section_normal_header") {
                            Text(
                                text = "Queue (${queueState.totalItems - queueState.playNextCount - 1})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }
                    }
                    }

                    // Floating overlay for the dragged item
                    val overlayKey = dragOverlayKey
                    val overlayTrack = dragOverlayTrack
                    if (overlayKey != null && overlayTrack != null) {
                        val rect = itemBounds[overlayKey]
                        if (rect != null) {
                            val xDp = with(density) { rect.left.toDp() }
                            val yDp = with(density) { rect.top.toDp() }
                            val wDp = with(density) { rect.width.toDp() }
                            val hDp = with(density) { rect.height.toDp() }
                            Box(
                                modifier = Modifier
                                    .offset(x = xDp, y = yDp)
                                    .width(wDp)
                                    .height(hDp)
                                    .zIndex(10f)
                            ) {
                                EnhancedQueueTrackItem(
                                    track = overlayTrack,
                                    isCurrentlyPlaying = dragOverlayIndex == queueState.currentIndex,
                                    isDragging = true,
                                    isMarkedPlayNext = false,
                                    position = dragOverlayIndex + 1,
                                    onClick = {},
                                    reorderState = state,
                                    config = dragConfig
                                )
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
                            Text(when (queueState.repeatMode) {
                                1 -> "Repeat All"
                                2 -> "Repeat One"
                                else -> "No Repeat"
                            })
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
                                leadingIcon = { Icon(Icons.Rounded.Clear, contentDescription = null) },
                                onClick = {
                                    onClearQueue(false)
                                    showClearMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Keep Current") },
                                leadingIcon = { Icon(Icons.Rounded.SkipNext, contentDescription = null) },
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
