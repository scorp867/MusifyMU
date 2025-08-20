package com.musify.mu.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * Enhanced swipe gesture component with Spotify-like behavior
 * Implements proper directional touch slop, velocity filters, and gesture locking
 */
@Composable
fun EnhancedSwipeableItem(
    onSwipeRight: () -> Unit, // Play Next
    onSwipeLeft: () -> Unit,  // Add to Queue
    isInQueue: Boolean = false,
    swipeThreshold: Float = 0.3f, // 30% of width to trigger action
    velocityThreshold: Dp = 125.dp, // Minimum velocity to trigger
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current

    // Swipe state management
    var offsetX by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isGestureLocked by remember { mutableStateOf(false) }
    var gestureDirection by remember { mutableStateOf<SwipeDirection?>(null) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    // Touch slop threshold
    val touchSlop = with(density) { viewConfiguration.touchSlop.toDp().toPx() }
    val velocityThresholdPx = with(density) { velocityThreshold.toPx() }

    // Animation states
    val animatedOffsetX by animateFloatAsState(
        targetValue = if (isDragging) offsetX else 0f,
        animationSpec = if (isDragging) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        },
        finishedListener = {
            if (!isDragging) {
                offsetX = 0f
                gestureDirection = null
                hasTriggeredHaptic = false
            }
        },
        label = "swipeOffset"
    )

    val backgroundAlpha by animateFloatAsState(
        targetValue = if (gestureDirection != null && abs(animatedOffsetX) > touchSlop * 2) {
            (abs(animatedOffsetX) / (density.density * 100)).coerceIn(0f, 1f)
        } else 0f,
        animationSpec = tween(150),
        label = "backgroundAlpha"
    )

    val draggableState = rememberDraggableState { delta ->
        if (!isGestureLocked) {
            val newOffsetX = offsetX + delta

            // Directional touch slop - lock gesture direction once threshold is passed
            if (!isDragging && abs(newOffsetX) > touchSlop) {
                isDragging = true
                isGestureLocked = true
                gestureDirection = if (newOffsetX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
            }

            if (isDragging) {
                // Limit swipe distance to prevent over-swiping
                val maxOffset = density.density * 120 // Max 120dp
                offsetX = newOffsetX.coerceIn(-maxOffset, maxOffset)

                // Haptic feedback at threshold
                val threshold = density.density * 80 // 80dp threshold
                if (!hasTriggeredHaptic && abs(offsetX) > threshold) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    hasTriggeredHaptic = true
                }
            }
        }
    }

    // Reset gesture state
    fun resetGesture() {
        isDragging = false
        isGestureLocked = false
        offsetX = 0f
        gestureDirection = null
        hasTriggeredHaptic = false
    }

    Box(modifier = modifier) {
        // Background indicators
        if (gestureDirection != null && backgroundAlpha > 0f) {
            SwipeBackground(
                direction = gestureDirection!!,
                alpha = backgroundAlpha,
                isInQueue = isInQueue,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Main content with swipe offset
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = animatedOffsetX
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val threshold = density.density * 100 * swipeThreshold // Convert threshold to px
                        val velocityAbs = abs(velocity)

                        // Check if gesture should trigger action
                        val shouldTrigger = abs(offsetX) > threshold && velocityAbs > velocityThresholdPx

                        if (shouldTrigger) {
                            when {
                                offsetX > 0 -> {
                                    // Swipe right - Play Next
                                    onSwipeRight()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                offsetX < 0 -> {
                                    // Swipe left - Add to Queue
                                    onSwipeLeft()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }

                        resetGesture()
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeBackground(
    direction: SwipeDirection,
    alpha: Float,
    isInQueue: Boolean,
    modifier: Modifier = Modifier
) {
    val color = when (direction) {
        SwipeDirection.RIGHT -> MaterialTheme.colorScheme.primary
        SwipeDirection.LEFT -> if (isInQueue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    }

    val (icon, text) = when (direction) {
        SwipeDirection.RIGHT -> {
            if (isInQueue) {
                Icons.Default.SkipNext to "Play Next"
            } else {
                Icons.Default.SkipNext to "Play Next"
            }
        }
        SwipeDirection.LEFT -> {
            if (isInQueue) {
                Icons.Default.Delete to "Remove"
            } else {
                Icons.Default.QueueMusic to "Add to Queue"
            }
        }
    }

    Box(
        modifier = modifier
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        color.copy(alpha = alpha * 0.1f),
                        color.copy(alpha = alpha * 0.3f),
                        color.copy(alpha = alpha * 0.1f)
                    )
                ),
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = when (direction) {
            SwipeDirection.RIGHT -> Alignment.CenterStart
            SwipeDirection.LEFT -> Alignment.CenterEnd
        }
    ) {
        AnimatedVisibility(
            visible = alpha > 0.3f, // Only show when swipe is significant
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = color.copy(alpha = alpha),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = color.copy(alpha = alpha)
                )
            }
        }
    }
}

private enum class SwipeDirection {
    LEFT, RIGHT
}