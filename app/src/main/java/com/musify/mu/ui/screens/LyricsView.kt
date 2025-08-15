package com.musify.mu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.data.repo.LyricsStateStore
import com.musify.mu.lyrics.LrcLine
import com.musify.mu.playback.LocalMediaController

@Composable
fun LyricsView(navController: NavController) {
    val context = LocalContext.current
    val lyricsStateStore = remember { LyricsStateStore.getInstance(context) }
    
    // Observe lyrics state from the store
    val lyricsState by lyricsStateStore.currentLyrics.collectAsState()
    
    val controller = LocalMediaController.current
    val currentMediaId: String? = controller?.currentMediaItem?.mediaId
    
    // Extract values from lyrics state
    val lyricsText = lyricsState?.text
    val lrcLines = lyricsState?.lrcLines ?: emptyList()
    val isLrc = lyricsState?.isLrc ?: false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            lyricsState?.isLoading == true -> {
                // Show loading indicator
                CircularProgressIndicator()
            }
            lrcLines.isNotEmpty() -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(lrcLines.size) { idx ->
                        val line = lrcLines[idx]
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            else -> {
                Text(
                    lyricsText ?: "No lyrics found",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
