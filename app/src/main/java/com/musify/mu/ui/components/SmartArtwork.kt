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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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

            // State for tracking load status - use stable key to prevent re-initialization
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

        // Single source of truth for artwork data - prevent multiple state changes
        val artworkData = remember(artworkUri, mediaUri) {
            // Prioritize explicit artwork URI, ignore MediaStore album art content URIs
            val sanitizedUri = artworkUri?.takeUnless { it.startsWith("content://media/external/audio/albumart") }

            // If no explicit artwork, check cached loader data
            sanitizedUri ?: if (enableOnDemand && !mediaUri.isNullOrBlank()) {
                com.musify.mu.util.OnDemandArtworkLoader.getCachedUri(mediaUri)
            } else null
        }

        // Observe loader changes only if we don't have explicit artwork
        val loaderFlowValue = if (enableOnDemand && !mediaUri.isNullOrBlank() && artworkUri.isNullOrBlank()) {
            com.musify.mu.util.OnDemandArtworkLoader.artworkFlow(mediaUri).collectAsState(initial = artworkData).value
        } else null

        // Final image data - stable to prevent unnecessary recompositions
        val finalImageData = remember(artworkData, loaderFlowValue) {
            artworkData ?: loaderFlowValue
        }

        // Trigger artwork loading only once per unique mediaUri
        LaunchedEffect(mediaUri) {
            if (enableOnDemand && finalImageData.isNullOrBlank() && !mediaUri.isNullOrBlank()) {
                // Check if we already tried this before (negative cache)
                val cached = com.musify.mu.util.OnDemandArtworkLoader.getCachedUri(mediaUri)
                if (cached == null) { // Only extract if not in negative cache
                    // Add delay to prevent rapid successive calls
                    kotlinx.coroutines.delay(200)
                    com.musify.mu.util.OnDemandArtworkLoader.loadArtwork(mediaUri)
                }
            }
        }

        if (finalImageData != null) {
            // Create image painter with multiple fallback strategies
            val enableCrossfade = !finalImageData.isNullOrBlank()
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(finalImageData)
                    .dispatcher(Dispatchers.IO)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCacheKey(finalImageData) // Use stable memory cache key
                    .diskCacheKey(finalImageData) // Use stable disk cache key
                    .apply { if (enableCrossfade) crossfade(300) else crossfade(false) }
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

            // Display the image
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Enhanced placeholder with subtle animation for tracks without artwork
        if (finalImageData == null || hasError) {
            // Animated music note with subtle pulsing
            val infiniteTransition = rememberInfiniteTransition(label = "musicNotePulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )

            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pulseAlpha),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}

// SmartArtwork component only - Artwork wrapper is in Artwork.kt
