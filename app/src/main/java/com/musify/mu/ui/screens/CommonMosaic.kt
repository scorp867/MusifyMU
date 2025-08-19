package com.musify.mu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musify.mu.ui.components.Artwork

@Composable
fun MosaicArtwork(arts: List<String?>) {
    val list = arts.filterNotNull()
    when (list.size) {
        0 -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        1 -> Artwork(data = list[0], audioUri = null, albumId = null, contentDescription = null, modifier = Modifier.fillMaxSize())
        2 -> Row(Modifier.fillMaxSize()) {
            Artwork(data = list[0], audioUri = null, albumId = null, contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(2.dp))
            Artwork(data = list[1], audioUri = null, albumId = null, contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                Artwork(data = list.getOrNull(0), audioUri = null, albumId = null, contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(2.dp))
                Artwork(data = list.getOrNull(1), audioUri = null, albumId = null, contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.weight(1f)) {
                Artwork(data = list.getOrNull(2), audioUri = null, albumId = null, contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(2.dp))
                Artwork(data = list.getOrNull(3), audioUri = null, albumId = null, contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}


