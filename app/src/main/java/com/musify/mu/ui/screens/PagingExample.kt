package com.musify.mu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.items
import com.musify.mu.data.repo.PagingRepository
import com.musify.mu.ui.components.Artwork

/**
 * Example implementation showing how to use Paging 3 with the new architecture
 * This demonstrates how other screens can be updated to use paginated loading
 */
@Composable
fun PagingExampleScreen() {
    val context = LocalContext.current
    val pagingRepository = remember { PagingRepository.getInstance(context) }
    
    // Get paginated tracks
    val tracks = pagingRepository.getAllTracksPaged(pageSize = 50).collectAsLazyPagingItems()
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Loading state
        when (tracks.loadState.refresh) {
            is LoadState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is LoadState.Error -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Error loading tracks")
                }
            }
            else -> {
                // Show tracks with efficient pagination
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tracks) { track ->
                        track?.let { 
                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Artwork with on-demand loading
                                    Artwork(
                                        data = it.artUri,
                                        audioUri = it.mediaId,
                                        albumId = it.albumId,
                                        contentDescription = it.title,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = it.title,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = it.artist,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Loading state for additional pages
                    when (tracks.loadState.append) {
                        is LoadState.Loading -> {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        is LoadState.Error -> {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Error loading more tracks")
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}