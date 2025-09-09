package com.musify.mu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Size
import coil.size.Scale
import com.musify.mu.util.SpotifyStyleArtworkLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spotify-style artwork component that uses on-demand ID3/APIC extraction
 * Based on Spotify's embedded artwork loading approach
 */
@Composable
fun SpotifyStyleArtwork(
    trackUri: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    enableOnDemand: Boolean = true,
    size: Size = Size.ORIGINAL
) {
    val context = LocalContext.current
    val finalModifier = if (shape != null) modifier.clip(shape) else modifier
    
    // Observe artwork from Spotify-style loader
    val artworkUri by SpotifyStyleArtworkLoader.getArtworkFlow(trackUri ?: "").collectAsState()
    
    // Trigger on-demand extraction when the composable becomes active and no cache is present
    LaunchedEffect(trackUri, enableOnDemand) {
        val id = trackUri
        if (enableOnDemand && !id.isNullOrBlank()) {
            try {
                // Move ALL cache operations to background thread to prevent main thread blocking
                withContext(Dispatchers.IO) {
                    val cached = SpotifyStyleArtworkLoader.getCachedArtworkUri(id)
                    if (cached == null) {
                        // Fire and forget; loader dedups parallel loads
                        com.musify.mu.util.SpotifyStyleArtworkLoader.loadArtwork(id)
                    }
                }
            } catch (e: Exception) { 
                // Silently handle extraction failures
            }
        }
    }
    
    Box(
        modifier = finalModifier,
        contentAlignment = Alignment.Center
    ) {
        // Background placeholder
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.04f)
                        )
                    )
                )
        )
        
        if (artworkUri != null) {
            // Display the extracted artwork using shared image loader with stable keys
            val imageLoader = remember { SpotifyStyleArtworkLoader.getImageLoader() ?: ImageLoader(context) }
            val imageRequest = remember(artworkUri, size) {
                ImageRequest.Builder(context)
                    .data(artworkUri)
                    .size(size) // Use specified size for better memory management
                    .build()
            }
            
            AsyncImage(
                model = imageRequest,
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Show music note placeholder
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}

/**
 * Album artwork component optimized for album views
 */
@Composable
fun AlbumArtwork(
    albumId: Long?,
    firstTrackUri: String? = null, // URI of first track for artwork extraction
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    SpotifyStyleArtwork(
        trackUri = firstTrackUri,
        contentDescription = contentDescription ?: "Album artwork",
        modifier = modifier,
        shape = shape,
        enableOnDemand = true
    )
}

/**
 * Track artwork component for individual tracks
 */
@Composable
fun TrackArtwork(
    trackUri: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    enableOnDemand: Boolean = true
) {
    SpotifyStyleArtwork(
        trackUri = trackUri,
        contentDescription = contentDescription ?: "Track artwork",
        modifier = modifier,
        shape = shape,
        enableOnDemand = enableOnDemand,
        size = Size(128, 128) // Compact size for track rows to reduce memory usage
    )
}

/**
 * Compact artwork component for small UI elements (like mini player)
 */
@Composable
fun CompactArtwork(
    trackUri: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    SpotifyStyleArtwork(
        trackUri = trackUri,
        contentDescription = "Now playing artwork",
        modifier = modifier,
        shape = shape,
        enableOnDemand = true,
        size = Size(64, 64) // Very compact size for mini player
    )
}

/**
 * Large artwork component for full-screen player
 */
@Composable
fun LargeArtwork(
    trackUri: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    SpotifyStyleArtwork(
        trackUri = trackUri,
        contentDescription = "Full-screen artwork",
        modifier = modifier,
        shape = shape,
        enableOnDemand = true
    )
}

