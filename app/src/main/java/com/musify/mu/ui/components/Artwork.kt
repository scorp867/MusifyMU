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
    mediaUri: String? = null, // Track media ID used for on-demand artwork extraction
    cacheKey: String? = null, // Not needed with new approach
    albumId: Long? = null, // Not used for artwork lookup anymore
    enableOnDemand: Boolean = false,
    overlay: (@Composable BoxScope.() -> Unit)? = null
) {
    // data should be the pre-extracted artwork URI from Track.artUri
    val artworkUri = (data as? String)?.takeUnless { it.startsWith("content://media/external/audio/albumart") }

    Box(modifier = modifier) {
        SmartArtwork(
            artworkUri = artworkUri,
            mediaUri = mediaUri,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            shape = shape,
            enableOnDemand = enableOnDemand
        )
        if (overlay != null) {
            overlay()
        }
    }
}


