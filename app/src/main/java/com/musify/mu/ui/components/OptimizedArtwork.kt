package com.musify.mu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Size
import coil.request.ImageResult
import com.musify.mu.util.OptimizedArtworkLoader
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Optimized artwork component with Spotify-like performance:
 * - Instant loading from cache when available
 * - Smooth placeholder transitions
 * - Efficient memory usage
 * - Background prefetching
 */
@Composable
fun OptimizedArtwork(
    mediaUri: String?,
    albumArtUri: String? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val finalModifier = if (shape != null) modifier.clip(shape) else modifier
    
    // Check for custom image first
    val customImageUri = remember(mediaUri) {
        if (!mediaUri.isNullOrBlank()) {
            com.musify.mu.util.ImagePickerUtil.getCustomImagePath(context, mediaUri)
        } else null
    }
    
    // Get artwork URI from optimized loader (only if not permanently failed)
    val artworkUri by OptimizedArtworkLoader.artworkFlow(mediaUri ?: "").collectAsStateWithLifecycle()
    
    // Determine the best artwork source (custom image has priority)
    val imageData = remember(customImageUri, artworkUri, albumArtUri) {
        customImageUri ?: artworkUri ?: albumArtUri
    }
    
    // Stable state to prevent flickering
    var isLoadingStable by remember { mutableStateOf(false) }
    
    // Trigger loading if not cached and not permanently failed
    LaunchedEffect(mediaUri) {
        if (!mediaUri.isNullOrBlank() && !OptimizedArtworkLoader.hasPermanentlyFailed(mediaUri)) {
            val cached = OptimizedArtworkLoader.getCachedUri(mediaUri)
            // Only trigger loading if we don't have anything cached
            if (cached == null) {
                OptimizedArtworkLoader.loadArtwork(mediaUri)
            }
        }
    }
    
    Box(modifier = finalModifier) {
        if (imageData != null) {
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(imageData)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(Size.ORIGINAL)
                    .crossfade(if (isLoadingStable) 0 else 150) // Reduce crossfade during loading
                    .listener(
                        onStart = { isLoadingStable = true },
                        onSuccess = { _, _ -> isLoadingStable = false },
                        onError = { _, _ -> isLoadingStable = false }
                    )
                    .build()
            )
            
            Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            // Show placeholder when loading or on error
            if (painter.state is AsyncImagePainter.State.Loading || 
                painter.state is AsyncImagePainter.State.Error) {
                PlaceholderArtwork()
            }
        } else {
            PlaceholderArtwork()
        }
        
        overlay?.invoke(this)
    }
}

@Composable
private fun BoxScope.PlaceholderArtwork() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(32.dp)
        )
    }
}