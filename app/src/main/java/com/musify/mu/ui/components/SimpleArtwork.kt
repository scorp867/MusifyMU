package com.musify.mu.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Size
import coil.size.Scale
import com.musify.mu.R
import android.content.ContentUris
import android.provider.MediaStore

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
    
    // Simple Coil image request with aggressive caching
    val imageRequest = remember(cacheKey, imageData) {
        ImageRequest.Builder(context)
            .data(imageData)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(200)
            .error(R.drawable.ic_music_note)
            .placeholder(R.drawable.ic_music_note)
            .fallback(R.drawable.ic_music_note)
            .size(Size.ORIGINAL)
            .scale(Scale.FIT)
            .listener(
                onStart = { placeholder ->
                    android.util.Log.d("SimpleArtwork", "Loading artwork for: $cacheKey")
                },
                onSuccess = { _, _ ->
                    android.util.Log.d("SimpleArtwork", "Successfully loaded artwork for: $cacheKey")
                },
                onError = { _, result ->
                    android.util.Log.w("SimpleArtwork", "Failed to load artwork for: $cacheKey", result.throwable)
                }
            )
            .build()
    }
    
    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        modifier = finalModifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop
    )
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
