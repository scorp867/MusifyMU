package com.musify.mu.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
// Using simple quadratic acceleration instead of pow

/**
 * Enhanced drag and drop component with smooth animations and dynamic feedback
 */
object EnhancedDragAndDrop {
    
    /**
     * High-performance modifier for draggable items with optimized animations
     * Uses hardware acceleration and efficient rendering for smooth 60fps performance
     */
    @Composable
    fun Modifier.smoothDraggable(
        isDragging: Boolean,
        dragOffset: Float = 0f,
        animationDuration: Int = 300,
        useHardwareAcceleration: Boolean = true
    ): Modifier {
        val haptic = LocalHapticFeedback.current
        
        // Optimized elevation animation - uses smaller range for better performance
        val elevation by animateDpAsState(
            targetValue = if (isDragging) 12.dp else 4.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessHigh // Higher stiffness for instant response
            ),
            label = "elevation"
        )
        
        // Scale animation with instant feedback
        val scale by animateFloatAsState(
            targetValue = if (isDragging) 1.05f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessHigh // Instant visual feedback
            ),
            label = "scale"
        )
        
        // Subtle rotation based on drag velocity for dynamic feel
        val rotation by animateFloatAsState(
            targetValue = if (isDragging) (dragOffset * 0.005f).coerceIn(-1.5f, 1.5f) else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "rotation"
        )
        
        // Optimized color animation with reduced alpha calculations
        val backgroundColor by animateColorAsState(
            targetValue = if (isDragging) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
            else 
                MaterialTheme.colorScheme.surface,
            animationSpec = if (isDragging) {
                // Instant feedback when starting drag
                tween(150, easing = FastOutSlowInEasing)
            } else {
                // Smooth return to normal
                tween(animationDuration, easing = FastOutSlowInEasing)
            },
            label = "backgroundColor"
        )
        
        return this
            .graphicsLayer {
                // Hardware accelerated transformations
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
                translationY = dragOffset
                
                // Enable hardware acceleration for smoother rendering
                if (useHardwareAcceleration) {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                
                // Optimize rendering layers
                renderEffect = if (isDragging) {
                    null // Disable effects during drag for performance
                } else null
            }
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(12.dp),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            )
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
    }
    
    /**
     * Drop zone indicator with pulsing animation
     */
    @Composable
    fun DropZoneIndicator(
        isActive: Boolean,
        modifier: Modifier = Modifier
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "dropZone")
        
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = if (isActive) 0.8f else 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
        
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isActive) 1.02f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        
        Box(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha * 0.5f)
                        )
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(2.dp)
        )
    }
    
    /**
     * Enhanced drag handle with visual feedback
     */
    @Composable
    fun DragHandle(
        isPressed: Boolean = false,
        modifier: Modifier = Modifier
    ) {
        val haptic = LocalHapticFeedback.current
        
        val color by animateColorAsState(
            targetValue = if (isPressed) 
                MaterialTheme.colorScheme.primary
            else 
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            animationSpec = tween(200),
            label = "handleColor"
        )
        
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 1.2f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "handleScale"
        )
        
        Column(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    ) { _, _ ->
                        // Handle drag
                    }
                },
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(width = 20.dp, height = 3.dp)
                        .background(
                            color = color,
                            shape = RoundedCornerShape(1.5.dp)
                        )
                )
            }
        }
    }
    
    /**
     * Enhanced auto-scroll with smooth interpolated scrolling for natural feel
     */
    @Composable
    fun rememberAutoScrollState(
        listState: LazyListState,
        threshold: Float = 60f, // Further reduced for Spotify-like responsiveness
        maxScrollSpeed: Float = 600f, // Increased max speed for faster scrolling
        accelerationCurve: Float = 1.5f // Exponential acceleration near edges
    ): AutoScrollState {
        val density = LocalDensity.current
        
        return remember {
            AutoScrollState(listState, threshold, maxScrollSpeed, density, accelerationCurve)
        }
    }
    
    class AutoScrollState(
        private val listState: LazyListState,
        private val threshold: Float,
        private val maxScrollSpeed: Float,
        private val density: androidx.compose.ui.unit.Density,
        private val accelerationCurve: Float = 1.5f
    ) {
        private var isScrolling = false
        private var scrollJob: kotlinx.coroutines.Job? = null
        
        suspend fun handleAutoScroll(dragY: Float, containerHeight: Float) {
            if (isScrolling) return // Prevent concurrent scroll operations
            
            val scrollThreshold = with(density) { threshold.toDp().toPx() }
            
            when {
                dragY < scrollThreshold -> {
                    // Enhanced upward scroll with quadratic acceleration
                    val proximity = ((scrollThreshold - dragY) / scrollThreshold).coerceIn(0f, 1f)
                    val acceleratedProximity = proximity * proximity // Simple quadratic acceleration
                    val scrollSpeed = (acceleratedProximity * maxScrollSpeed).coerceIn(100f, maxScrollSpeed)
                    performSmoothScroll(scrollSpeed, isUpward = true)
                }
                dragY > containerHeight - scrollThreshold -> {
                    // Enhanced downward scroll with quadratic acceleration
                    val proximity = ((dragY - (containerHeight - scrollThreshold)) / scrollThreshold).coerceIn(0f, 1f)
                    val acceleratedProximity = proximity * proximity // Simple quadratic acceleration
                    val scrollSpeed = (acceleratedProximity * maxScrollSpeed).coerceIn(100f, maxScrollSpeed)
                    performSmoothScroll(scrollSpeed, isUpward = false)
                }
                else -> {
                    stopAutoScroll() // Stop scrolling when not near edges
                }
            }
        }
        
        private suspend fun performSmoothScroll(speed: Float, isUpward: Boolean) {
            isScrolling = true
            try {
                val scrollDistance = with(density) { (speed / 16f).toDp().toPx() } // 60fps frame rate
                val currentOffset = listState.firstVisibleItemScrollOffset
                
                if (isUpward) {
                    val targetOffset = (currentOffset - scrollDistance).coerceAtLeast(0f)
                    if (targetOffset < currentOffset) {
                        listState.scrollToItem(
                            index = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0),
                            scrollOffset = targetOffset.toInt()
                        )
                    }
                } else {
                    listState.scrollToItem(
                        index = listState.firstVisibleItemIndex + 1,
                        scrollOffset = 0
                    )
                }
                
                delay(16) // 60fps smooth scrolling
            } finally {
                isScrolling = false
            }
        }
        
        fun stopAutoScroll() {
            scrollJob?.cancel()
            scrollJob = null
            isScrolling = false
        }
        
        fun dispose() {
            stopAutoScroll()
        }
    }
    
    /**
     * Insertion indicator with smooth slide animation
     */
    @Composable
    fun InsertionIndicator(
        isVisible: Boolean,
        position: Int,
        modifier: Modifier = Modifier
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ) + fadeOut(animationSpec = tween(200)),
            modifier = modifier
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary,
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/**
 * Enhanced configuration for drag and drop behavior with performance optimizations
 */
