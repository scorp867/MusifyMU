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
 * Spotify-like swipe gesture component with ultra-smooth physics
 * Features: Elastic resistance, velocity-based triggers, smooth animations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedSwipeableItem(
    onSwipeRight: () -> Unit, // Play Next
    onSwipeLeft: () -> Unit,  // Add to Queue/Remove
    isInQueue: Boolean = false,
    swipeThreshold: Float = 0.2f, // 20% of width to trigger action (more responsive)
    velocityThreshold: Dp = 80.dp, // Lower velocity threshold for easier triggering
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current

    // Enhanced swipe state management
    var offsetX by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var gestureDirection by remember { mutableStateOf<SwipeDirection?>(null) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    var screenWidth by remember { mutableStateOf(0f) }
    var lastDragTime by remember { mutableStateOf(0L) }
    var dragVelocity by remember { mutableStateOf(0f) }

    // Touch slop threshold (very low for responsive feel)
    val touchSlop = with(density) { (viewConfiguration.touchSlop * 0.5f).toDp().toPx() }
    val velocityThresholdPx = with(density) { velocityThreshold.toPx() }

    // Ultra-smooth spring animation with Spotify-like physics
    val animatedOffsetX by animateFloatAsState(
        targetValue = if (isDragging) offsetX else 0f,
        animationSpec = if (isDragging) {
            // While dragging: immediate response with slight resistance
            spring(
                dampingRatio = 0.9f, // Very responsive
                stiffness = 800f // High stiffness for immediate feedback
            )
        } else {
            // When released: bouncy spring like Spotify
            spring(
                dampingRatio = 0.5f, // Bouncy for elastic feel
                stiffness = 200f // Lower stiffness for smooth return
            )
        },
        finishedListener = {
            if (!isDragging) {
                offsetX = 0f
                gestureDirection = null
                hasTriggeredHaptic = false
                dragVelocity = 0f
            }
        },
        label = "swipeOffset"
    )

    // Dynamic background alpha with smooth transitions
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (gestureDirection != null && abs(animatedOffsetX) > touchSlop) {
            val progress = (abs(animatedOffsetX) / (screenWidth * 0.3f)).coerceIn(0f, 1f)
            // Show background immediately and build up smoothly
            (0.15f + progress * 0.85f).coerceIn(0f, 1f)
        } else 0f,
        animationSpec = tween(80), // Very fast fade for responsive feel
        label = "backgroundAlpha"
    )

    val draggableState = rememberDraggableState { delta ->
        val currentTime = System.currentTimeMillis()
        val timeDelta = currentTime - lastDragTime
        
        if (timeDelta > 0) {
            dragVelocity = delta / timeDelta.toFloat()
        }
        lastDragTime = currentTime

        val newOffsetX = offsetX + delta

        // Very responsive gesture detection
        if (!isDragging && abs(newOffsetX) > touchSlop) {
            isDragging = true
            gestureDirection = if (newOffsetX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
        }

        if (isDragging) {
            // Extended swipe range for better visibility
            val maxOffset = screenWidth * 0.6f // 60% of screen width

            // Smooth resistance curve (rubber band effect)
            val resistance = if (abs(newOffsetX) > screenWidth * 0.25f) {
                val excess = abs(newOffsetX) - (screenWidth * 0.25f)
                val resistanceFactor = 1f - (excess / (screenWidth * 0.35f)).coerceIn(0f, 0.8f)
                resistanceFactor
            } else {
                1f
            }

            offsetX = (newOffsetX * resistance).coerceIn(-maxOffset, maxOffset)

            // Early haptic feedback for better feel
            val threshold = screenWidth * 0.12f // 12% of screen width
            if (!hasTriggeredHaptic && abs(offsetX) > threshold) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                hasTriggeredHaptic = true
            }
        }
    }

    // Reset gesture state
    fun resetGesture() {
        isDragging = false
        offsetX = 0f
        gestureDirection = null
        hasTriggeredHaptic = false
        dragVelocity = 0f
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            screenWidth = coordinates.size.width.toFloat()
        }
    ) {
        // Enhanced background with smooth animations
        if (gestureDirection != null && backgroundAlpha > 0f) {
            SwipeBackground(
                direction = gestureDirection!!,
                alpha = backgroundAlpha,
                isInQueue = isInQueue,
                progress = abs(animatedOffsetX) / (screenWidth * 0.6f),
                modifier = Modifier.fillMaxSize()
            )
        }

        // Main content with ultra-smooth swipe offset
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = animatedOffsetX
                    // Subtle scale effect during swipe for premium feel
                    if (abs(animatedOffsetX) > touchSlop) {
                        val scale = 1f - (abs(animatedOffsetX) / (screenWidth * 2f))
                        scaleX = scale.coerceIn(0.95f, 1f)
                        scaleY = scale.coerceIn(0.95f, 1f)
                    } else {
                        scaleX = 1f
                        scaleY = 1f
                    }
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val threshold = screenWidth * swipeThreshold
                        val velocityAbs = abs(velocity)
                        val offsetAbs = abs(offsetX)

                        // Smart trigger conditions: distance OR velocity
                        val shouldTrigger = offsetAbs > threshold ||
                                (offsetAbs > threshold * 0.6f && velocityAbs > velocityThresholdPx) ||
                                (offsetAbs > threshold * 0.4f && velocityAbs > velocityThresholdPx * 1.5f)

                        if (shouldTrigger) {
                            when {
                                offsetX > 0 -> {
                                    // Swipe right - Play Next
                                    onSwipeRight()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                offsetX < 0 -> {
                                    // Swipe left - Add to Queue/Remove
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

/**
 * Enhanced swipe background with dynamic colors and smooth animations
 */
@Composable
private fun SwipeBackground(
    direction: SwipeDirection,
    alpha: Float,
    isInQueue: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when (direction) {
            SwipeDirection.RIGHT -> MaterialTheme.colorScheme.primary
            SwipeDirection.LEFT -> if (isInQueue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
        },
        animationSpec = tween(150),
        label = "swipeColor"
    )

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

    // Dynamic background with smooth gradients
    Box(
        modifier = modifier
            .background(
                brush = when (direction) {
                    SwipeDirection.RIGHT -> Brush.horizontalGradient(
                        colors = listOf(
                            color.copy(alpha = alpha * 0.8f),
                            color.copy(alpha = alpha * 0.4f),
                            Color.Transparent
                        ),
                        startX = 0f,
                        endX = 400f * progress
                    )
                    SwipeDirection.LEFT -> Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            color.copy(alpha = alpha * 0.4f),
                            color.copy(alpha = alpha * 0.8f)
                        ),
                        startX = Float.POSITIVE_INFINITY,
                        endX = Float.POSITIVE_INFINITY - (400f * progress)
                    )
                },
                shape = RoundedCornerShape(16.dp)
            ),
        contentAlignment = when (direction) {
            SwipeDirection.RIGHT -> Alignment.CenterStart
            SwipeDirection.LEFT -> Alignment.CenterEnd
        }
    ) {
        AnimatedVisibility(
            visible = alpha > 0.08f, // Show very early for immediate feedback
            enter = fadeIn(animationSpec = tween(80)) + scaleIn(animationSpec = tween(80)),
            exit = fadeOut(animationSpec = tween(80)) + scaleOut(animationSpec = tween(80))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .graphicsLayer {
                        // Dynamic scaling based on swipe progress
                        val scale = 0.7f + (progress * 0.5f)
                        scaleX = scale
                        scaleY = scale
                        // Subtle rotation for premium feel
                        rotationZ = if (direction == SwipeDirection.RIGHT) {
                            (progress * 5f).coerceIn(-5f, 5f)
                        } else {
                            (-progress * 5f).coerceIn(-5f, 5f)
                        }
                    }
            ) {
                if (direction == SwipeDirection.RIGHT) {
                    Icon(
                        imageVector = icon,
                        contentDescription = text,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
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
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

private enum class SwipeDirection {
    LEFT, RIGHT
}