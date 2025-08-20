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
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import kotlin.math.sign

/**
 * Enhanced swipe gesture component with Spotify-like spring behavior
 * Implements smooth elastic animations and extended swipe range
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedSwipeableItem(
    onSwipeRight: () -> Unit, // Play Next
    onSwipeLeft: () -> Unit,  // Add to Queue
    isInQueue: Boolean = false,
    swipeThreshold: Float = 0.25f, // 25% of width to trigger action
    velocityThreshold: Dp = 100.dp, // Lower velocity threshold for easier triggering
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
    var screenWidth by remember { mutableStateOf(0f) }

    // Touch slop threshold
    val touchSlop = with(density) { viewConfiguration.touchSlop.toDp().toPx() }
    val velocityThresholdPx = with(density) { velocityThreshold.toPx() }

    // Spotify-like direct animation - immediate response while dragging, smooth return
    val animatedOffsetX by animateFloatAsState(
        targetValue = if (isDragging) offsetX else 0f,
        animationSpec = if (isDragging) {
            // While dragging: no animation, direct follow for immediate response
            snap()
        } else {
            // When released: quick smooth return like Spotify
            spring(
                dampingRatio = 0.75f, // Less bouncy for cleaner feel
                stiffness = 500f // Higher stiffness for quicker return
            )
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

    // Spotify-like background alpha - immediate and more visible
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (gestureDirection != null && abs(animatedOffsetX) > touchSlop) {
            // Immediate visibility like Spotify
            val progress = (abs(animatedOffsetX) / (screenWidth * 0.3f)).coerceIn(0f, 1f)
            progress.coerceAtLeast(0.4f) // Higher minimum opacity for better visibility
        } else 0f,
        animationSpec = snap(), // Immediate response, no fade delay
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
                // Spotify-like swipe range - more generous but with subtle resistance
                val maxOffset = screenWidth * 0.4f // 40% of screen width

                // Minimal resistance for natural feel like Spotify
                val resistance = if (abs(newOffsetX) > screenWidth * 0.25f) {
                    // Light resistance after 25% of screen
                    val excess = abs(newOffsetX) - (screenWidth * 0.25f)
                    val resistanceFactor = 1f - (excess / (screenWidth * 0.3f)).coerceIn(0f, 0.4f)
                    resistanceFactor.coerceAtLeast(0.6f) // Keep it responsive
                } else {
                    1f
                }

                offsetX = (newOffsetX * resistance).coerceIn(-maxOffset, maxOffset)

                // Earlier haptic feedback like Spotify
                val threshold = screenWidth * 0.1f // 10% of screen width for immediate feedback
                if (!hasTriggeredHaptic && abs(offsetX) > threshold) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // Lighter haptic
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

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            screenWidth = coordinates.size.width.toFloat()
        }
    ) {
        // Enhanced background that shows more prominently
        if (gestureDirection != null && backgroundAlpha > 0f) {
            SwipeBackground(
                direction = gestureDirection!!,
                alpha = backgroundAlpha,
                isInQueue = isInQueue,
                progress = abs(animatedOffsetX) / (screenWidth * 0.5f),
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
                        val threshold = screenWidth * swipeThreshold // 25% of screen width
                        val velocityAbs = abs(velocity)

                        // Spotify-like trigger conditions - more responsive
                        val shouldTrigger = abs(offsetX) > threshold ||
                                (abs(offsetX) > threshold * 0.6f && velocityAbs > velocityThresholdPx * 0.7f)

                        if (shouldTrigger) {
                            when {
                                offsetX > 0 -> {
                                    // Swipe right - Play Next
                                    onSwipeRight()
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress) // Stronger success feedback
                                }
                                offsetX < 0 -> {
                                    // Swipe left - Add to Queue
                                    onSwipeLeft()
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress) // Stronger success feedback
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
    progress: Float, // 0 to 1 progress of swipe
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

    // Spotify-like solid background with subtle gradient
    Box(
        modifier = modifier
            .background(
                brush = when (direction) {
                    SwipeDirection.RIGHT -> Brush.horizontalGradient(
                        colors = listOf(
                            color.copy(alpha = alpha * 0.9f), // More solid like Spotify
                            color.copy(alpha = alpha * 0.7f),
                            color.copy(alpha = alpha * 0.3f)
                        ),
                        startX = 0f,
                        endX = 400f // Fixed width for consistent look
                    )
                    SwipeDirection.LEFT -> Brush.horizontalGradient(
                        colors = listOf(
                            color.copy(alpha = alpha * 0.3f),
                            color.copy(alpha = alpha * 0.7f),
                            color.copy(alpha = alpha * 0.9f) // More solid like Spotify
                        ),
                        startX = Float.POSITIVE_INFINITY - 400f, // Fixed width
                        endX = Float.POSITIVE_INFINITY
                    )
                },
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = when (direction) {
            SwipeDirection.RIGHT -> Alignment.CenterStart
            SwipeDirection.LEFT -> Alignment.CenterEnd
        }
    ) {
        AnimatedVisibility(
            visible = alpha > 0.2f, // Show when more visible for cleaner look
            enter = fadeIn(animationSpec = snap()) + scaleIn(animationSpec = snap()), // Immediate appearance like Spotify
            exit = fadeOut(animationSpec = tween(150)) + scaleOut(animationSpec = tween(150))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(horizontal = 24.dp) // Closer to edge like Spotify
                    .graphicsLayer {
                        // Subtle scale animation like Spotify
                        val scale = 1f + (progress * 0.1f) // More subtle scaling
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                if (direction == SwipeDirection.RIGHT) {
                    Icon(
                        imageVector = icon,
                        contentDescription = text,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = text,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

private enum class SwipeDirection {
    LEFT, RIGHT
}