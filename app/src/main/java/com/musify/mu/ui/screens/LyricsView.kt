package com.musify.mu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.data.repo.LyricsRepository
import com.musify.mu.lyrics.LrcLine
import com.musify.mu.lyrics.LrcParser
import com.musify.mu.playback.LocalMediaController
import kotlinx.coroutines.launch

@Composable
fun LyricsView(navController: NavController) {
    val context = LocalContext.current
    val lyricsRepo = remember { LyricsRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    var lyricsText by remember { mutableStateOf<String?>(null) }
    var lrcLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }

    val controller = LocalMediaController.current
    val currentMediaId: String? = controller?.currentMediaItem?.mediaId

    LaunchedEffect(currentMediaId) {
        currentMediaId?.let { id ->
            coroutineScope.launch {
                lyricsRepo.get(id)?.let { map ->
                    if (map.type == "lrc") {
                        // Load file text
                        val text = context.contentResolver.openInputStream(android.net.Uri.parse(map.uriOrText))
                            ?.bufferedReader()?.use { it.readText() }
                        lyricsText = text
                        lrcLines = text?.let { LrcParser.parse(it) } ?: emptyList()
                    } else {
                        lyricsText = map.uriOrText
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (lrcLines.isNotEmpty()) {
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
        } else {
            Text(
                lyricsText ?: "No lyrics found",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}
