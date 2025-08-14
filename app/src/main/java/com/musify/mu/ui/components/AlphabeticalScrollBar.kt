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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Alphabetical scroll bar for quick navigation
 * Features:
 * - Touch and drag to navigate
 * - Haptic feedback
 * - Smooth animations
 * - Letter preview popup
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

    var isDragging by remember { mutableStateOf(false) }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var selectedIndex by remember { mutableStateOf(-1) }

    // Animation for visibility
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 0.8f else 0f,
        animationSpec = tween(300),
        label = "scroll_bar_alpha"
    )

    // Animation for drag state
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scroll_bar_scale"
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .fillMaxHeight()
            .width(28.dp)
    ) {
        // Main scroll bar
        Card(
            modifier = Modifier
                .fillMaxHeight()
                .width(24.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(letters) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                val index = (offset.y / (size.height / letters.size)).roundToInt()
                                    .coerceIn(0, letters.size - 1)
                                selectedIndex = index
                                selectedLetter = letters.getOrNull(index)
                                selectedLetter?.let {
                                    onLetterSelected(it)
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                                selectedLetter = null
                                selectedIndex = -1
                            }
                        ) { _, dragAmount ->
                            // Calculate current position
                            val currentY = selectedIndex * (size.height / letters.size) + dragAmount.y
                            val newIndex = (currentY / (size.height / letters.size)).roundToInt()
                                .coerceIn(0, letters.size - 1)

                            if (newIndex != selectedIndex) {
                                selectedIndex = newIndex
                                selectedLetter = letters.getOrNull(newIndex)
                                selectedLetter?.let {
                                    onLetterSelected(it)
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                letters.forEachIndexed { index, letter ->
                    LetterItem(
                        letter = letter,
                        isSelected = selectedIndex == index,
                        isDragging = isDragging
                    )
                }
            }
        }

        // Letter preview popup
        AnimatedVisibility(
            visible = isDragging && selectedLetter != null,
            enter = scaleIn(
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                initialScale = 0.8f
            ) + fadeIn(),
            exit = scaleOut(
                animationSpec = tween(150),
                targetScale = 0.8f
            ) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            LetterPreview(
                letter = selectedLetter ?: "",
                modifier = Modifier.offset(x = (-60).dp)
            )
        }
    }
}

@Composable
private fun LetterItem(
    letter: String,
    isSelected: Boolean,
    isDragging: Boolean
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected && isDragging -> MaterialTheme.colorScheme.primary
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "letter_background"
    )

    val textColor by animateColorAsState(
        targetValue = when {
            isSelected && isDragging -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(150),
        label = "letter_text_color"
    )

    Box(
        modifier = Modifier
            .size(16.dp)
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LetterPreview(
    letter: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter.uppercase(),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center
            )
        }
    }
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