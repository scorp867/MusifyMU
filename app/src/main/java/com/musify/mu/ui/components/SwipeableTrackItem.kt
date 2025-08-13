package com.musify.mu.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.rememberDismissState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.musify.mu.data.db.entities.Track

@Composable
fun SwipeableTrackItem(
    track: Track,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    
    val dismissState = rememberDismissState(
        confirmStateChange = { dismissValue ->
            when (dismissValue) {
                DismissValue.DismissedToEnd -> {
                    // Swipe right - Play next
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlayNext()
                    false // Don't dismiss the item
                }
                DismissValue.DismissedToStart -> {
                    // Swipe left - Add to queue
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddToQueue()
                    false // Don't dismiss the item
                }
                else -> false
            }
        }
    )
    
    SwipeToDismiss(
        state = dismissState,
        modifier = modifier,
        directions = setOf(DismissDirection.StartToEnd, DismissDirection.EndToStart),
        background = {
            SwipeBackground(
                dismissDirection = dismissState.dismissDirection,
                dismissValue = dismissState.currentValue
            )
        },
        dismissContent = {
            TrackItemContent(
                track = track,
                onClick = onClick
            )
        }
    )
}

@Composable
private fun SwipeBackground(
    dismissDirection: DismissDirection?,
    dismissValue: DismissValue
) {
    val color = when (dismissDirection) {
        DismissDirection.StartToEnd -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        DismissDirection.EndToStart -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
        null -> Color.Transparent
    }
    
    val icon = when (dismissDirection) {
        DismissDirection.StartToEnd -> Icons.Default.PlaylistPlay
        DismissDirection.EndToStart -> Icons.Default.PlaylistAdd
        null -> null
    }
    
    val text = when (dismissDirection) {
        DismissDirection.StartToEnd -> "Play Next"
        DismissDirection.EndToStart -> "Add to Queue"
        null -> ""
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = when (dismissDirection) {
            DismissDirection.StartToEnd -> Alignment.CenterStart
            DismissDirection.EndToStart -> Alignment.CenterEnd
            null -> Alignment.Center
        }
    ) {
        if (icon != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = when (dismissDirection) {
                        DismissDirection.StartToEnd -> MaterialTheme.colorScheme.primary
                        DismissDirection.EndToStart -> MaterialTheme.colorScheme.secondary
                        null -> Color.Transparent
                    }
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = when (dismissDirection) {
                        DismissDirection.StartToEnd -> MaterialTheme.colorScheme.primary
                        DismissDirection.EndToStart -> MaterialTheme.colorScheme.secondary
                        null -> Color.Transparent
                    }
                )
            }
        }
    }
}

@Composable
private fun TrackItemContent(
    track: Track,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album artwork
            Card(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                AsyncImage(
                    model = track.artUri,
                    contentDescription = track.album,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Track info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Duration
            track.durationMs.let { duration ->
                val minutes = duration / 60000
                val seconds = (duration % 60000) / 1000
                Text(
                    text = String.format("%d:%02d", minutes, seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}