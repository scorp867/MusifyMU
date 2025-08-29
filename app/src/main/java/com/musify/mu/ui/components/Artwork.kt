package com.musify.mu.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.ContentScale

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
    albumId: Long? = null, // Not used for artwork lookup anymore
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    isVisible: Boolean = true
) {
    // data should be the pre-extracted artwork URI from Track.artUri
    val artData = data
    
    Box(modifier = modifier) {
        SmartArtwork(
            artData = artData,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            shape = shape,
            shouldLoad = isVisible
        )
        if (overlay != null) {
            overlay()
        }
    }
}


