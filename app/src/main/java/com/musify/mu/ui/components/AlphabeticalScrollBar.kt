package com.musify.mu.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Enhanced Alphabetical scroll bar with instant touch activation and hardware acceleration
 * Features:
 * - Instant touch response (no drag threshold)
 * - Hardware accelerated animations
 * - Haptic feedback
 * - Letter preview popup
 * - Fast scrolling performance
 */
@Composable
fun AlphabeticalScrollBar(
    letters: List<String>,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true
) {
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isActive by remember { mutableStateOf(false) }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var lastSelectedLetter by remember { mutableStateOf<String?>(null) }

    // Hardware accelerated animations for instant response
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "scroll_bar_alpha"
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessHigh // Instant response
        ),
        label = "scroll_bar_scale"
    )

    val elevation by animateDpAsState(
        targetValue = if (isActive) 12.dp else 6.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "scroll_bar_elevation"
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .fillMaxHeight()
            .width(32.dp)
    ) {
        // Main scroll bar with hardware acceleration
        Card(
            modifier = Modifier
                .fillMaxHeight()
                .width(28.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // Enable hardware acceleration for smooth rendering
                    compositingStrategy = CompositingStrategy.Offscreen
                    // Optimize for fast animations
                    renderEffect = null
                },
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) 
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                else 
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(letters) {
                        // Use awaitPointerEventScope for instant touch detection
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                
                                when (event.type) {
                                    PointerEventType.Press -> {
                                        // Instant activation on touch
                                        isActive = true
                                        val y = event.changes.first().position.y
                                        val index = (y / (size.height / letters.size)).roundToInt()
                                            .coerceIn(0, letters.size - 1)
                                        
                                        selectedIndex = index
                                        selectedLetter = letters.getOrNull(index)
                                        
                                        // Trigger immediately on press for maximum responsiveness
                                        if (selectedLetter != null) {
                                            lastSelectedLetter = selectedLetter
                                            onLetterSelected(selectedLetter!!)
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        
                                        // Consume the event to prevent any delays
                                        event.changes.forEach { it.consume() }
                                    }
                                    
                                    PointerEventType.Move -> {
                                        if (isActive) {
                                            val y = event.changes.first().position.y
                                            val index = (y / (size.height / letters.size)).roundToInt()
                                                .coerceIn(0, letters.size - 1)
                                            
                                            if (index != selectedIndex) {
                                                selectedIndex = index
                                                selectedLetter = letters.getOrNull(index)
                                                
                                                // Only trigger if letter actually changed
                                                if (selectedLetter != lastSelectedLetter && selectedLetter != null) {
                                                    lastSelectedLetter = selectedLetter
                                                    onLetterSelected(selectedLetter!!)
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                            
                                            // Consume the event for faster response
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                    
                                    PointerEventType.Release -> {
                                        isActive = false
                                        selectedLetter = null
                                        selectedIndex = -1
                                        lastSelectedLetter = null
                                    }
                                }
                            }
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                letters.forEachIndexed { index, letter ->
                    EnhancedLetterItem(
                        letter = letter,
                        isSelected = selectedIndex == index,
                        isActive = isActive
                    )
                }
            }
        }

        // Enhanced letter preview popup
        AnimatedVisibility(
            visible = isActive && selectedLetter != null,
            enter = scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessHigh
                ),
                initialScale = 0.7f
            ) + fadeIn(animationSpec = tween(100)),
            exit = scaleOut(
                animationSpec = tween(100),
                targetScale = 0.7f
            ) + fadeOut(animationSpec = tween(100)),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            EnhancedLetterPreview(
                letter = selectedLetter ?: "",
                modifier = Modifier.offset(x = (-70).dp)
            )
        }
    }
}

@Composable
private fun EnhancedLetterItem(
    letter: String,
    isSelected: Boolean,
    isActive: Boolean
) {
    // Hardware accelerated animations with ultra-fast response
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected && isActive -> MaterialTheme.colorScheme.primary
            isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        animationSpec = tween(50, easing = FastOutSlowInEasing), // Ultra-fast response
        label = "letter_background"
    )

    val textColor by animateColorAsState(
        targetValue = when {
            isSelected && isActive -> MaterialTheme.colorScheme.onPrimary
            isActive -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        },
        animationSpec = tween(50, easing = FastOutSlowInEasing), // Ultra-fast response
        label = "letter_text_color"
    )

    val scale by animateFloatAsState(
        targetValue = if (isSelected && isActive) 1.2f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 1500f // Very high stiffness for instant response (default High is 1000f)
        ),
        label = "letter_scale"
    )

    Box(
        modifier = Modifier
            .size(18.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // Hardware acceleration for smooth scaling
                compositingStrategy = if (isSelected && isActive) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
            }
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            fontSize = 11.sp,
            fontWeight = if (isSelected && isActive) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}

// Keep the old LetterItem for backward compatibility
@Composable
private fun LetterItem(
    letter: String,
    isSelected: Boolean,
    isDragging: Boolean
) {
    EnhancedLetterItem(
        letter = letter,
        isSelected = isSelected,
        isActive = isDragging
    )
}

@Composable
private fun EnhancedLetterPreview(
    letter: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .graphicsLayer {
                // Hardware acceleration for smooth popup animations
                compositingStrategy = CompositingStrategy.Offscreen
                // Optimize rendering
                renderEffect = null
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter.uppercase(),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Keep the old LetterPreview for backward compatibility
@Composable
private fun LetterPreview(
    letter: String,
    modifier: Modifier = Modifier
) {
    EnhancedLetterPreview(letter = letter, modifier = modifier)
}

/**
 * Generate alphabet letters for the scroll bar
 */
fun generateAlphabet(): List<String> {
    return ('A'..'Z').map { it.toString() } + listOf("#")
}

/**
 * Find the first letter of a track title for navigation
 */
fun getFirstLetter(title: String): String {
    val firstChar = title.firstOrNull()?.uppercaseChar()
    return when {
        firstChar == null -> "#"
        firstChar.isLetter() -> firstChar.toString()
        else -> "#"
    }
}