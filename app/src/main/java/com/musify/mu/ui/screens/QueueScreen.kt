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
import androidx.compose.material.icons.rounded.DragHandle
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.launch
import com.musify.mu.data.repo.QueueStateStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    val controller = LocalMediaController.current
    var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Play-next region state (number of items after current that are in Play Next)
    val context = androidx.compose.ui.platform.LocalContext.current
    val queueStateStore = remember { QueueStateStore(context) }
    var playNextCount by remember { mutableStateOf(0) }

    val state = rememberReorderableLazyListState(
        onMove = { from, to ->
            queue = queue.toMutableList().apply { add(to.index, removeAt(from.index)) }
            val fromIdx = from.index
            val toIdx = to.index
            if (fromIdx != toIdx) {
                controller?.moveMediaItem(fromIdx, toIdx)
            }
        }
    )

    LaunchedEffect(controller) {
        controller?.let { c ->
            queue = (0 until c.mediaItemCount).mapNotNull { idx -> c.getMediaItemAt(idx)?.toTrack() }
            currentIndex = c.currentMediaItemIndex
            // Load play-next count
            playNextCount = try { queueStateStore.getPlayNextCount() } catch (_: Exception) { 0 }
            c.addListener(object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    currentIndex = c.currentMediaItemIndex
                    // Update play-next count when advancing
                    scope.launch { playNextCount = try { queueStateStore.getPlayNextCount() } catch (_: Exception) { 0 } }
                }
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    queue = (0 until c.mediaItemCount).mapNotNull { idx -> c.getMediaItemAt(idx)?.toTrack() }
                    // Refresh play-next count on any structural change
                    scope.launch { playNextCount = try { queueStateStore.getPlayNextCount() } catch (_: Exception) { 0 } }
                }
            })
        }
    }

    Scaffold(
        topBar = {
            TopBar(title = "Queue")
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            state = state.listState,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .reorderable(state),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(queue, key = { _, item -> item.mediaId }) { idx, track ->
                val isCurrent = idx == currentIndex
                val playNextStart = (currentIndex + 1).coerceAtLeast(0)
                val inPlayNextRegion = idx in playNextStart until (playNextStart + playNextCount)
                ReorderableItem(state, key = track.mediaId) { _ ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    inPlayNextRegion -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f)
                                    else -> MaterialTheme.colorScheme.surface
                                }
                            )
                            .clickable { controller?.seekToDefaultPosition(idx) }
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
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                                val subtitle = buildString {
                                    append(track.artist)
                                    if (isCurrent) append("  •  Now playing")
                                    else if (inPlayNextRegion) append("  •  Play next")
                                }
                                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row {
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = null,
                                modifier = Modifier.detectReorder(state)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = {
                                val removedIndex = idx
                                val removedItem = track
                                controller?.removeMediaItem(removedIndex)
                                scope.launch {
                                    val res = snackbarHostState.showSnackbar(
                                        message = "Removed from queue",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (res == SnackbarResult.ActionPerformed) {
                                        controller?.addMediaItem(removedIndex, removedItem.toMediaItem())
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
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
