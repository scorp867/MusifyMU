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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    val controller = LocalMediaController.current
    val haptic = LocalHapticFeedback.current
    var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Visual drag state
    var dragVisualState by remember { mutableStateOf<DragVisualState?>(null) }

    val state = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Immediate visual feedback
            dragVisualState = DragVisualState(from.index, to.index, true)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            
            queue = queue.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            
            val fromIdx = from.index
            val toIdx = to.index
            if (fromIdx != toIdx) {
                controller?.moveMediaItem(fromIdx, toIdx)
            }
        },
        onDragEnd = { _, _ ->
            // Reset visual state after drag ends
            dragVisualState = null
        }
    )

    LaunchedEffect(controller) {
        controller?.let { c ->
            queue = (0 until c.mediaItemCount).mapNotNull { idx -> c.getMediaItemAt(idx)?.toTrack() }
            currentIndex = c.currentMediaItemIndex
            c.addListener(object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    currentIndex = c.currentMediaItemIndex
                }
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    queue = (0 until c.mediaItemCount).mapNotNull { idx -> c.getMediaItemAt(idx)?.toTrack() }
                }
            })
        }
    }

    // Modern gradient background
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Compact header
            QueueHeader(
                queueSize = queue.size, 
                currentIndex = currentIndex,
                onClose = { navController.popBackStack() }
            )
            
            if (queue.isEmpty()) {
                EmptyQueueMessage()
            } else {
                LazyColumn(
                    state = state.listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .reorderable(state),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(queue, key = { idx, item -> "queue_${idx}_${item.mediaId}" }) { idx, track ->
                        val dismissState = rememberDismissState(confirmStateChange = { value ->
                            when (value) {
                                DismissValue.DismissedToEnd -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val insertIndex = ((controller?.currentMediaItemIndex ?: -1) + 1)
                                        .coerceAtMost(controller?.mediaItemCount ?: 0)
                                    controller?.removeMediaItem(idx)
                                    controller?.addMediaItem(insertIndex, track.toMediaItem())
                                    true
                                }
                                DismissValue.DismissedToStart -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    controller?.removeMediaItem(idx)
                                    val removed = track
                                    scope.launch {
                                        val res = snackbarHostState.showSnackbar(
                                            message = "Removed from queue",
                                            actionLabel = "Undo",
                                            duration = SnackbarDuration.Short
                                        )
                                        if (res == SnackbarResult.ActionPerformed) {
                                            controller?.addMediaItem(idx, removed.toMediaItem())
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        })
                        
                        SwipeToDismiss(
                            state = dismissState,
                            directions = setOf(DismissDirection.StartToEnd, DismissDirection.EndToStart),
                            background = {
                                SwipeBackground(dismissState.dismissDirection)
                            },
                            dismissContent = {
                                ReorderableItem(state, key = "queue_${idx}_${track.mediaId}") { isDragging ->
                                    QueueTrackItem(
                                        track = track,
                                        isCurrentlyPlaying = idx == currentIndex,
                                        isDragging = isDragging,
                                        dragVisualState = if (dragVisualState?.fromIndex == idx || dragVisualState?.toIndex == idx) dragVisualState else null,
                                        onClick = { 
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
        
        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

data class DragVisualState(
    val fromIndex: Int,
    val toIndex: Int,
    val isDragging: Boolean
)

@Composable
private fun QueueHeader(
    queueSize: Int, 
    currentIndex: Int,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Playing Queue",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$queueSize songs • Now playing ${currentIndex + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            // Queue actions
            IconButton(
                onClick = { /* TODO: Clear queue */ },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ClearAll,
                    contentDescription = "Clear queue",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
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
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Your queue is empty",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = "Add songs to see them here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun SwipeBackground(dismissDirection: DismissDirection?) {
    val color = when (dismissDirection) {
        DismissDirection.StartToEnd -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        DismissDirection.EndToStart -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        null -> Color.Transparent
    }
    
    val icon = when (dismissDirection) {
        DismissDirection.StartToEnd -> Icons.Default.Delete
        DismissDirection.EndToStart -> Icons.Rounded.PlaylistPlay
        null -> null
    }
    
    val text = when (dismissDirection) {
        DismissDirection.StartToEnd -> "Remove"
        DismissDirection.EndToStart -> "Play next"
        null -> ""
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 16.dp),
        contentAlignment = if (dismissDirection == DismissDirection.StartToEnd) 
            Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (dismissDirection == DismissDirection.StartToEnd) 
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = if (dismissDirection == DismissDirection.StartToEnd) 
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun QueueTrackItem(
    track: Track,
    isCurrentlyPlaying: Boolean,
    isDragging: Boolean,
    dragVisualState: DragVisualState?,
    onClick: () -> Unit,
    reorderState: ReorderableLazyListState
) {
    // Enhanced visual feedback
    val elevation by animateDpAsState(
        targetValue = when {
            isDragging -> 12.dp
            dragVisualState?.isDragging == true -> 6.dp
            else -> 1.dp
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "elevation"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrentlyPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                isDragging -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Compact album artwork
            Card(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(6.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                AsyncImage(
                    model = track.artUri,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 15.sp
                    ),
                    color = if (isCurrentlyPlaying) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
            
            // Current playing indicator
            if (isCurrentlyPlaying) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = "Now playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            // Drag handle with better visual feedback
            IconButton(
                onClick = { },
                modifier = Modifier
                    .size(32.dp)
                    .detectReorderAfterLongPress(reorderState)
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (isDragging) 0.9f else 0.5f
                    ),
                    modifier = Modifier.size(16.dp)
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
