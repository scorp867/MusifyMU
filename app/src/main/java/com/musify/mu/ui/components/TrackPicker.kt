package com.musify.mu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musify.mu.data.db.entities.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackPickerSheet(
    allTracks: List<Track>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = "Add tracks", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search tracks") }
            )
            Spacer(Modifier.height(8.dp))
            val filtered = remember(query, allTracks) {
                if (query.isBlank()) allTracks
                else allTracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
            }
            LazyColumn(modifier = Modifier.fillMaxHeight(0.7f)) {
                items(filtered.size) { idx ->
                    val t = filtered[idx]
                    val checked = selected.contains(t.mediaId)
                    ListItem(
                        headlineContent = { Text(t.title) },
                        supportingContent = { Text(t.artist) },
                        leadingContent = {
                            TrackArtwork(
                                trackUri = t.mediaId,
                                contentDescription = t.title,
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(6.dp)
                            )
                        },
                        trailingContent = {
                            Checkbox(checked = checked, onCheckedChange = { isChecked ->
                                selected = if (isChecked) selected + t.mediaId else selected - t.mediaId
                            })
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConfirm(selected.toList()) }, enabled = selected.isNotEmpty()) {
                    Text("Add")
                }
            }
        }
    }
}