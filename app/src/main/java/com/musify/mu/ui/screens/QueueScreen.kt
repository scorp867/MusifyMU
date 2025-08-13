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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musify.mu.playback.PlayerService
import com.musify.mu.playback.QueueManager
import com.musify.mu.playback.QueueManagerProvider
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    val controller = LocalMediaController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Get QueueManager from provider
    val queueManager = remember { QueueManagerProvider.get() }
    
    // Observe queue state for real-time updates
    val queueState by (queueManager?.queueStateFlow ?: remember { 
        kotlinx.coroutines.flow.MutableStateFlow(QueueManager.QueueState()) 
    }).collectAsStateWithLifecycle()
    
    // Convert queue items to tracks for display
    val currentTrack = queueState.currentTrack?.mediaItem?.toTrack()
    val upNextTracks = queueState.upNext.map { it.mediaItem.toTrack() }
    
    // Combine all queue items for full list
    val allQueueItems = remember(queueState) {
        mutableListOf<QueueSectionItem>().apply {
            // Current track
            queueState.currentTrack?.let {
                add(QueueSectionItem.Header("Now Playing"))
                add(QueueSectionItem.TrackItem(it.mediaItem.toTrack(), 0, QueueSection.CURRENT))
            }
            
            // Play Next items
            if (queueState.playNextCount > 0) {
                add(QueueSectionItem.Header("Playing Next"))
                // In real implementation, we'd get these from QueueManager
                // For now, we'll show from the upNext list
                upNextTracks.take(queueState.playNextCount).forEachIndexed { index, track ->
                    add(QueueSectionItem.TrackItem(track, index + 1, QueueSection.PLAY_NEXT))
                }
            }
            
            // Play Soon items
            if (queueState.playSoonCount > 0) {
                add(QueueSectionItem.Header("Playing Soon"))
                upNextTracks.drop(queueState.playNextCount).take(queueState.playSoonCount).forEachIndexed { index, track ->
                    add(QueueSectionItem.TrackItem(track, index + queueState.playNextCount + 1, QueueSection.PLAY_SOON))
                }
            }
            
            // Smart Queue items
            if (queueState.smartQueueCount > 0) {
                add(QueueSectionItem.Header("Suggested for You"))
                // Show smart queue items with special indicator
            }
            
            // Regular queue
            val regularStart = queueState.playNextCount + queueState.playSoonCount + queueState.smartQueueCount
            if (upNextTracks.size > regularStart) {
                add(QueueSectionItem.Header("Next in Queue"))
                upNextTracks.drop(regularStart).forEachIndexed { index, track ->
                    add(QueueSectionItem.TrackItem(track, index + regularStart + 1, QueueSection.REGULAR))
                }
            }
        }
    }

    val state = rememberReorderableLazyListState(onMove = { from, to ->
        // Only allow reordering within sections
        val fromItem = allQueueItems.getOrNull(from.index)
        val toItem = allQueueItems.getOrNull(to.index)
        
        if (fromItem is QueueSectionItem.TrackItem && toItem is QueueSectionItem.TrackItem &&
            fromItem.section == toItem.section && fromItem.section != QueueSection.CURRENT) {
            controller?.moveMediaItem(fromItem.actualIndex, toItem.actualIndex)
        }
    })

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
            // Enhanced queue header with controls
            EnhancedQueueHeader(
                queueState = queueState,
                onShuffleClick = { 
                    scope.launch {
                        queueManager?.setShuffle(!queueState.isShuffled)
                    }
                },
                onRadioClick = {
                    scope.launch {
                        if (queueState.isRadioMode) {
                            queueManager?.disableRadioMode()
                        } else {
                            queueManager?.enableRadioMode()
                        }
                    }
                },
                onHistoryClick = {
                    // Navigate to history screen or show history bottom sheet
                }
            )
            
            if (allQueueItems.isEmpty()) {
                EmptyQueueMessage()
            } else {
                LazyColumn(
                    state = state.listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .reorderable(state),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(allQueueItems, key = { item ->
                        when (item) {
                            is QueueSectionItem.Header -> "header_${item.title}"
                            is QueueSectionItem.TrackItem -> "track_${item.section}_${item.actualIndex}_${item.track.mediaId}"
                        }
                    }) { item ->
                        when (item) {
                            is QueueSectionItem.Header -> {
                                QueueSectionHeader(title = item.title)
                            }
                            is QueueSectionItem.TrackItem -> {
                                if (item.section == QueueSection.CURRENT) {
                                    // Current track is not dismissible or reorderable
                                    CurrentTrackItem(
                                        track = item.track,
                                        onClick = { }
                                    )
                                } else {
                                    val dismissState = rememberDismissState(confirmStateChange = { value ->
                                        when (value) {
                                            DismissValue.DismissedToEnd -> {
                                                // Move to play next
                                                controller?.let { c ->
                                                    c.removeMediaItem(item.actualIndex)
                                                    c.addMediaItem(
                                                        (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount),
                                                        item.track.toMediaItem()
                                                    )
                                                }
                                                true
                                            }
                                            DismissValue.DismissedToStart -> {
                                                // Remove from queue
                                                controller?.removeMediaItem(item.actualIndex)
                                                scope.launch {
                                                    val res = snackbarHostState.showSnackbar(
                                                        message = "Removed from queue",
                                                        actionLabel = "Undo",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                    if (res == SnackbarResult.ActionPerformed) {
                                                        controller?.addMediaItem(item.actualIndex, item.track.toMediaItem())
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
                                            ReorderableItem(state, key = "track_${item.section}_${item.actualIndex}_${item.track.mediaId}") { isDragging ->
                                                EnhancedQueueTrackItem(
                                                    track = item.track,
                                                    section = item.section,
                                                    index = item.actualIndex,
                                                    isDragging = isDragging,
                                                    onClick = { controller?.seekToDefaultPosition(item.actualIndex) },
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
        }
    }
}

// Data classes for queue sections
sealed class QueueSectionItem {
    data class Header(val title: String) : QueueSectionItem()
    data class TrackItem(
        val track: Track,
        val actualIndex: Int,
        val section: QueueSection
    ) : QueueSectionItem()
}

enum class QueueSection {
    CURRENT, PLAY_NEXT, PLAY_SOON, SMART_QUEUE, REGULAR
}

@Composable
private fun EnhancedQueueHeader(
    queueState: QueueManager.QueueState,
    onShuffleClick: () -> Unit,
    onRadioClick: () -> Unit,
    onHistoryClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
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
                            append("${queueState.totalSize} ${if (queueState.totalSize == 1) "song" else "songs"}")
                            if (queueState.playNextCount > 0) {
                                append(" • ${queueState.playNextCount} next")
                            }
                            if (queueState.isRadioMode) {
                                append(" • Radio on")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            
            // Control buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QueueControlChip(
                    icon = Icons.Default.Shuffle,
                    label = "Shuffle",
                    selected = queueState.isShuffled,
                    onClick = onShuffleClick
                )
                
                QueueControlChip(
                    icon = Icons.Default.Radio,
                    label = "Radio",
                    selected = queueState.isRadioMode,
                    onClick = onRadioClick
                )
                
                if (queueState.hasHistory) {
                    QueueControlChip(
                        icon = Icons.Default.History,
                        label = "History",
                        selected = false,
                        onClick = onHistoryClick
                    )
                }
                
                if (queueState.smartQueueCount > 0) {
                    QueueControlChip(
                        icon = Icons.Default.SmartToy,
                        label = "Smart",
                        selected = true,
                        onClick = { },
                        enabled = false
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueControlChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun QueueSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp)
    )
}

@Composable
private fun CurrentTrackItem(
    track: Track,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album artwork with playing indicator
            Box {
                Card(
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    AsyncImage(
                        model = track.artUri,
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                // Playing indicator overlay
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.PlaylistPlay,
                        contentDescription = "Currently playing",
                        modifier = Modifier
                            .padding(4.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun EnhancedQueueTrackItem(
    track: Track,
    section: QueueSection,
    index: Int,
    isDragging: Boolean,
    onClick: () -> Unit,
    reorderState: ReorderableLazyListState
) {
    val sectionColor = when (section) {
        QueueSection.PLAY_NEXT -> MaterialTheme.colorScheme.tertiary
        QueueSection.PLAY_SOON -> MaterialTheme.colorScheme.secondary
        QueueSection.SMART_QUEUE -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .shadow(
                elevation = if (isDragging) 12.dp else 2.dp,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            } else {
                sectionColor.copy(alpha = 0.1f)
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
            // Section indicator
            if (section != QueueSection.REGULAR) {
                Surface(
                    modifier = Modifier.size(4.dp, 40.dp),
                    color = sectionColor,
                    shape = RoundedCornerShape(2.dp)
                ) { }
                Spacer(modifier = Modifier.width(12.dp))
            }
            
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
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                
                // Section label for smart queue
                if (section == QueueSection.SMART_QUEUE) {
                    Text(
                        text = "Suggested",
                        style = MaterialTheme.typography.labelSmall,
                        color = sectionColor,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
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
