package com.musify.mu.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * Artwork wrapper component that uses pre-extracted artwork URIs
 * This maintains backward compatibility while using cache-only artwork loading
 */
@Composable
fun Artwork(
    data: Any?, // Expected to be the pre-extracted artwork URI
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    audioUri: String? = null, // Track media ID (not used for artwork anymore)
    cacheKey: String? = null, // Not needed with new approach
    albumId: Long? = null // Not used for artwork lookup anymore
) {
    // data should be the pre-extracted artwork URI from Track.artUri
    val artworkUri = data as? String
    
    SmartArtwork(
        artworkUri = artworkUri, // Use pre-extracted artwork URI
        contentDescription = contentDescription,
        modifier = modifier,
        shape = shape
    )
}


