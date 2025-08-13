package com.musify.mu.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AlphabetScrollbar(
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    
    val alphabets = listOf(
        "#", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
        "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    )
    
    var selectedLetter by remember { mutableStateOf("") }
    var isDragging by remember { mutableStateOf(false) }
    var showBubble by remember { mutableStateOf(false) }
    var scrollbarHeight by remember { mutableStateOf(0f) }
    
    // Auto-hide bubble after selection
    LaunchedEffect(selectedLetter) {
        if (selectedLetter.isNotEmpty()) {
            showBubble = true
            delay(500)
            if (!isDragging) {
                showBubble = false
            }
        }
    }
    
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (showBubble) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "bubble_alpha"
    )
    
    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.CenterEnd
    ) {
        // Selection bubble
        if (selectedLetter.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .offset(x = (-60).dp)
                    .alpha(bubbleAlpha),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 8.dp
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = selectedLetter,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
        
        // Alphabet list
        Surface(
            modifier = Modifier
                .width(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .alpha(if (isDragging) 1f else 0.7f),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .onGloballyPositioned { coordinates ->
                        scrollbarHeight = coordinates.size.height.toFloat()
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                val index = calculateIndex(offset.y, scrollbarHeight, alphabets.size)
                                if (index in alphabets.indices) {
                                    val letter = alphabets[index]
                                    if (letter != selectedLetter) {
                                        selectedLetter = letter
                                        onLetterSelected(letter)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                            },
                            onVerticalDrag = { change, _ ->
                                val currentY = change.position.y
                                val index = calculateIndex(currentY, scrollbarHeight, alphabets.size)
                                if (index in alphabets.indices) {
                                    val letter = alphabets[index]
                                    if (letter != selectedLetter) {
                                        selectedLetter = letter
                                        onLetterSelected(letter)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                alphabets.forEach { letter ->
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = if (letter == selectedLetter) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (letter == selectedLetter) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun calculateIndex(y: Float, height: Float, size: Int): Int {
    val ratio = y / height
    return (ratio * size).toInt().coerceIn(0, size - 1)
}