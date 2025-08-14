package com.musify.mu.ui.components

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.musify.mu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.musify.mu.data.media.EmbeddedArtCache

@Composable
fun Artwork(
    data: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    audioUri: String? = null,
    cacheKey: String? = null,
    albumId: Long? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Stable key for consistent caching
    val stableKey = remember(audioUri, albumId, data) {
        audioUri ?: cacheKey ?: albumId?.toString()
    }

    // Session-persistent artwork state - only updates when key changes
    var embeddedBitmap by remember(stableKey) { 
        mutableStateOf<android.graphics.Bitmap?>(
            // Initialize with cached value if available
            stableKey?.let { EmbeddedArtCache.getFromMemory(it) }
        ) 
    }

    // Load artwork only once per stable key
    LaunchedEffect(stableKey) {
        if (!stableKey.isNullOrBlank() && embeddedBitmap == null) {
            // Check if already cached (including session cache)
            val cached = EmbeddedArtCache.getFromMemory(stableKey)
            if (cached != null) {
                embeddedBitmap = cached
            } else if (!EmbeddedArtCache.isLoading(stableKey)) {
                // Only load if not already in progress
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val loaded = EmbeddedArtCache.loadEmbedded(context, stableKey)
                    if (loaded != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            embeddedBitmap = loaded
                        }
                    }
                }
            }
        }
    }

    val mod = if (shape != null) modifier.clip(shape) else modifier

    // Create fallback chain: embedded bitmap -> data -> album artwork
    val displayModel: Any? = embeddedBitmap ?: data ?: albumId?.let { id ->
        android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, 
            id
        )
    }

    val req = ImageRequest.Builder(context)
        .data(displayModel)
        .allowHardware(false)
        .crossfade(200) // Smooth but quick transitions
        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
        .error(R.drawable.ic_music_note)
        .placeholder(R.drawable.ic_music_note)
        .fallback(R.drawable.ic_music_note)
        .size(coil.size.Size.ORIGINAL)
        .scale(coil.size.Scale.FILL)
        .apply {
            // Use stable key for consistent caching
            stableKey?.let { 
                memoryCacheKey(it) 
                diskCacheKey(it) // Also set disk cache key for persistence
            }
        }
        .build()

    AsyncImage(
        model = req,
        contentDescription = contentDescription,
        modifier = mod,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop
    )
}


