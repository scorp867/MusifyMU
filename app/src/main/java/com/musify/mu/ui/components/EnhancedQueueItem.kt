package com.musify.mu.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musify.mu.data.db.entities.Track
import org.burnoutcrew.reorderable.ReorderableLazyListState
import org.burnoutcrew.reorderable.detectReorderAfterLongPress

/**
 * Enhanced queue track item with optimized drag and drop animations
 * Uses hardware acceleration and efficient rendering for 60fps performance
 */
@Composable
fun EnhancedQueueTrackItem(
    track: Track,
    isCurrentlyPlaying: Boolean,
    isDragging: Boolean,
    isMarkedPlayNext: Boolean = false,
    position: Int,
    onClick: () -> Unit,
    reorderState: ReorderableLazyListState,
    modifier: Modifier = Modifier,
    config: DragDropConfig = DragDropConfig()
) {
    val haptic = LocalHapticFeedback.current

    // Optimized animation states with performance considerations
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessHigh // Instant visual feedback
        ),
        label = "scale"
    )

    val elevation by animateDpAsState(
        targetValue = if (isDragging) {
            if (config.enableLightweightShadow) 8.dp else 16.dp
        } else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh // Fast response
        ),
        label = "elevation"
    )

    val rotation by animateFloatAsState(
        targetValue = if (isDragging) 1.5f else 0f, // Reduced rotation for performance
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "rotation"
    )

    // Color animations
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
            isCurrentlyPlaying -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            isMarkedPlayNext -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(300),
        label = "backgroundColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.primary
            isCurrentlyPlaying -> MaterialTheme.colorScheme.secondary
            isMarkedPlayNext -> MaterialTheme.colorScheme.tertiary
            else -> Color.Transparent
        },
        animationSpec = tween(300),
        label = "borderColor"
    )

    // Pulsing animation for currently playing
    val pulseScale by animateFloatAsState(
        targetValue = if (isCurrentlyPlaying) 1.02f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pulseScale
                scaleY = scale * pulseScale
                rotationZ = rotation

                // Hardware acceleration for smooth rendering
                if (config.enableHardwareAcceleration) {
                    compositingStrategy = if (isDragging) {
                        CompositingStrategy.Offscreen // Offscreen rendering during drag
                    } else {
                        CompositingStrategy.Auto
                    }
                }

                // Optimize alpha during drag for better performance
                alpha = if (isDragging) 0.95f else 1f
            }
            .clickable {
                if (config.enableHapticFeedback) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onClick()
            },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        shape = RoundedCornerShape(16.dp),
        border = if (borderColor != Color.Transparent) {
            androidx.compose.foundation.BorderStroke(
                width = if (isDragging) 2.dp else 1.dp,
                color = borderColor
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Position indicator with animation
            AnimatedContent(
                targetState = position,
                transitionSpec = {
                    slideInVertically { height -> height } + fadeIn() togetherWith
                            slideOutVertically { height -> -height } + fadeOut()
                },
                label = "position"
            ) { pos ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            brush = if (isCurrentlyPlaying) {
                                Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.secondary,
                                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                                    )
                                )
                            } else {
                                Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrentlyPlaying) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Currently playing",
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = pos.toString(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Album artwork with enhanced loading
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Artwork(
                    data = track.artUri,
                    mediaUri = track.mediaId,
                    albumId = track.albumId,
                    cacheKey = track.mediaId,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize(),
                    enableOnDemand = false
                )

                // Play next indicator
                if (isMarkedPlayNext) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .background(
                                MaterialTheme.colorScheme.tertiary,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Play next",
                            tint = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Track information with enhanced typography
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isCurrentlyPlaying) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Drag handle integrated with reorderable state (long-press to drag)
            IconButton(
                onClick = { },
                modifier = Modifier
                    .padding(8.dp)
                    .detectReorderAfterLongPress(reorderState)
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Enhanced swipe background with dynamic colors and animations
 * Context-aware icons and actions based on usage
 */
@Composable
fun EnhancedSwipeBackground(
    dismissDirection: androidx.compose.material.DismissDirection?,
    modifier: Modifier = Modifier,
    isInQueue: Boolean = false // Different behavior for queue vs library items
) {
    val color by animateColorAsState(
        targetValue = when (dismissDirection) {
            androidx.compose.material.DismissDirection.StartToEnd -> {
                if (isInQueue) MaterialTheme.colorScheme.primary // Play Next in queue
                else MaterialTheme.colorScheme.primary // Play Next in library
            }
            androidx.compose.material.DismissDirection.EndToStart -> {
                if (isInQueue) MaterialTheme.colorScheme.error // Remove from queue
                else MaterialTheme.colorScheme.secondary // Add to User Queue in library
            }
            null -> Color.Transparent
        },
        animationSpec = tween(300),
        label = "swipeColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (dismissDirection != null) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "swipeScale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.1f),
                        color.copy(alpha = 0.3f),
                        color.copy(alpha = 0.1f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            ),
        contentAlignment = when (dismissDirection) {
            androidx.compose.material.DismissDirection.StartToEnd -> Alignment.CenterStart
            androidx.compose.material.DismissDirection.EndToStart -> Alignment.CenterEnd
            null -> Alignment.Center
        }
    ) {
        AnimatedVisibility(
            visible = dismissDirection != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            val (icon, text) = when (dismissDirection) {
                androidx.compose.material.DismissDirection.StartToEnd -> {
                    if (isInQueue) {
                        Icons.Default.SkipNext to "Play Next"
                    } else {
                        Icons.Default.SkipNext to "Play Next" // Swipe right = Play Next
                    }
                }
                androidx.compose.material.DismissDirection.EndToStart -> {
                    if (isInQueue) {
                        Icons.Default.Delete to "Remove"
                    } else {
                        Icons.Default.QueueMusic to "Add to Queue" // Swipe left = Add to Queue
                    }
                }
                null -> Icons.Default.Info to ""
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = color,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = color
                )
            }
        }
    }
}
