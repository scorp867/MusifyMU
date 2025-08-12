package com.musify.mu.ui.screens

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    // Placeholder queue screen; wire to MediaController for real state
    var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopBar(title = "Queue")
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(queue.size) { idx ->
                val track = queue[idx]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // TODO: Seek player to this track
                        }
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
                                color = if (idx == currentIndex) MaterialTheme.colorScheme.primary
                                else LocalContentColor.current
                            )
                            Text(track.artist, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    IconButton(onClick = {
                        // TODO: remove track from queue
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove from Queue")
                    }
                }
            }
        }
    }
}
