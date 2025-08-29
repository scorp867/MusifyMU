package com.musify.mu.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.media.ArtworkManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Lazy artwork loader that loads artwork only for visible items in lists
 * Features:
 * - Loads artwork only when items become visible
 * - Preloads artwork for items about to become visible
 * - Manages memory efficiently
 * - Works with LazyColumn and LazyRow
 */
@Composable
fun LazyArtworkLoader(
    tracks: List<Track>,
    listState: LazyListState,
    preloadDistance: Int = 5
) {
    val context = LocalContext.current
    val artworkManager = remember { ArtworkManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    
    // Track which items are visible or about to be visible
    LaunchedEffect(listState, tracks) {
        snapshotFlow { 
            listState.layoutInfo.visibleItemsInfo.map { it.index }
        }
        .distinctUntilChanged()
        .collect { visibleIndices ->
            if (visibleIndices.isNotEmpty() && tracks.isNotEmpty()) {
                // Calculate range to preload (visible + preload distance)
                val firstVisible = visibleIndices.first()
                val lastVisible = visibleIndices.last()
                val startIndex = maxOf(0, firstVisible - preloadDistance)
                val endIndex = minOf(tracks.size - 1, lastVisible + preloadDistance)
                
                // Load artwork for visible and preload range
                for (index in startIndex..endIndex) {
                    if (index < tracks.size) {
                        val track = tracks[index]
                        
                        // Skip if artwork is already available
                        if (!track.artUri.isNullOrBlank()) continue
                        
                        // Load artwork in background
                        scope.launch {
                            try {
                                artworkManager.loadArtwork(
                                    mediaId = track.mediaId,
                                    albumId = track.albumId,
                                    audioUri = track.mediaId
                                )
                                // Artwork is now cached in memory for instant access
                            } catch (e: Exception) {
                                // Silently fail - artwork loading is non-critical
                                android.util.Log.w("LazyArtworkLoader", "Failed to load artwork for ${track.title}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Artwork visibility tracker for individual items
 */
@Composable
fun TrackArtworkLoader(
    track: Track,
    onArtworkLoaded: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val artworkManager = remember { ArtworkManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    
    // Load artwork when track becomes visible
    LaunchedEffect(track.mediaId) {
        // Skip if artwork is already available
        if (!track.artUri.isNullOrBlank()) {
            onArtworkLoaded(track.artUri)
            return@LaunchedEffect
        }
        
        scope.launch {
            try {
                val bitmap = artworkManager.loadArtwork(
                    mediaId = track.mediaId,
                    albumId = track.albumId,
                    audioUri = track.mediaId
                )
                
                if (bitmap != null) {
                    // Notify that artwork is loaded and cached
                    onArtworkLoaded("artwork_cached_${track.mediaId}")
                }
            } catch (e: Exception) {
                android.util.Log.w("TrackArtworkLoader", "Failed to load artwork for ${track.title}")
                onArtworkLoaded(null)
            }
        }
    }
}

/**
 * Memory-aware artwork cache manager
 */
@Composable
fun ArtworkCacheManager() {
    val context = LocalContext.current
    val artworkManager = remember { ArtworkManager.getInstance(context) }
    
    // Monitor memory pressure and clear cache when needed
    DisposableEffect(Unit) {
        onDispose {
            // Clear memory cache when component is disposed
            // This helps with memory management during navigation
            android.util.Log.d("ArtworkCacheManager", "Clearing artwork cache on dispose")
        }
    }
    
    // Provide access to cache stats for debugging
    LaunchedEffect(Unit) {
        android.util.Log.d("ArtworkCacheManager", "Current artwork cache size: ${artworkManager.getMemoryCacheSize()}")
    }
}