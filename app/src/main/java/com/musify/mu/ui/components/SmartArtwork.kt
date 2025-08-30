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
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Size
import coil.size.Scale
// Removed BlurTransformation to avoid extra artifact
import com.musify.mu.R
import kotlinx.coroutines.Dispatchers

/**
 * Enhanced artwork component with lazy loading and multiple fallback methods
 * Features:
 * - Lazy loading with progressive quality
 * - Multiple fallback strategies
 * - Smooth transitions and placeholders
 */
@Composable
fun SmartArtwork(
    artworkUri: String?, // Pre-extracted artwork URI from Track entity (may be null)
    mediaUri: String? = null, // Content URI or file path to the audio file
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    enableOnDemand: Boolean = true
) {
    val context = LocalContext.current
    val finalModifier = if (shape != null) modifier.clip(shape) else modifier

            // State for tracking load status - keyed by artwork URI to prevent re-initialization
        var isLoading by remember(artworkUri, mediaUri) { mutableStateOf(true) }
        var hasError by remember(artworkUri, mediaUri) { mutableStateOf(false) }

    // Create gradient background colors based on theme
    val gradientColors = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
    )

    Box(
        modifier = finalModifier,
        contentAlignment = Alignment.Center
    ) {
        // Background gradient placeholder
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = if (hasError) {
                            listOf(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                            )
                        } else {
                            gradientColors
                        }
                    )
                )
        )

        // Ignore MediaStore album art content URIs; rely on embedded art or Media3
        val sanitizedArtworkUri = remember(artworkUri) {
            artworkUri?.takeUnless { it.startsWith("content://media/external/audio/albumart") }
        }
        var imageData by remember { mutableStateOf<String?>(sanitizedArtworkUri) }

        // Observe Media3/loader-provided artwork for this mediaUri
        val loaderFlowValue = if (enableOnDemand && !mediaUri.isNullOrBlank()) {
            val observedLoaderArt = remember(mediaUri) { com.musify.mu.util.OnDemandArtworkLoader.getCachedUri(mediaUri!!) }
            com.musify.mu.util.OnDemandArtworkLoader.artworkFlow(mediaUri!!).collectAsState(initial = observedLoaderArt).value
        } else null

        // Prioritize explicit artwork from track, otherwise use loader-provided art
        LaunchedEffect(sanitizedArtworkUri) {
            if (!sanitizedArtworkUri.isNullOrBlank()) {
                imageData = sanitizedArtworkUri
            }
        }
        LaunchedEffect(loaderFlowValue) {
            if (imageData.isNullOrBlank() && !loaderFlowValue.isNullOrBlank()) {
                imageData = loaderFlowValue
            }
        }

        // Trigger one-time extraction if still missing and mediaUri available
        // Trigger one-time extraction if still missing and not negatively cached
        LaunchedEffect(key1 = mediaUri) {
            if (enableOnDemand && imageData.isNullOrBlank() && !mediaUri.isNullOrBlank()) {
                com.musify.mu.util.OnDemandArtworkLoader.loadArtwork(mediaUri)
            }
        }

        if (imageData != null) {
            // Create image painter with multiple fallback strategies
            val enableCrossfade = !imageData.isNullOrBlank()
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(imageData)
                    .dispatcher(Dispatchers.IO)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCacheKey(imageData) // Use stable memory cache key
                    .diskCacheKey(imageData) // Use stable disk cache key
                    .apply { if (enableCrossfade) crossfade(300) else crossfade(false) }
                    .size(Size.ORIGINAL)
                    .scale(Scale.FIT)
                    .listener(
                        onStart = {
                            isLoading = true
                            hasError = false
                        },
                        onSuccess = { _, _ ->
                            isLoading = false
                            hasError = false
                        },
                        onError = { _, _ ->
                            isLoading = false
                            hasError = true
                        }
                    )
                    .build()
            )

            // Display the image
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = if (isLoading) 0.7f else 1f
            )

            // Loading state overlay
            if (painter.state is AsyncImagePainter.State.Loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f))
                )
            }
        }

        // Show icon placeholder when no image or on error
        if (imageData == null || hasError) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}

// SmartArtwork component only - Artwork wrapper is in Artwork.kt
