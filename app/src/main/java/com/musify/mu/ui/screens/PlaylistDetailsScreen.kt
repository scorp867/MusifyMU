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
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DragHandle
import kotlinx.coroutines.launch
import com.musify.mu.ui.components.TrackPickerSheet
import org.burnoutcrew.reorderable.*
import org.burnoutcrew.reorderable.ItemPosition
import android.content.ContentUris
import android.provider.MediaStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailsScreen(navController: NavController, playlistId: Long, onPlay: (List<Track>, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var title by remember { mutableStateOf("Playlist") }
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var allTracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(playlistId) {
        tracks = repo.playlistTracks(playlistId)
        title = repo.playlists().find { it.id == playlistId }?.name ?: "Playlist"
        allTracks = repo.getAllTracks()
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
            longPressTimeout = 300L,
            animationDuration = 150,
            enableHardwareAcceleration = true,
            enableAutoScroll = true,
            autoScrollThreshold = 70f
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
            val fromIdx = from  // from is Int in onDragEnd  
            val toIdx = to      // to is Int in onDragEnd
            
            if (fromIdx != toIdx) {
                // Commit actual data change when drag completes
        scope.launch {
                    try {
                        // Update actual tracks data
                        tracks = visualTracks.toList()
                        
                        // TODO: Implement efficient playlist reordering in repository
            val mediaIds = tracks.map { it.mediaId }
                        // For now, we maintain the tracks list state
                        
                    } catch (e: Exception) {
                        // On failure, revert visual state to match actual data
                        visualTracks = tracks
                    } finally {
                        isDragging = false
                    }
                }
            } else {
                isDragging = false
                visualTracks = tracks // Ensure sync
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
        LazyColumn(
            state = reorderState.listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .reorderable(reorderState),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visualTracks.size, key = { idx -> "playlist_${playlistId}_${idx}_${visualTracks[idx].mediaId}" }) { idx ->
                val track = visualTracks[idx]
                var showMenu by remember { mutableStateOf(false) }
                
                ReorderableItem(reorderState, key = "playlist_${playlistId}_${idx}_${track.mediaId}") { isDragging ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // Hardware acceleration for smooth drag animations
                                if (dragConfig.enableHardwareAcceleration) {
                                    compositingStrategy = if (isDragging) {
                                        CompositingStrategy.Offscreen
                                    } else {
                                        CompositingStrategy.Auto
                                    }
                                }
                                scaleX = if (isDragging) 1.02f else 1f
                                scaleY = if (isDragging) 1.02f else 1f
                                alpha = if (isDragging) 0.95f else 1f
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlay(visualTracks, idx) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val fallbackAlbumArt = track.albumId?.let { id ->
                                android.net.Uri.parse("content://media/external/audio/albumart/$id")
                            }
                            com.musify.mu.ui.components.Artwork(
                                data = track.artUri,
                                audioUri = track.mediaId,
                                albumId = track.albumId,
                                contentDescription = track.title,
                                modifier = Modifier.size(48.dp)
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            
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
                                            repo.removeFromPlaylist(playlistId, track.mediaId)
                                                // Reload actual tracks from database
                                                val newTracks = repo.playlistTracks(playlistId)
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

    if (showPicker) {
        TrackPickerSheet(allTracks = allTracks, onDismiss = { showPicker = false }) { ids ->
            scope.launch {
                repo.addToPlaylist(playlistId, ids)
                tracks = repo.playlistTracks(playlistId)
                showPicker = false
            }
        }
    }
}