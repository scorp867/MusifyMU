package com.musify.mu.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.fillMaxSize

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
    isVisible: Boolean = true,
    overlay: (@Composable BoxScope.() -> Unit)? = null
) {
    // Prefer provided data; fallback to albumId-based MediaStore URI; then to audioUri; else null
    val artData = remember(data, albumId, audioUri) {
        when {
            data != null -> data
            albumId != null -> android.net.Uri.parse("content://media/external/audio/albumart/$albumId")
            !audioUri.isNullOrBlank() -> android.net.Uri.parse(audioUri)
            else -> null
        }
    }

    Box(modifier = modifier) {
        SmartArtwork(
            artData = artData,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            shouldLoad = isVisible
        )
        if (overlay != null) {
            overlay()
        }
    }
}


