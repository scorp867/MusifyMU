package com.musify.mu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import kotlinx.coroutines.launch

/**
 * Dialog for editing song details
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailsEditor(
    track: Track,
    onDismiss: () -> Unit,
    onSave: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    var editedTitle by remember { mutableStateOf(track.title) }
    var editedArtist by remember { mutableStateOf(track.artist) }
    var editedAlbum by remember { mutableStateOf(track.album) }
    var editedGenre by remember { mutableStateOf(track.genre ?: "") }
    var editedYear by remember { mutableStateOf(track.year?.toString() ?: "") }

    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = "Edit Song Details",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editedArtist,
                    onValueChange = { editedArtist = it },
                    label = { Text("Artist") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editedAlbum,
                    onValueChange = { editedAlbum = it },
                    label = { Text("Album") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editedGenre,
                    onValueChange = { editedGenre = it },
                    label = { Text("Genre") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editedYear,
                    onValueChange = { editedYear = it },
                    label = { Text("Year") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Read-only fields
                Text(
                    text = "Duration: ${formatDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "File: ${track.mediaId.substringAfterLast("/")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        isLoading = true
                        try {
                            val updatedTrack = track.copy(
                                title = editedTitle.takeIf { it.isNotBlank() } ?: track.title,
                                artist = editedArtist.takeIf { it.isNotBlank() } ?: track.artist,
                                album = editedAlbum.takeIf { it.isNotBlank() } ?: track.album,
                                genre = editedGenre.takeIf { it.isNotBlank() },
                                year = editedYear.toIntOrNull()
                            )
                            onSave(updatedTrack)
                            onDismiss()
                        } catch (e: Exception) {
                            // Handle error
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && editedTitle.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Format duration in milliseconds to mm:ss format
 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
