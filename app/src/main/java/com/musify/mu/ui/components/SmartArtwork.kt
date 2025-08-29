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
import androidx.compose.ui.graphics.asImageBitmap
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
    artworkUri: String?, // Pre-extracted artwork URI from Track entity
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null
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
                    .crossfade(300)
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

/**
 * Enhanced SmartArtwork component that integrates with ArtworkManager
 * Features:
 * - Direct bitmap loading from memory cache
 * - On-demand extraction fallback
 * - Progressive loading states
 */
@Composable
fun EnhancedSmartArtwork(
    artworkUri: String?,
    mediaId: String?,
    albumId: Long?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    isLoading: Boolean = false,
    artworkManager: com.musify.mu.data.media.ArtworkManager
) {
    val context = LocalContext.current
    val finalModifier = if (shape != null) modifier.clip(shape) else modifier
    val scope = rememberCoroutineScope()
    
    // State for bitmap from ArtworkManager
    var bitmapFromCache by remember(mediaId, albumId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var localLoading by remember(mediaId, albumId) { mutableStateOf(false) }
    var hasError by remember(mediaId, albumId) { mutableStateOf(false) }
    
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
        
        // Try direct bitmap from cache first, then fallback to URI loading
        when {
            bitmapFromCache != null -> {
                // Use bitmap directly from ArtworkManager cache
                androidx.compose.foundation.Image(
                    bitmap = androidx.compose.ui.graphics.asImageBitmap(bitmapFromCache!!),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            !artworkUri.isNullOrBlank() -> {
                // Fallback to URI-based loading (existing functionality)
                val painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(artworkUri)
                        .dispatcher(Dispatchers.IO)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(300)
                        .size(Size.ORIGINAL)
                        .scale(Scale.FIT)
                        .listener(
                            onStart = { hasError = false },
                            onSuccess = { _, _ -> hasError = false },
                            onError = { _, _ -> 
                                hasError = true
                                android.util.Log.w("EnhancedSmartArtwork", "Failed to load artwork URI: $artworkUri")
                            }
                        )
                        .build()
                )
                
                androidx.compose.foundation.Image(
                    painter = painter,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = if (isLoading || localLoading) 0.7f else 1f
                )
            }
        }
        
        // Show loading indicator
        if (isLoading || localLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
            )
        }
        
        // Show icon placeholder when no image, loading, or on error
        if (bitmapFromCache == null && (artworkUri.isNullOrBlank() || hasError || isLoading)) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (isLoading || localLoading) 0.3f else 0.6f
                ),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
    
    // Load bitmap from ArtworkManager cache when needed
    LaunchedEffect(mediaId, albumId) {
        if (bitmapFromCache == null && !mediaId.isNullOrBlank() && !localLoading) {
            localLoading = true
            scope.launch {
                try {
                    // Try to get from memory cache first (very fast)
                    val bitmap = artworkManager.loadArtwork(
                        mediaId = mediaId,
                        albumId = albumId,
                        audioUri = mediaId
                    )
                    bitmapFromCache = bitmap
                } catch (e: Exception) {
                    android.util.Log.w("EnhancedSmartArtwork", "Failed to load bitmap from cache", e)
                    hasError = true
                } finally {
                    localLoading = false
                }
            }
        }
    }
}

// Keep existing SmartArtwork for backward compatibility
