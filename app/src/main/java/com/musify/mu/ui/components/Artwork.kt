package com.musify.mu.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.musify.mu.data.media.ArtworkManager
import kotlinx.coroutines.launch

/**
 * Enhanced Artwork component that uses ArtworkManager for Media3-like artwork extraction
 * Features:
 * - On-demand loading for visible items only
 * - Media3-like fallback strategies
 * - Session-based caching
 * - Backward compatibility with existing URIs
 */
@Composable
fun Artwork(
    data: Any?, // Can be pre-extracted artwork URI or null for on-demand loading
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    audioUri: String? = null, // Track media ID for on-demand extraction
    cacheKey: String? = null, // Deprecated, not needed
    albumId: Long? = null, // Album ID for fallback extraction
    overlay: (@Composable BoxScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val artworkManager = remember { ArtworkManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    
    // State for on-demand artwork loading
    var loadedArtworkUri by remember(audioUri, albumId) { mutableStateOf<String?>(null) }
    var isLoading by remember(audioUri, albumId) { mutableStateOf(false) }
    
    // Determine the artwork URI to use
    val artworkUri = when {
        // Use pre-extracted artwork URI if available
        !data?.toString().isNullOrBlank() -> data.toString()
        // Use loaded artwork URI from on-demand extraction
        !loadedArtworkUri.isNullOrBlank() -> loadedArtworkUri
        else -> null
    }
    
    // On-demand artwork loading when no pre-extracted URI is available
    LaunchedEffect(audioUri, albumId) {
        if (artworkUri == null && !audioUri.isNullOrBlank() && !isLoading) {
            isLoading = true
            scope.launch {
                try {
                    val bitmap = artworkManager.loadArtwork(
                        mediaId = audioUri,
                        albumId = albumId,
                        audioUri = audioUri
                    )
                    
                    if (bitmap != null) {
                        // Convert bitmap to URI for use with Coil
                        // This approach stores the bitmap in memory cache and provides a key
                        loadedArtworkUri = "artwork_cache_${audioUri}_${albumId}"
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Artwork", "Failed to load artwork on-demand", e)
                } finally {
                    isLoading = false
                }
            }
        }
    }
    
    Box(modifier = modifier) {
        EnhancedSmartArtwork(
            artworkUri = artworkUri,
            mediaId = audioUri,
            albumId = albumId,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            shape = shape,
            isLoading = isLoading,
            artworkManager = artworkManager
        )
        if (overlay != null) {
            overlay()
        }
    }
}