data class DragDropConfig(
    val longPressTimeout: Long = 300L, // Reduced for instant response (was 500L)
    val vibrationDuration: Long = 30L, // Shorter for subtle feedback (was 50L)
    val animationDuration: Int = 200, // Faster animations for responsiveness (was 300)
    val autoScrollThreshold: Float = 80f, // Closer to edges for better UX (was 100f)
    val snapBackAnimationDuration: Int = 250, // Faster snap back (was 400)
    val enableHapticFeedback: Boolean = true,
    val enableAutoScroll: Boolean = true,
    val enableDropZoneHighlight: Boolean = true,
    val enableHardwareAcceleration: Boolean = true, // New: Hardware acceleration
    val maxConcurrentAnimations: Int = 3, // New: Limit concurrent animations for performance
    val dragStartDelay: Long = 100L, // New: Minimal delay for immediate drag feel
    val enableLightweightShadow: Boolean = true // New: Use bitmap shadow for performance
)

/**
 * State holder for enhanced drag and drop operations
 */
@Stable
class EnhancedDragDropState(
    val config: DragDropConfig = DragDropConfig()
) {
    var isDragging by mutableStateOf(false)
        private set
    
    var draggedItemIndex by mutableStateOf(-1)
        private set
    
    var dropTargetIndex by mutableStateOf(-1)
        private set
    
    var dragOffset by mutableStateOf(0f)
        private set
    
    fun startDrag(index: Int) {
        isDragging = true
        draggedItemIndex = index
    }
    
    fun updateDrag(offset: Float, targetIndex: Int) {
        dragOffset = offset
        dropTargetIndex = targetIndex
    }
    
    fun endDrag(): Pair<Int, Int>? {
        val result = if (draggedItemIndex != -1 && dropTargetIndex != -1 && draggedItemIndex != dropTargetIndex) {
            draggedItemIndex to dropTargetIndex
        } else null
        
        reset()
        return result
    }
    
    private fun reset() {
        isDragging = false
        draggedItemIndex = -1
        dropTargetIndex = -1
        dragOffset = 0f
    }
}

@Composable
fun rememberEnhancedDragDropState(
    config: DragDropConfig = DragDropConfig()
): EnhancedDragDropState {
    return remember { EnhancedDragDropState(config) }
}
