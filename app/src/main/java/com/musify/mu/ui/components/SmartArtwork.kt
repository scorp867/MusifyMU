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

    // Use stable keys to prevent unnecessary recompositions
    val stableKey = remember(artworkUri, mediaUri) { "$artworkUri:$mediaUri" }
    var hasError by remember(stableKey) { mutableStateOf(false) }

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

        // Simplified artwork data management - single source of truth
        val finalImageData = remember(artworkUri, mediaUri) {
            // Prioritize explicit artwork URI, ignore MediaStore album art content URIs
            val sanitizedUri = artworkUri?.takeUnless { it.startsWith("content://media/external/audio/albumart") }

            // If no explicit artwork, check cached loader data
            sanitizedUri ?: if (enableOnDemand && !mediaUri.isNullOrBlank()) {
                com.musify.mu.util.OnDemandArtworkLoader.getCachedUri(mediaUri)
            } else null
        }

        // Observe loader changes only if we don't have explicit artwork - with proper lifecycle
        // Use produceState to prevent flickering
        val loaderFlowValue by produceState<String?>(
            initialValue = if (enableOnDemand && !mediaUri.isNullOrBlank() && artworkUri.isNullOrBlank()) finalImageData else null,
            key1 = mediaUri,
            key2 = artworkUri
        ) {
            if (enableOnDemand && !mediaUri.isNullOrBlank() && artworkUri.isNullOrBlank()) {
                com.musify.mu.util.OnDemandArtworkLoader.artworkFlow(mediaUri).collect { newValue ->
                    value = newValue
                }
            }
        }

        // Final resolved image data
        val resolvedImageData = finalImageData ?: loaderFlowValue

        // Trigger artwork loading only once per unique mediaUri - simplified
        LaunchedEffect(mediaUri) {
            if (enableOnDemand && !mediaUri.isNullOrBlank()) {
                // Check if we already have artwork or tried before
                val cached = com.musify.mu.util.OnDemandArtworkLoader.getCachedUri(mediaUri)
                if (cached == null && artworkUri.isNullOrBlank()) {
                    // Load artwork without delay to prevent flickering
                    com.musify.mu.util.OnDemandArtworkLoader.loadArtwork(mediaUri)
                }
            }
        }

        // Create image painter with multiple fallback strategies (always create, even if no data)
        val painter = rememberAsyncImagePainter(
            model = ImageRequest.Builder(context)
                .data(resolvedImageData)
                .dispatcher(Dispatchers.IO)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCacheKey(resolvedImageData) // Use stable memory cache key
                .diskCacheKey(resolvedImageData) // Use stable disk cache key
                .crossfade(false) // Disable crossfade to prevent flickering
                .size(Size.ORIGINAL)
                .scale(Scale.FIT)
                .listener(
                    onStart = {
                        hasError = false
                    },
                    onSuccess = { _, _ ->
                        hasError = false
                    },
                    onError = { _, _ ->
                        hasError = true
                    }
                )
                .build()
        )

        if (resolvedImageData != null) {
            // Display the image
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Show placeholder only when we don't have image data AND painter is not loading
        // This prevents flickering during cache checks
        val showPlaceholder = resolvedImageData == null || (hasError && painter.state !is AsyncImagePainter.State.Loading)

        if (showPlaceholder) {
            // Static music note without animation to prevent flickering
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}

// SmartArtwork component only - Artwork wrapper is in Artwork.kt
