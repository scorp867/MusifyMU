package com.musify.mu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

@Composable
fun MoreOptionsMenu(
    isGymModeEnabled: Boolean,
    canEnableGymMode: Boolean,
    onGymModeToggle: () -> Unit,
    onCustomAlbumArt: () -> Unit,
    onEditSongDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .size(40.dp)
                .background(
                    Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(20.dp)
                )
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "More options",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // Use Popup for true overlay behavior
        if (expanded) {
            Popup(
                onDismissRequest = { expanded = false },
                alignment = Alignment.TopEnd
            ) {
                Surface(
                    modifier = Modifier
                        .width(280.dp)
                        .padding(top = 50.dp, end = 8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {

                        // Gym Mode Toggle
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Gym Mode",
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (canEnableGymMode) {
                                            "Voice control via headset microphone only"
                                        } else {
                                            "Connect headset with microphone to enable"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (canEnableGymMode) {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        } else {
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                        }
                                    )
                                }

                                Switch(
                                    checked = isGymModeEnabled,
                                    onCheckedChange = { onGymModeToggle() },
                                    enabled = canEnableGymMode,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }

                        // Divider
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )

                        // Custom Album Art Option
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Change Album Art")
                                }
                            },
                            onClick = {
                                expanded = false
                                onCustomAlbumArt()
                            }
                        )
                        
                        // Edit Song Details Option
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Edit Song Details")
                                }
                            },
                            onClick = {
                                expanded = false
                                onEditSongDetails()
                            }
                        )
                    }
                }
            }
        }
    }
}
