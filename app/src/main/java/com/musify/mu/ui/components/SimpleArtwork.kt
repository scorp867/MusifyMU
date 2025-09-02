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
import androidx.compose.runtime.collectAsState
import com.musify.mu.R
import android.content.ContentUris
import android.provider.MediaStore
import android.net.Uri
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



    // Observe loader-provided artwork bound to trackUri (mediaId)
    // Use produceState to prevent flickering by maintaining stable state across recompositions
    val loaderArt by produceState<String?>(
        initialValue = if (trackUri.isNullOrBlank()) null else com.musify.mu.util.OnDemandArtworkLoader.getCachedUri(trackUri),
        key1 = trackUri
    ) {
        if (!trackUri.isNullOrBlank()) {
            com.musify.mu.util.OnDemandArtworkLoader.artworkFlow(trackUri).collect { newValue ->
                value = newValue
            }
        }
    }

    // Resolve image data preference: explicit artUri (non-MediaStore) > loaderArt > placeholder
    val sanitizedArtUri = remember(artUri) {
        artUri?.takeUnless { it.startsWith("content://media/external/audio/albumart") }
    }
    val albumArtContentUri = remember(albumId) {
        albumId?.let { id ->
            // Use legacy album art content provider for album thumbnails
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), id)
        }
    }
    val imageData = remember(sanitizedArtUri, loaderArt, albumArtContentUri) {
        when {
            !sanitizedArtUri.isNullOrBlank() -> android.net.Uri.parse(sanitizedArtUri)
            !loaderArt.isNullOrBlank() -> android.net.Uri.parse(loaderArt)
            albumArtContentUri != null -> albumArtContentUri
            else -> R.drawable.ic_music_note
        }
    }



    // State for tracking load status
    var hasError by remember { mutableStateOf(false) }

    Box(
        modifier = finalModifier,
        contentAlignment = Alignment.Center
    ) {
        // Background neutral placeholder
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.04f)
                        )
                    )
                )
        )

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageData)
                .apply { if (cacheKey != "unknown") { memoryCacheKey(cacheKey); diskCacheKey(cacheKey) } }
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .dispatcher(Dispatchers.IO)
                .crossfade(false) // Disable crossfade to prevent flickering
                .error(R.drawable.ic_music_note)
                .placeholder(null) // Remove placeholder to prevent flickering during cache checks
                .fallback(R.drawable.ic_music_note)
                .size(Size.ORIGINAL)
                .scale(Scale.FIT)
                .listener(
                    onStart = { },
                    onError = { _, _ -> hasError = true },
                    onSuccess = { _, _ -> hasError = false }
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
