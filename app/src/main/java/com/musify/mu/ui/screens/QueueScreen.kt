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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    val controller = LocalMediaController.current
    var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val state = rememberReorderableLazyListState(onMove = { from, to ->
        queue = queue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        val fromIdx = from.index
        val toIdx = to.index
        if (fromIdx != toIdx) {
            controller?.moveMediaItem(fromIdx, toIdx)
        }
    })

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

    // Background gradient
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
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
            // Queue header with stats
            QueueHeader(queueSize = queue.size, currentIndex = currentIndex)
            
            if (queue.isEmpty()) {
                EmptyQueueMessage()
            } else {
                LazyColumn(
                    state = state.listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .reorderable(state),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(queue, key = { idx, item -> item.mediaId }) { idx, track ->
                        val dismissState = rememberDismissState(confirmStateChange = { value ->
                            when (value) {
                                DismissValue.DismissedToEnd -> {
                                    val insertIndex = ((controller?.currentMediaItemIndex ?: -1) + 1)
                                        .coerceAtMost(controller?.mediaItemCount ?: 0)
                                    controller?.removeMediaItem(idx)
                                    controller?.addMediaItem(insertIndex, track.toMediaItem())
                                    true
                                }
                                DismissValue.DismissedToStart -> {
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
                                ReorderableItem(state, key = track.mediaId) { isDragging ->
                                    QueueTrackItem(
                                        track = track,
                                        isCurrentlyPlaying = idx == currentIndex,
                                        isDragging = isDragging,
                                        onClick = { controller?.seekToDefaultPosition(idx) },
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
private fun QueueHeader(queueSize: Int, currentIndex: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = "Now Playing Queue",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "$queueSize ${if (queueSize == 1) "song" else "songs"} • Position ${currentIndex + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
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
private fun SwipeBackground(dismissDirection: DismissDirection?) {
    val color = when (dismissDirection) {
        DismissDirection.StartToEnd -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
        DismissDirection.EndToStart -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        null -> androidx.compose.ui.graphics.Color.Transparent
    }
    
    val icon = when (dismissDirection) {
        DismissDirection.StartToEnd -> Icons.Default.Delete
        DismissDirection.EndToStart -> Icons.Rounded.QueueMusic
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
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 20.dp),
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
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
    onClick: () -> Unit,
    reorderState: ReorderableLazyListState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDragging) 12.dp else 2.dp,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrentlyPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                isDragging -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album artwork
            Card(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                AsyncImage(
                    model = track.artUri,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isCurrentlyPlaying) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            // Current playing indicator
            if (isCurrentlyPlaying) {
                Icon(
                    imageVector = Icons.Rounded.QueueMusic,
                    contentDescription = "Currently playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            // Drag handle
            IconButton(
                onClick = { },
                modifier = Modifier.detectReorderAfterLongPress(reorderState)
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
