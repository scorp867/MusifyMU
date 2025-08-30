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

    // State for tracking load status
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

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
        // Keep showing the last good artwork until a new one is ready to avoid flicker
        var imageData by remember { mutableStateOf<String?>(sanitizedArtworkUri) }

        // Observe Media3/loader-provided artwork for this mediaUri
        val loaderFlowValue = if (enableOnDemand && !mediaUri.isNullOrBlank()) {
            val observedLoaderArt = remember(mediaUri) { com.musify.mu.util.OnDemandArtworkLoader.getCachedUri(mediaUri!!) }
            com.musify.mu.util.OnDemandArtworkLoader.artworkFlow(mediaUri!!).collectAsState(initial = observedLoaderArt).value
        } else null

        // Prioritize explicit artwork from track, otherwise use loader-provided art
        LaunchedEffect(sanitizedArtworkUri) {
            if (!sanitizedArtworkUri.isNullOrBlank() && imageData != sanitizedArtworkUri) {
                imageData = sanitizedArtworkUri
            }
        }
        // Always adopt loader-provided artwork when it becomes available; rely on coil crossfade to prevent jank
        LaunchedEffect(loaderFlowValue) {
            if (!loaderFlowValue.isNullOrBlank() && imageData != loaderFlowValue) {
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

        // Track the last successfully shown artwork
        var lastSuccessfulData by remember { mutableStateOf<String?>(sanitizedArtworkUri) }

        if (imageData != null) {
            // Create image painter with multiple fallback strategies
            val request = remember(imageData) {
                ImageRequest.Builder(context)
                    .data(imageData)
                    .dispatcher(Dispatchers.IO)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(false) // avoid perceived flicker on track change
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
                            // Commit displayed data only after new art is ready
                            lastSuccessfulData = imageData
                        },
                        onError = { _, _ ->
                            isLoading = false
                            hasError = true
                        }
                    )
                    .build()
            }
            val painter = rememberAsyncImagePainter(model = request)

            // Only use previous artwork as a placeholder if the incoming track already had art
            val allowPreviousAsPlaceholder = remember(sanitizedArtworkUri) { !sanitizedArtworkUri.isNullOrBlank() }

            // Draw previous successful art underneath while the new one is loading
            if (allowPreviousAsPlaceholder && painter.state is AsyncImagePainter.State.Loading && !lastSuccessfulData.isNullOrBlank()) {
                val lastReq = remember(lastSuccessfulData) {
                    ImageRequest.Builder(context)
                        .data(lastSuccessfulData)
                        .dispatcher(Dispatchers.IO)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(false)
                        .size(Size.ORIGINAL)
                        .scale(Scale.FIT)
                        .build()
                }
                val lastPainter = rememberAsyncImagePainter(model = lastReq)
                androidx.compose.foundation.Image(
                    painter = lastPainter,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Draw the new image only when ready (Success) to avoid a brief blank reload
            if (painter.state is AsyncImagePainter.State.Success) {
                androidx.compose.foundation.Image(
                    painter = painter,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
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
