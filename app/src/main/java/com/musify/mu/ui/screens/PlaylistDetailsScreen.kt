package com.musify.mu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import com.musify.mu.ui.components.TrackPickerSheet

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

    Scaffold(
        topBar = {
            SmallTopAppBar(title = { Text(title) }, actions = {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tracks.size) { idx ->
                val track = tracks[idx]
                var showMenu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(track.title) },
                    supportingContent = { Text(track.artist) },
                    leadingContent = {
                        com.musify.mu.ui.components.Artwork(
                            data = track.artUri,
                            contentDescription = track.title,
                            modifier = Modifier.size(48.dp)
                        )
                    },
                    trailingContent = {
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
                    },
                    modifier = Modifier.clickable { onPlay(tracks, idx) }
                )
                Divider()
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