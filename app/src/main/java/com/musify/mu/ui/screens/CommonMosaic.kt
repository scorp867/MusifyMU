package com.musify.mu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun MosaicArtwork(arts: List<String?>) {
    val context = LocalContext.current
    val list = arts.filterNotNull()
    when (list.size) {
        0 -> Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
        1 -> SimpleArtworkImage(imageUrl = list[0], modifier = Modifier.fillMaxSize())
        2 -> Row(Modifier.fillMaxSize()) {
            SimpleArtworkImage(imageUrl = list[0], modifier = Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(2.dp))
            SimpleArtworkImage(imageUrl = list[1], modifier = Modifier.weight(1f).fillMaxHeight())
        }
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                SimpleArtworkImage(imageUrl = list.getOrNull(0), modifier = Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(2.dp))
                SimpleArtworkImage(imageUrl = list.getOrNull(1), modifier = Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.weight(1f)) {
                SimpleArtworkImage(imageUrl = list.getOrNull(2), modifier = Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(2.dp))
                SimpleArtworkImage(imageUrl = list.getOrNull(3), modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SimpleArtworkImage(
    imageUrl: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}


