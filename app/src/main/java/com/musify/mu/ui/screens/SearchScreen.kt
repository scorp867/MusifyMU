package com.musify.mu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.ui.navigation.Screen
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    navController: NavController,
    onPlay: (List<Track>, Int) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                coroutineScope.launch {
                    results = if (it.isNotBlank()) repo.search(it) else emptyList()
                }
            },
            label = { Text("Search") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results.size) { idx ->
                val track = results[idx]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPlay(results, idx)
                            navController.navigate(Screen.NowPlaying.route)
                        }
                        .padding(8.dp)
                ) {
                    com.musify.mu.ui.components.Artwork(
                        data = track.artUri,
                        contentDescription = track.title,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(track.title, style = MaterialTheme.typography.bodyLarge)
                        Text(track.artist, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
