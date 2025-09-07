package com.musify.mu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import androidx.compose.material.icons.Icons
import androidx.hilt.navigation.compose.hiltViewModel
import com.musify.mu.ui.viewmodels.LibraryViewModel
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DragHandle
import kotlinx.coroutines.launch
import com.musify.mu.ui.components.TrackPickerSheet
import org.burnoutcrew.reorderable.*
import org.burnoutcrew.reorderable.ItemPosition

import android.content.ContentUris
import android.provider.MediaStore

import androidx.compose.material.ExperimentalMaterialApi
import com.musify.mu.playback.rememberQueueOperations
import com.musify.mu.playback.QueueContextHelper
import com.musify.mu.util.toMediaItem
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun PlaylistDetailsScreen(navController: NavController, playlistId: Long, onPlay: (List<Track>, Int) -> Unit) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var title by remember { mutableStateOf("Playlist") }
    val scope = rememberCoroutineScope()
    val queueOps = rememberQueueOperations()
    var showPicker by remember { mutableStateOf(false) }
    var allTracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(playlistId) {
        tracks = viewModel.playlistTracks(playlistId)
        title = viewModel.playlists().find { it.id == playlistId }?.name ?: "Playlist"
        // Load all tracks so TrackPicker has something to show even if the library screen wasn't opened yet
        allTracks = viewModel.getAllTracksSnapshot()
    }

    // Separate visual state from actual data state for playlists
    var visualTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isDragging by remember { mutableStateOf(false) }

    // Visual state for remove operations
    var removeOperations by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isProcessingRemoval by remember { mutableStateOf(false) }

    // Sync visual tracks with actual tracks when not dragging
    LaunchedEffect(tracks) {
        if (!isDragging) {
            visualTracks = tracks
        }
    }

    // Enhanced drag configuration for playlist reordering
    val dragConfig = remember {
        com.musify.mu.ui.components.DragDropConfig(
            enableHardwareAcceleration = true,
            enableLightweightShadow = true,
            enableHapticFeedback = true
        )
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Update ONLY visual state for smooth UI feedback
            visualTracks = visualTracks.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            isDragging = true
        },
        onDragEnd = { from, to ->
            val fromIdx = from
            val toIdx = to
            if (fromIdx != toIdx) {
                scope.launch {
                    try {
                        // Commit new visual order to real tracks
                        tracks = visualTracks.toList()
                        val mediaIds = tracks.map { it.mediaId }
                        // Persist order in DB
                        viewModel.reorderPlaylist(playlistId, mediaIds)
                    } catch (e: Exception) {
                        // Revert on failure
                        visualTracks = tracks
                    } finally {
                        isDragging = false
                    }
                }
            } else {
                isDragging = false
                visualTracks = tracks
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, actions = {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            })
        }
    ) { padding ->
        // Track on-screen bounds for overlay positioning during drag
        val itemBounds = remember { mutableStateMapOf<String, Rect>() }
        var dragOverlayKey by remember { mutableStateOf<String?>(null) }
        var dragOverlayTrack by remember { mutableStateOf<Track?>(null) }
        var dragOverlayIndex by remember { mutableStateOf(0) }
        val density = LocalDensity.current

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Prefetch artwork for visible items in playlist
            LaunchedEffect(reorderState.listState, visualTracks) {
                snapshotFlow { reorderState.listState.layoutInfo.visibleItemsInfo.map { it.index } }
                    .distinctUntilChanged()
                    .collect { visible ->
                        if (visible.isEmpty() || visualTracks.isEmpty()) return@collect
                        val min = (visible.minOrNull() ?: 0) - 2
                        val max = (visible.maxOrNull() ?: 0) + 8
                        val start = min.coerceAtLeast(0)
                        val end = max.coerceAtMost(visualTracks.lastIndex)
                        if (start <= end) {
                            val ids = visualTracks.subList(start, end + 1).map { it.mediaId }
                            viewModel.prefetchArtwork(ids)
                        }
                    }
            }
            LazyColumn(
                state = reorderState.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .reorderable(reorderState),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visualTracks.size, key = { idx -> "playlist_${playlistId}_${visualTracks[idx].mediaId}" }) { idx ->
                    val track = visualTracks[idx]
                    var showMenu by remember { mutableStateOf(false) }

                    ReorderableItem(reorderState, key = "playlist_${playlistId}_${track.mediaId}") { isDragging ->
                        com.musify.mu.ui.components.EnhancedSwipeableItem(
                            onSwipeRight = {
                                // Right swipe: Play Next
                                val ctx = QueueContextHelper.createPlaylistContext(playlistId.toString(), title)
                                scope.launch { queueOps.playNextWithContext(items = listOf(track.toMediaItem()), context = ctx) }
                            },
                            onSwipeLeft = {
                                // Left swipe: Add to User Queue
                                val ctx = QueueContextHelper.createPlaylistContext(playlistId.toString(), title)
                                scope.launch { queueOps.addToUserQueueWithContext(items = listOf(track.toMediaItem()), context = ctx) }
                            },
                            isInQueue = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Card(
                                modifier = Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        // Hardware acceleration for smooth drag animations
                                        if (dragConfig.enableHardwareAcceleration) {
                                            compositingStrategy = if (isDragging) {
                                                androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                            } else {
                                                androidx.compose.ui.graphics.CompositingStrategy.Auto
                                            }
                                        }
                                        scaleX = if (isDragging) 1.02f else 1f
                                        scaleY = if (isDragging) 1.02f else 1f
                                        // Keep original visible during drag per request
                                        alpha = 1f
                                    },
                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = if (isDragging) {
                                        if (dragConfig.enableLightweightShadow) 6.dp else 8.dp
                                    } else 2.dp
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDragging)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else
                                        MaterialTheme.colorScheme.surface
                                )
                            ) {
                                // Update overlay source on drag state changes
                                LaunchedEffect(isDragging) {
                                    val key = "playlist_${playlistId}_${track.mediaId}"
                                    if (isDragging) {
                                        dragOverlayKey = key
                                        dragOverlayTrack = track
                                        dragOverlayIndex = idx
                                    } else if (dragOverlayKey == key) {
                                        dragOverlayKey = null
                                        dragOverlayTrack = null
                                    }
                                }
                                // Capture bounds for overlay placement
                                Box(
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        itemBounds["playlist_${playlistId}_${track.mediaId}"] = coords.boundsInRoot()
                                    }
                                ) {
                                    val isPlaying = com.musify.mu.playback.LocalPlaybackMediaId.current == track.mediaId && com.musify.mu.playback.LocalIsPlaying.current
                                    com.musify.mu.ui.components.CompactTrackRow(
                                        title = track.title,
                                        subtitle = track.artist,
                                        artData = track.artUri,
                                        mediaUri = track.mediaId,
                                        contentDescription = track.title,
                                        isPlaying = isPlaying,
                                        onClick = { onPlay(visualTracks, idx) },
                                        trailingContent = {
                                            Box {
                                                IconButton(onClick = { showMenu = true }) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                                }
                                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                                    DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = {
                                                        // Visual-only removal - immediate UI feedback
                                                        removeOperations = removeOperations + track.mediaId
                                                        visualTracks = visualTracks.filter { it.mediaId != track.mediaId }
                                                        showMenu = false

                                                        // Perform actual removal in background
                                                        scope.launch {
                                                            try {
                                                                viewModel.removeFromPlaylist(playlistId, track.mediaId)
                                                                // Reload actual tracks from database
                                                                val newTracks = viewModel.playlistTracks(playlistId)
                                                                tracks = newTracks
                                                                // Success - remove from pending operations
                                                                removeOperations = removeOperations - track.mediaId
                                                            } catch (e: Exception) {
                                                                // On failure, revert visual state
                                                                visualTracks = tracks
                                                                removeOperations = removeOperations - track.mediaId
                                                                // Show error feedback
                                                            }
                                                        }
                                                    })
                                                }
                                            }
                                        }
                                    )
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
                    }
                }
            }

            // Floating overlay removed per request
        }
    }

    if (showPicker) {
        TrackPickerSheet(allTracks = allTracks, onDismiss = { showPicker = false }) { ids ->
            scope.launch {
                viewModel.addToPlaylist(playlistId, ids)
                tracks = viewModel.playlistTracks(playlistId)
                showPicker = false
            }
        }
    }
}