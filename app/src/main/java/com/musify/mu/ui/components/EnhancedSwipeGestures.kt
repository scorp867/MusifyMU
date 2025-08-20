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

    // Enhanced spring animation with Spotify-like elastic behavior
    val animatedOffsetX by animateFloatAsState(
        targetValue = if (isDragging) offsetX else 0f,
        animationSpec = if (isDragging) {
            // While dragging: tight spring for responsive feel
            spring(
                dampingRatio = 0.8f, // Slightly bouncy
                stiffness = 400f // Medium stiffness for smooth dragging
            )
        } else {
            // When released: bouncy spring like Spotify
            spring(
                dampingRatio = 0.6f, // More bouncy for elastic feel
                stiffness = 300f // Lower stiffness for smoother return
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

    // Enhanced background alpha that shows earlier and more prominently
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (gestureDirection != null && abs(animatedOffsetX) > touchSlop) {
            // Make background more visible sooner
            val progress = (abs(animatedOffsetX) / (screenWidth * 0.4f)).coerceIn(0f, 1f)
            progress.coerceAtLeast(0.2f) // Minimum 20% opacity when swiping
        } else 0f,
        animationSpec = tween(100), // Faster fade
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
                // Allow swipe up to half the screen width for better visibility
                val maxOffset = screenWidth * 0.5f // 50% of screen width

                // Apply resistance as swipe extends (rubber band effect)
                val resistance = if (abs(newOffsetX) > screenWidth * 0.3f) {
                    // After 30% of screen, apply increasing resistance
                    val excess = abs(newOffsetX) - (screenWidth * 0.3f)
                    val resistanceFactor = 1f - (excess / (screenWidth * 0.4f)).coerceIn(0f, 0.7f)
                    resistanceFactor
                } else {
                    1f
                }

                offsetX = (newOffsetX * resistance).coerceIn(-maxOffset, maxOffset)

                // Haptic feedback at threshold (earlier feedback)
                val threshold = screenWidth * 0.15f // 15% of screen width
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

                        // More lenient trigger conditions
                        val shouldTrigger = abs(offsetX) > threshold ||
                                (abs(offsetX) > threshold * 0.7f && velocityAbs > velocityThresholdPx)

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

    // Enhanced background with dynamic gradient based on swipe progress
    Box(
        modifier = modifier
            .background(
                brush = when (direction) {
                    SwipeDirection.RIGHT -> Brush.horizontalGradient(
                        colors = listOf(
                            color.copy(alpha = alpha * 0.6f),
                            color.copy(alpha = alpha * 0.3f),
                            Color.Transparent
                        ),
                        startX = 0f,
                        endX = 300f * progress
                    )
                    SwipeDirection.LEFT -> Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            color.copy(alpha = alpha * 0.3f),
                            color.copy(alpha = alpha * 0.6f)
                        ),
                        startX = Float.POSITIVE_INFINITY,
                        endX = Float.POSITIVE_INFINITY - (300f * progress)
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
            visible = alpha > 0.1f, // Show earlier for better feedback
            enter = fadeIn(animationSpec = tween(100)) + scaleIn(animationSpec = tween(100)),
            exit = fadeOut(animationSpec = tween(100)) + scaleOut(animationSpec = tween(100))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .graphicsLayer {
                        // Scale icon based on progress for dynamic feedback
                        val scale = 0.8f + (progress * 0.4f)
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