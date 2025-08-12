package com.musify.mu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.musify.mu.ui.components.TopBar
import com.musify.mu.data.db.entities.Track
import com.musify.mu.playback.LocalMediaController
import androidx.compose.foundation.lazy.itemsIndexed
import org.burnoutcrew.reorderable.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    val controller = LocalMediaController.current
    var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }

    val state = rememberReorderableLazyListState(onMove = { from, to ->
        // Update local queue and apply to player
        queue = queue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        // Apply reorder to controller
        val fromIdx = from.index
        val toIdx = to.index
        if (fromIdx != toIdx) {
            controller?.moveMediaItem(fromIdx, toIdx)
        }
    })

    LaunchedEffect(controller) {
        controller?.let { c ->
            // Initial load
            queue = (0 until c.mediaItemCount).mapNotNull { idx -> c.getMediaItemAt(idx)?.toTrack() }
            currentIndex = c.currentMediaItemIndex
            // Listen to changes
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

    Scaffold(
        topBar = {
            TopBar(title = "Queue")
        }
    ) { paddingValues ->
        LazyColumn(
            state = state.listState,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .reorderable(state)
                .detectReorder(afterLongPress = true),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(queue, key = { idx, item -> item.mediaId }) { idx, track ->
                ReorderableItem(state, key = track.mediaId) { isDragging ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (idx == currentIndex) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
                            .clickable { controller?.seekTo(idx) }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row {
                            AsyncImage(
                                model = track.artUri,
                                contentDescription = track.title,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (idx == currentIndex) MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current
                                )
                                Text(track.artist, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row {
                            IconButton(onClick = { controller?.removeMediaItem(idx) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove from Queue")
                            }
                            IconButton(onClick = { /* Add menu: play next / add to end for future */ }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                        }
                    }
                }
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
