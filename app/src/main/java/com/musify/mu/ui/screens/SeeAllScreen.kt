package com.musify.mu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import org.burnoutcrew.reorderable.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.musify.mu.ui.viewmodels.LibraryViewModel
import kotlinx.coroutines.launch
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.rememberDismissState

import com.musify.mu.playback.rememberQueueOperations
import com.musify.mu.util.toMediaItem
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.rememberDismissState
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun SeeAllScreen(navController: NavController, type: String, onPlay: (List<Track>, Int) -> Unit) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // Optimized state management to prevent unnecessary recompositions
    val displayTitle by remember(type) {
        derivedStateOf {
            when (type) {
                "recently_played" -> "Recently Played"
                "recently_added" -> "Recently Added"
                "favorites" -> "Favourites"
                else -> ""
            }
        }
    }

    // Load tracks asynchronously
    LaunchedEffect(type) {
        tracks = when (type) {
            "recently_played" -> viewModel.recentlyPlayed(200)
            "recently_added" -> viewModel.recentlyAdded(200)
            "favorites" -> viewModel.favorites()
            else -> emptyList()
        }
    }

    val displayTracks by remember(tracks) {
        derivedStateOf { tracks }
    }

    val reorderState = if (type == "favorites") rememberReorderableLazyListState(
        onMove = { from, to ->
            val mutableTracks = displayTracks.toMutableList().apply { add(to.index, removeAt(from.index)) }
            tracks = mutableTracks
        },
        onDragEnd = { _, _ ->
            // Persist once after drop
            scope.launch {
                val order = tracks.mapIndexed { index, track ->
                    com.musify.mu.data.db.entities.FavoritesOrder(track.mediaId, index)
                }
                viewModel.saveFavoritesOrder(order)
            }
        }
    ) else null

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(displayTitle) })
        }
    ) { padding ->
        if (type == "favorites") {
            // Track bounds for floating overlay during drag
            val itemBounds = remember { mutableStateMapOf<String, Rect>() }
            var dragOverlayKey by remember { mutableStateOf<String?>(null) }
            var dragOverlayTrack by remember { mutableStateOf<Track?>(null) }
            var dragOverlayIndex by remember { mutableStateOf(0) }
            val density = LocalDensity.current

            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                // Prefetch artwork for visible items in favorites list
                LaunchedEffect(reorderState!!.listState, tracks) {
                    snapshotFlow { reorderState!!.listState.layoutInfo.visibleItemsInfo.map { it.index } }
                        .distinctUntilChanged()
                        .collect { visible ->
                            if (visible.isEmpty() || tracks.isEmpty()) return@collect
                            val min = (visible.minOrNull() ?: 0) - 2
                            val max = (visible.maxOrNull() ?: 0) + 8
                            val start = min.coerceAtLeast(0)
                            val end = max.coerceAtMost(tracks.lastIndex)
                            if (start <= end) {
                                val ids = tracks.subList(start, end + 1).map { it.mediaId }
                                viewModel.prefetchArtwork(ids)
                            }
                        }
                }
                LazyColumn(
                    state = reorderState!!.listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .reorderable(reorderState),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        count = displayTracks.size,
                        key = { idx -> "seeall_${type}_${displayTracks[idx].mediaId}" },
                        contentType = { _ -> "seeall_fav_track" }
                    ) { idx ->
                        val track = displayTracks[idx]
                        ReorderableItem(reorderState, key = "seeall_${type}_${track.mediaId}") { isDragging ->
                            Card(
                                modifier = Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = if (isDragging) 10.dp else 2.dp
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDragging)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else
                                        MaterialTheme.colorScheme.surface
                                )
                            ) {
                                // Update overlay context while dragging
                                LaunchedEffect(isDragging) {
                                    val key = "seeall_${type}_${track.mediaId}"
                                    if (isDragging) {
                                        dragOverlayKey = key
                                        dragOverlayTrack = track
                                        dragOverlayIndex = idx
                                    } else if (dragOverlayKey == key) {
                                        dragOverlayKey = null
                                        dragOverlayTrack = null
                                    }
                                }
                                val isPlaying = com.musify.mu.playback.LocalPlaybackMediaId.current == track.mediaId && com.musify.mu.playback.LocalIsPlaying.current
                                com.musify.mu.ui.components.CompactTrackRow(
                                    title = track.title,
                                    subtitle = track.artist,
                                    artData = track.artUri,
                                    mediaUri = track.mediaId,
                                    contentDescription = track.title,
                                    isPlaying = isPlaying,
                                    showIndicator = (com.musify.mu.playback.LocalPlaybackMediaId.current == track.mediaId),
                                    onClick = { onPlay(tracks, idx) },
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        itemBounds["seeall_${type}_${track.mediaId}"] = coords.boundsInRoot()
                                    },
                                    trailingContent = {
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
                                )
                            }
                        }
                    }
                }

                // Floating overlay removed per request
            }
        } else {
            val queueOps = rememberQueueOperations()
            val queueOpsScope = rememberCoroutineScope()
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            // Prefetch artwork for visible items
            LaunchedEffect(listState, tracks) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                    .distinctUntilChanged()
                    .collect { visible ->
                        if (visible.isEmpty() || tracks.isEmpty()) return@collect
                        val min = (visible.minOrNull() ?: 0) - 2
                        val max = (visible.maxOrNull() ?: 0) + 8
                        val start = min.coerceAtLeast(0)
                        val end = max.coerceAtMost(tracks.lastIndex)
                        if (start <= end) {
                            val ids = tracks.subList(start, end + 1).map { it.mediaId }
                            viewModel.prefetchArtwork(ids)
                        }
                    }
            }
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    count = displayTracks.size,
                    key = { idx -> "seeall_${type}_${displayTracks[idx].mediaId}" },
                    contentType = { _ -> "seeall_track" }
                ) { idx ->
                    val track = displayTracks[idx]
                    com.musify.mu.ui.components.EnhancedSwipeableItem(
                        onSwipeRight = {
                            // Right swipe: Play Next
                            val ctx = com.musify.mu.playback.QueueContextHelper.createDiscoverContext(type)
                            queueOpsScope.launch { queueOps.playNextWithContext(items = listOf(track.toMediaItem()), context = ctx) }
                        },
                        onSwipeLeft = {
                            // Left swipe: Add to User Queue
                            val ctx = com.musify.mu.playback.QueueContextHelper.createDiscoverContext(type)
                            queueOpsScope.launch { queueOps.addToUserQueueWithContext(items = listOf(track.toMediaItem()), context = ctx) }
                        },
                        isInQueue = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            val isPlaying = com.musify.mu.playback.LocalPlaybackMediaId.current == track.mediaId && com.musify.mu.playback.LocalIsPlaying.current
                            com.musify.mu.ui.components.CompactTrackRow(
                                title = track.title,
                                subtitle = track.artist,
                                artData = track.artUri,
                                mediaUri = track.mediaId,
                                contentDescription = track.title,
                                isPlaying = isPlaying,
                                showIndicator = (com.musify.mu.playback.LocalPlaybackMediaId.current == track.mediaId),
                                onClick = { onPlay(tracks, idx) }
                            )
                        }
                    }
                }
            }
        }
    }
}