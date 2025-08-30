package com.musify.mu.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.ui.components.Artwork
import com.musify.mu.playback.QueueContextHelper
import com.musify.mu.playback.rememberQueueOperations
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailsScreen(navController: NavController, album: String, artist: String, onPlay: (List<Track>, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(album, artist) {
        val all = repo.getAllTracks()
        tracks = all.filter { it.album.equals(album, ignoreCase = true) || it.album.contains(album, ignoreCase = true) }
            .let { byAlbum ->
                if (artist.isBlank()) byAlbum else byAlbum.filter { it.artist.equals(artist, ignoreCase = true) || it.artist.contains(artist, ignoreCase = true) }
            }
    }

    Scaffold { padding ->
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No songs found")
            }
        } else {
            // Choose a single album art (from the album group)
            val albumArt = remember(tracks) { tracks.firstOrNull { it.artUri != null }?.artUri }

            val listState = rememberLazyListState()

            // Prefetch artwork for visible tracks
            LaunchedEffect(listState, tracks) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                    .distinctUntilChanged()
                    .collectLatest { visibleIndices ->
                        val visibleTracks = visibleIndices.mapNotNull { index ->
                            // Account for header item
                            tracks.getOrNull(index - 1)
                        }
                        val mediaUris = visibleTracks.mapNotNull { it.mediaId }
                        if (mediaUris.isNotEmpty()) {
                            com.musify.mu.util.OnDemandArtworkLoader.prefetch(mediaUris)
                        }
                    }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // Large cover header with album art
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                            if (albumArt != null) {
                                Artwork(data = albumArt, mediaUri = tracks.firstOrNull()?.mediaId, albumId = null, contentDescription = null, modifier = Modifier.fillMaxSize(), enableOnDemand = true)
                            } else {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(album, style = MaterialTheme.typography.headlineSmall)
                        if (artist.isNotBlank()) {
                            Text(artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("${tracks.size} ${if (tracks.size == 1) "song" else "songs"}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { onPlay(tracks, 0) }) { Text("Play all") }
                            OutlinedButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks.shuffled(), 0) }) { Text("Shuffle") }
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    val scope = rememberCoroutineScope()
                                    DropdownMenuItem(text = { Text("Change artwork") }, onClick = {
                                        expanded = false
                                        // Navigate to NowPlaying's picker-like flow is not ideal here; keep placeholder for future
                                        // For now, this can be expanded to open an image picker and update all album tracks art
                                    })
                                    DropdownMenuItem(text = { Text("Edit album info") }, onClick = {
                                        expanded = false
                                        // Placeholder for future album info edit (no separate album table in current schema)
                                    })
                                }
                            }
                        }
                    }
                }

                stickyHeader {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = "Back")
                            }
                            Box(modifier = Modifier.size(32.dp)) {
                                if (albumArt != null) {
                                    Artwork(data = albumArt, mediaUri = tracks.firstOrNull()?.mediaId, albumId = null, contentDescription = null, modifier = Modifier.fillMaxSize(), enableOnDemand = true)
                                } else {
                                    Box(Modifier.fillMaxSize().background(Color.LightGray))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(album, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                            IconButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play all") }
                            IconButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks.shuffled(), 0) }) { Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle") }
                        }
                    }
                }

                itemsIndexed(tracks, key = { _, t -> t.mediaId }) { index, t ->
                    val isPlaying = com.musify.mu.playback.LocalPlaybackMediaId.current == t.mediaId && com.musify.mu.playback.LocalIsPlaying.current

                    // Add queue operations for swipe gestures
                    val queueOps = rememberQueueOperations()
                    val scope = rememberCoroutineScope()

                    com.musify.mu.ui.components.EnhancedSwipeableItem(
                        onSwipeRight = {
                            // Swipe right: Play Next
                            val ctx = QueueContextHelper.createAlbumContext(t.albumId.toString(), album)
                            scope.launch { queueOps.playNextWithContext(items = listOf(t.toMediaItem()), context = ctx) }
                        },
                        onSwipeLeft = {
                            // Swipe left: Add to User Queue
                            val ctx = QueueContextHelper.createAlbumContext(t.albumId.toString(), album)
                            scope.launch { queueOps.addToUserQueueWithContext(items = listOf(t.toMediaItem()), context = ctx) }
                        },
                        isInQueue = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        com.musify.mu.ui.components.CompactTrackRow(
                            title = t.title,
                            subtitle = t.artist,
                            artData = t.artUri,
                            mediaUri = t.mediaId,
                            contentDescription = t.title,
                            isPlaying = isPlaying,
                            useGlass = true,
                            showIndicator = (com.musify.mu.playback.LocalPlaybackMediaId.current == t.mediaId),
                            onClick = { onPlay(tracks, index) }
                        )
                    }
                }
            }
        }
    }
}


