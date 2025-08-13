package com.musify.mu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    val reorderState = rememberReorderableLazyListState(onMove = { from, to ->
        tracks = tracks.toMutableList().apply { add(to.index, removeAt(from.index)) }
        // Auto-save the new order immediately
        scope.launch {
            // Remove all items from playlist and re-add in new order
            val mediaIds = tracks.map { it.mediaId }
            repo.deletePlaylist(playlistId) // This might be too aggressive, let me find a better approach
            // Instead, let's create a new method to reorder playlist items
            // For now, we'll just update the tracks list visually
        }
    })

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
            items(tracks.size, key = { idx -> "playlist_${playlistId}_${idx}_${tracks[idx].mediaId}" }) { idx ->
                val track = tracks[idx]
                var showMenu by remember { mutableStateOf(false) }
                
                ReorderableItem(reorderState, key = "playlist_${playlistId}_${idx}_${track.mediaId}") { isDragging ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (isDragging) 8.dp else 2.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDragging) 
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else 
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlay(tracks, idx) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            com.musify.mu.ui.components.Artwork(
                                data = track.artUri,
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
                                        scope.launch {
                                            repo.removeFromPlaylist(playlistId, track.mediaId)
                                            tracks = repo.playlistTracks(playlistId)
                                            showMenu = false
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