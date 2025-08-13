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
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
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
import com.musify.mu.data.db.entities.Track
import com.musify.mu.playback.LocalMediaController
import androidx.compose.foundation.lazy.itemsIndexed
import org.burnoutcrew.reorderable.*
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.rememberDismissState
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    val controller = LocalMediaController.current
    val hapticFeedback = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Use the enhanced QueueManager for real-time updates
    var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    var queueState by remember { mutableStateOf(com.musify.mu.playback.QueueManager.QueueState()) }
    
    // Real-time queue updates
    LaunchedEffect(controller) {
        controller?.let { c ->
            // Initial load
            queue = (0 until c.mediaItemCount).mapNotNull { idx -> c.getMediaItemAt(idx)?.toTrack() }
            currentIndex = c.currentMediaItemIndex
            
            // Add listener for real-time updates
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    currentIndex = c.currentMediaItemIndex
                }
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    queue = (0 until c.mediaItemCount).mapNotNull { idx -> c.getMediaItemAt(idx)?.toTrack() }
                }
            }
            c.addListener(listener)
            
            // Cleanup listener
            scope.launch {
                try {
                    // Keep the listener active
                } finally {
                    c.removeListener(listener)
                }
            }
        }
    }

    val state = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Provide immediate UI feedback
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            
            // Update local state immediately for smooth UX
            queue = queue.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            
            // Update the actual queue
            val fromIdx = from.index
            val toIdx = to.index
            if (fromIdx != toIdx) {
                scope.launch {
                    try {
                        controller?.moveMediaItem(fromIdx, toIdx)
                    } catch (e: Exception) {
                        // Revert local change if move fails
                        queue = queue.toMutableList().apply {
                            add(fromIdx, removeAt(toIdx))
                        }
                    }
                }
            }
        },
        onDragEnd = { _, _ ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
            // Enhanced queue header with controls
            EnhancedQueueHeader(
                queueSize = queue.size,
                currentIndex = currentIndex,
                queueState = queueState,
                onShuffleToggle = { enabled ->
                    scope.launch {
                        controller?.shuffleModeEnabled = enabled
                    }
                }
            )
            
            if (queue.isEmpty()) {
                EmptyQueueMessage()
            } else {
                LazyColumn(
                    state = state.listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .reorderable(state),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        queue, 
                        key = { idx, item -> "queue_${idx}_${item.mediaId}" }
                    ) { idx, track ->
                        val dismissState = rememberDismissState(
                            confirmStateChange = { value ->
                                when (value) {
                                    DismissValue.DismissedToEnd -> {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Move to play next
                                        val insertIndex = ((controller?.currentMediaItemIndex ?: -1) + 1)
                                            .coerceAtMost(controller?.mediaItemCount ?: 0)
                                        scope.launch {
                                            try {
                                                controller?.removeMediaItem(idx)
                                                controller?.addMediaItem(insertIndex, track.toMediaItem())
                                                snackbarHostState.showSnackbar(
                                                    message = "Moved to play next",
                                                    duration = SnackbarDuration.Short
                                                )
                                            } catch (e: Exception) {
                                                snackbarHostState.showSnackbar(
                                                    message = "Failed to move track",
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        }
                                        true
                                    }
                                    DismissValue.DismissedToStart -> {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Remove from queue
                                        scope.launch {
                                            try {
                                                controller?.removeMediaItem(idx)
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Removed \"${track.title}\" from queue",
                                                    actionLabel = "Undo",
                                                    duration = SnackbarDuration.Long
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    controller?.addMediaItem(idx, track.toMediaItem())
                                                }
                                            } catch (e: Exception) {
                                                snackbarHostState.showSnackbar(
                                                    message = "Failed to remove track",
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        }
                                        true
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
                                ReorderableItem(state, key = "queue_${idx}_${track.mediaId}") { isDragging ->
                                    EnhancedQueueTrackItem(
                                        track = track,
                                        isCurrentlyPlaying = idx == currentIndex,
                                        isDragging = isDragging,
                                        position = idx + 1,
                                        onClick = { 
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            controller?.seekToDefaultPosition(idx) 
                                        },
                                        reorderState = state
                                    )
                                }
                            }
                        )
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
    queueState: com.musify.mu.playback.QueueManager.QueueState,
    onShuffleToggle: (Boolean) -> Unit
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
            
            // Queue controls
            if (queueSize > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                
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
                }
            }
        }
    }
}

@Composable
private fun EmptyQueueMessage() {
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

@Composable
private fun EnhancedQueueTrackItem(
    track: Track,
    isCurrentlyPlaying: Boolean,
    isDragging: Boolean,
    position: Int,
    onClick: () -> Unit,
    reorderState: ReorderableLazyListState
) {
    val animatedElevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 2.dp,
        animationSpec = tween(200),
        label = "drag_elevation"
    )
    
    val animatedScale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "drag_scale"
    )
    
    val containerColor = if (isCurrentlyPlaying) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(animatedElevation, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Position indicator
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (isCurrentlyPlaying) 
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else 
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = position.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isCurrentlyPlaying) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Track artwork
            AsyncImage(
                model = track.artUri,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Track info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrentlyPlaying) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (isCurrentlyPlaying) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Drag handle
            IconButton(
                onClick = { },
                modifier = Modifier
                    .detectReorderAfterLongPress(reorderState)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Reorder",
                    tint = if (isDragging) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
