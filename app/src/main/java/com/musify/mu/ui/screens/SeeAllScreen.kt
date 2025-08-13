package com.musify.mu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import org.burnoutcrew.reorderable.*
import kotlinx.coroutines.launch

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

    val reorderState = if (type == "favorites") rememberReorderableLazyListState(onMove = { from, to ->
        tracks = tracks.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }) else null

    Scaffold(
        topBar = {
            SmallTopAppBar(title = { Text(title) })
        },
        floatingActionButton = {
            if (type == "favorites") {
                ExtendedFloatingActionButton(
                    text = { Text("Save order") },
                    icon = {},
                    onClick = {
                        val order = tracks.mapIndexed { index, track -> com.musify.mu.data.db.entities.FavoritesOrder(track.mediaId, index) }
                        scope.launch {
                            repo.saveFavoritesOrder(order)
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (type == "favorites") {
            LazyColumn(
                state = reorderState!!.listState,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .reorderable(reorderState)
                    .detectReorder(reorderState),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tracks.size, key = { idx -> tracks[idx].mediaId }) { idx ->
                    val track = tracks[idx]
                    ReorderableItem(reorderState, key = track.mediaId) { _ ->
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
                            modifier = Modifier.clickable { onPlay(tracks, idx) }
                        )
                        Divider()
                    }
                }
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
                        modifier = Modifier.clickable { onPlay(tracks, idx) }
                    )
                    Divider()
                }
            }
        }
    }
}