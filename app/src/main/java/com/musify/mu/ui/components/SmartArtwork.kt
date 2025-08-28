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
import coil.size.Scale
import com.musify.mu.R
import android.net.Uri

/**
 * Cache-only artwork component that reads from pre-extracted artwork stored in database
 * No on-demand extraction - all artwork is extracted at app startup for smooth scrolling
 */
@Composable
fun SmartArtwork(
    artworkUri: String?, // Pre-extracted artwork URI from Track entity
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    albumId: Long? = null, // Fallback source
    trackUri: String? = null, // Fallback source
    sessionArtworkUri: Uri? = null, // Highest-priority: what the session/notification uses
    onResolved: (Uri?) -> Unit = {}
) {
    val context = LocalContext.current
    
    val finalModifier = if (shape != null) modifier.clip(shape) else modifier
    
    // Candidate chain: pre-extracted -> MediaStore album art -> embedded from track -> vector placeholder
    val candidates = remember(artworkUri, albumId, trackUri, sessionArtworkUri) {
        buildList<Any> {
            if (sessionArtworkUri != null) add(sessionArtworkUri)
            if (!artworkUri.isNullOrBlank()) add(android.net.Uri.parse(artworkUri))
            if (!trackUri.isNullOrBlank()) add(android.net.Uri.parse(trackUri))
            if (albumId != null) {
                try { add(android.net.Uri.parse("content://media/external/audio/albumart/$albumId")) } catch (_: Exception) {}
            }
            add(R.drawable.ic_music_note)
        }
    }
    var candidateIndex by remember(candidates) { mutableStateOf(0) }
    val currentData = remember(candidates, candidateIndex) { candidates.getOrNull(candidateIndex) ?: R.drawable.ic_music_note }

    // Coil request with graceful fallback stepping
    val imageRequest = remember(currentData) {
        ImageRequest.Builder(context)
            .data(currentData)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(250)
            .error(R.drawable.ic_music_note)
            .placeholder(R.drawable.ic_music_note)
            .fallback(R.drawable.ic_music_note)
            .scale(Scale.FIT)
            .listener(
                onStart = { android.util.Log.d("SmartArtwork", "Loading artwork [${candidateIndex + 1}/${candidates.size}] for ${contentDescription ?: "unknown"}") },
                onError = { _, result ->
                    android.util.Log.w("SmartArtwork", "Artwork load failed on candidate $candidateIndex", result.throwable)
                    if (candidateIndex < candidates.lastIndex) candidateIndex++
                },
                onSuccess = { _, _ ->
                    val resolved = currentData
                    onResolved(resolved as? Uri)
                }
            )
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
