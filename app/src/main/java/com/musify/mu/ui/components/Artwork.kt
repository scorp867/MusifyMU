package com.musify.mu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.musify.mu.R

@Composable
fun Artwork(
    data: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    val req = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
        .data(data)
        .crossfade(true)
        .error(R.drawable.ic_music_note)
        .placeholder(R.drawable.ic_music_note)
        .build()
    val mod = if (shape != null) modifier.clip(shape) else modifier
    AsyncImage(
        model = req,
        contentDescription = contentDescription,
        modifier = mod
    )
}


