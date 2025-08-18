package com.musify.mu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
 
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeeAllScreen(navController: NavController, type: String, onPlay: (List<Track>, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    var title by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(type) {
        title = when (type) {
            "recently_played" -> "Recently Played"
            "recently_added" -> "Recently Added"
            "favorites" -> "Favourites"
            else -> ""
        }
        tracks = when (type) {
            "recently_played" -> repo.recentlyPlayed(200)
            "recently_added" -> repo.recentlyAdded(200)
            "favorites" -> repo.favorites()
            else -> emptyList()
        }
    }

    val reorderState = if (type == "favorites") rememberReorderableLazyListState(
        onMove = { from, to ->
            tracks = tracks.toMutableList().apply { add(to.index, removeAt(from.index)) }
        },
        onDragEnd = { _, _ ->
            // Persist once after drop
            scope.launch {
                val order = tracks.mapIndexed { index, track ->
                    com.musify.mu.data.db.entities.FavoritesOrder(track.mediaId, index)
                }
                repo.saveFavoritesOrder(order)
            }
        }
    ) else null

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) })
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
                LazyColumn(
                    state = reorderState!!.listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .reorderable(reorderState),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                items(tracks.size, key = { idx -> "seeall_${type}_${tracks[idx].mediaId}" }) { idx ->
                    val track = tracks[idx]
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlay(tracks, idx) }
                                    .onGloballyPositioned { coords ->
                                        itemBounds["seeall_${type}_${track.mediaId}"] = coords.boundsInRoot()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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

                // Floating overlay removed per request
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tracks.size) { idx ->
                    val track = tracks[idx]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        }
                    }
                }
            }
        }
    }
}