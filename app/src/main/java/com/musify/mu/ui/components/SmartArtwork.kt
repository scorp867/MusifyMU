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
 * - Optimized for Paging 3 with automatic memory management
 */
@Composable
fun SmartArtwork(
    artworkUri: String?, // Pre-extracted artwork URI from Track entity
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    enableCrossfade: Boolean = true
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
        
        // Determine image source with fallback chain
        val imageData = remember(artworkUri) {
            when {
                !artworkUri.isNullOrBlank() -> {
                    // Primary: Use pre-extracted artwork
                    android.util.Log.d("SmartArtwork", "Loading artwork: $artworkUri")
                    artworkUri
                }
                else -> {
                    // Fallback: Use default placeholder
                    android.util.Log.d("SmartArtwork", "No artwork available, using placeholder")
                    null
                }
            }
        }
        
        if (imageData != null) {
            // Create image painter with multiple fallback strategies
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(imageData)
                    .dispatcher(Dispatchers.IO)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(if (enableCrossfade) 300 else 0)
                    .size(Size.ORIGINAL)
                    .scale(Scale.FIT)
                    .allowHardware(true) // Enable hardware bitmaps for better performance
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
                            android.util.Log.w("SmartArtwork", "Failed to load artwork: $imageData")
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
