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
import com.musify.mu.util.toMediaItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.MoreVert
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
import com.musify.mu.ui.components.EnhancedSwipeableItem
import com.musify.mu.ui.components.CompactTrackRow
import com.musify.mu.playback.LocalPlaybackMediaId
import com.musify.mu.playback.LocalIsPlaying
import com.musify.mu.playback.QueueContextHelper
import com.musify.mu.playback.rememberQueueOperations
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistDetailsScreen(navController: NavController, artist: String, onPlay: (List<Track>, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var showArtPicker by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val uriStr = uri.toString()
            scope.launch {
                withContext(Dispatchers.IO) {
                    tracks.forEach { t ->
                        try {
                            repo.updateTrackArt(t.mediaId, uriStr)
                            com.musify.mu.util.OnDemandArtworkLoader.cacheUri(t.mediaId, uriStr)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    LaunchedEffect(artist) {
        val all = repo.getAllTracks()
        tracks = all.filter { it.artist.equals(artist, ignoreCase = true) || it.artist.contains(artist, ignoreCase = true) }
    }

    Scaffold { padding ->
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No songs found")
            }
        } else {
            // Choose a single album-based cover (most frequent album's art)
            val albumArt = remember(tracks) {
                val grouped = tracks.groupBy { it.album }
                val top = grouped.maxByOrNull { it.value.size }?.value
                top?.firstOrNull { it.artUri != null }?.artUri
            }

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
                    // Large cover header with mosaic
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                            if (albumArt != null) {
                                Artwork(data = albumArt, mediaUri = tracks.firstOrNull()?.mediaId, albumId = null, contentDescription = null, modifier = Modifier.fillMaxSize(), enableOnDemand = true)
                            } else {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(artist, style = MaterialTheme.typography.headlineSmall)
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
                                    DropdownMenuItem(text = { Text("Change artwork") }, onClick = {
                                        expanded = false
                                        showArtPicker = true
                                    })
                                    DropdownMenuItem(text = { Text("Edit artist info") }, onClick = {
                                        expanded = false
                                        showEditDialog = true
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
                            // Compact mosaic indicator
                            Box(modifier = Modifier.size(32.dp)) {
                                if (albumArt != null) {
                                    Artwork(data = albumArt, mediaUri = tracks.firstOrNull()?.mediaId, albumId = null, contentDescription = null, modifier = Modifier.fillMaxSize(), enableOnDemand = true)
                                } else {
                                    Box(Modifier.fillMaxSize().background(Color.LightGray))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(artist, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
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

                    EnhancedSwipeableItem(
                        onSwipeRight = {
                            // Swipe right: Play Next
                            val ctx = QueueContextHelper.createArtistContext(t.artist, t.artist)
                            scope.launch { queueOps.playNextWithContext(items = listOf(t.toMediaItem()), context = ctx) }
                        },
                        onSwipeLeft = {
                            // Swipe left: Add to User Queue
                            val ctx = QueueContextHelper.createArtistContext(t.artist, t.artist)
                            scope.launch { queueOps.addToUserQueueWithContext(items = listOf(t.toMediaItem()), context = ctx) }
                        },
                        isInQueue = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CompactTrackRow(
                            title = t.title,
                            subtitle = t.album,
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

    if (showArtPicker) {
        LaunchedEffect(Unit) {
            showArtPicker = false
            pickImageLauncher.launch("image/*")
        }
    }

    if (showEditDialog) {
        var newArtist by remember { mutableStateOf(artist) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showEditDialog = false
                    // Update artist tag for all tracks
                    scope.launch(Dispatchers.IO) {
                        tracks.forEach { t ->
                            try {
                                com.musify.mu.util.MetadataWriter.writeTags(
                                    context = context,
                                    mediaUriString = t.mediaId,
                                    title = null,
                                    artist = if (newArtist.isNotBlank()) newArtist else null,
                                    album = null
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel") } },
            title = { Text("Edit artist info") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newArtist, onValueChange = { newArtist = it }, label = { Text("Artist name") }, singleLine = true)
                }
            }
        )
    }
}


