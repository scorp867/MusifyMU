package com.musify.mu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Playlist
import com.musify.mu.data.repo.LibraryRepository
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.musify.mu.ui.viewmodels.LibraryViewModel

@Composable
fun PlaylistScreen(navController: NavController) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val context = LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var showDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        playlists = repo.playlists()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Playlist")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(playlists.size) { idx ->
                val playlist = playlists[idx]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            navController.navigate("playlist/${playlist.id}")
                        }
                        .padding(8.dp)
                ) {
                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (showDialog) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("New Playlist") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist Name") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            if (name.isNotBlank()) {
                                repo.createPlaylist(name)
                                playlists = repo.playlists()
                            }
                            showDialog = false
                        }
                    }) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
