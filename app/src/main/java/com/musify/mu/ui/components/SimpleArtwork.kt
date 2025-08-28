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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Size
import coil.size.Scale
import com.musify.mu.R
import android.content.ContentUris
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers

/**
 * Simple artwork component that efficiently displays album art from cached data.
 * Uses album ID to generate proper MediaStore URIs for artwork.
 */
@Composable
fun SimpleArtwork(
    albumId: Long? = null,
    trackUri: String? = null,
    artUri: String? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    val context = LocalContext.current
    
    // Create stable cache key based on artwork URI or album ID
    val cacheKey = remember(artUri, albumId, trackUri) {
        artUri ?: albumId?.toString() ?: trackUri ?: "unknown"
    }
    
    val finalModifier = if (shape != null) modifier.clip(shape) else modifier
    
    // Debug logging
    LaunchedEffect(albumId, trackUri, artUri) {
        android.util.Log.d("SimpleArtwork", "SimpleArtwork created - albumId: $albumId, trackUri: $trackUri, artUri: $artUri, cacheKey: $cacheKey")
    }
    
    // Create image data using multiple sources like ExoPlayer does
    val imageData = remember(artUri, albumId, trackUri) {
        when {
            !artUri.isNullOrBlank() -> {
                // Use cached artwork URI - this is the primary source
                android.util.Log.d("SimpleArtwork", "Using cached artwork URI: $artUri")
                android.net.Uri.parse(artUri)
            }
            albumId != null -> {
                // Try ExoPlayer's approach: use MediaStore album art URI directly
                try {
                    val albumArtUri = android.net.Uri.parse("content://media/external/audio/albumart/$albumId")
                    android.util.Log.d("SimpleArtwork", "Using MediaStore album art URI: $albumArtUri for album ID: $albumId")
                    albumArtUri
                } catch (e: Exception) {
                    android.util.Log.w("SimpleArtwork", "Failed to create MediaStore album art URI for album $albumId", e)
                    R.drawable.ic_music_note
                }
            }
            !trackUri.isNullOrBlank() -> {
                // Last resort: try to extract artwork from the track URI itself (like ExoPlayer does)
                android.util.Log.d("SimpleArtwork", "Trying to extract artwork from track URI: $trackUri")
                android.net.Uri.parse(trackUri)
            }
            else -> {
                android.util.Log.d("SimpleArtwork", "No artwork data available, using default icon")
                R.drawable.ic_music_note
            }
        }
    }
    

    
    // State for tracking load status
    var hasError by remember { mutableStateOf(false) }
    
    Box(
        modifier = finalModifier,
        contentAlignment = Alignment.Center
    ) {
        // Background gradient placeholder
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.05f)
                        )
                    )
                )
        )
        
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageData)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .dispatcher(Dispatchers.IO)
                .crossfade(200)
                .error(R.drawable.ic_music_note)
                .placeholder(R.drawable.ic_music_note)
                .fallback(R.drawable.ic_music_note)
                .size(Size.ORIGINAL)
                .scale(Scale.FIT)
                .listener(
                    onError = { _, _ ->
                        hasError = true
                    },
                    onSuccess = { _, _ ->
                        hasError = false
                    }
                )
                .build(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        
        // Show icon overlay on error
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxSize(0.3f)
                )
            }
        }
    }
}

/**
 * Album-specific artwork component that uses album ID for optimal artwork loading
 */
@Composable
fun AlbumArtwork(
    albumId: Long,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    SimpleArtwork(
        albumId = albumId,
        contentDescription = contentDescription,
        modifier = modifier,
        shape = shape
    )
}

/**
 * Track-specific artwork component that can use both album ID and track URI
 */
@Composable
fun TrackArtwork(
    albumId: Long?,
    trackUri: String? = null,
    artUri: String? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    SimpleArtwork(
        albumId = albumId,
        trackUri = trackUri,
        artUri = artUri,
        contentDescription = contentDescription,
        modifier = modifier,
        shape = shape
    )
}
