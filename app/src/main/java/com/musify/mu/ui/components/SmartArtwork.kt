package com.musify.mu.ui.components

import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * Cache-only artwork component that reads from pre-extracted artwork stored in database
 * No on-demand extraction - all artwork is extracted at app startup for smooth scrolling
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
    
    // Determine what to display - either pre-extracted artwork or placeholder
    val imageData = remember(artworkUri) {
        when {
            !artworkUri.isNullOrBlank() -> {
                // Use pre-extracted artwork from startup scan
                android.util.Log.d("SmartArtwork", "Using pre-extracted artwork: $artworkUri")
                android.net.Uri.parse(artworkUri)
            }
            else -> {
                // Show placeholder - no artwork was found during startup scan
                android.util.Log.d("SmartArtwork", "Showing placeholder - no artwork extracted during startup for contentDescription: $contentDescription")
                R.drawable.ic_music_note
            }
        }
    }
    
    // Simple Coil image request - no complex caching needed since artwork is pre-extracted
    val imageRequest = remember(artworkUri, imageData) {
        ImageRequest.Builder(context)
            .data(imageData)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(200) // Quick transition
            .error(R.drawable.ic_music_note)
            .placeholder(R.drawable.ic_music_note)
            .fallback(R.drawable.ic_music_note)
            .size(Size.ORIGINAL)
            .scale(Scale.FIT)
            .build()
    }
    
    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        modifier = finalModifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop
    )
}

// SmartArtwork component only - Artwork wrapper is in Artwork.kt
