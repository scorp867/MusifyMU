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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Size
import coil.size.Scale
import com.musify.mu.util.SpotifyStyleArtworkLoader
import kotlinx.coroutines.Dispatchers

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
    targetSizePx: Int? = null
) {
    val context = LocalContext.current
    val finalModifier = if (shape != null) modifier.clip(shape) else modifier
    
    // Observe artwork from Spotify-style loader
    val artworkUri by SpotifyStyleArtworkLoader.getArtworkFlow(trackUri ?: "").collectAsState()
    
    // Trigger artwork loading on first composition
    LaunchedEffect(trackUri) {
        if (enableOnDemand && !trackUri.isNullOrBlank()) {
            SpotifyStyleArtworkLoader.loadArtwork(trackUri)
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
            // Display the extracted artwork
            AsyncImage(
                model = run {
                    val builder = ImageRequest.Builder(context)
                        .data(artworkUri)
                        .dispatcher(Dispatchers.IO)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(false) // Disable to prevent flickering
                        .scale(Scale.FILL)

                    if (targetSizePx != null) {
                        builder.size(targetSizePx)
                        builder.memoryCacheKey("${artworkUri}#${targetSizePx}")
                        builder.diskCacheKey("${artworkUri}#${targetSizePx}")
                    } else {
                        builder.size(Size.ORIGINAL)
                        builder.memoryCacheKey("${artworkUri}#orig")
                        builder.diskCacheKey("${artworkUri}#orig")
                    }

                    builder.build()
                },
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
    targetSizePx: Int? = null
) {
    SpotifyStyleArtwork(
        trackUri = trackUri,
        contentDescription = contentDescription ?: "Track artwork",
        modifier = modifier,
        shape = shape,
        enableOnDemand = true,
        targetSizePx = targetSizePx
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
        targetSizePx = 128
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
        enableOnDemand = true,
        targetSizePx = null
    )
}

