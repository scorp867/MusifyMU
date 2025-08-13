package com.musify.mu.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.ui.theme.ElectricBlue
import com.musify.mu.ui.theme.NeonPink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NowPlayingBar(
    navController: NavController,
    currentTrack: Track?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (currentTrack == null) return

    // Animation for pulsing play button
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPlaying) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse scale"
    )

    // Animation for text scrolling if long text
    val coroutineScope = rememberCoroutineScope()
    val textScrollOffset = remember { Animatable(0f) }
    
    // Create a dynamic gradient based on the theme colors
    val gradient = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        )
    )

    var dragX by remember { mutableStateOf(0f) }
    
    // Track drag animation
    val dragProgress by animateFloatAsState(
        targetValue = dragX.coerceIn(-100f, 100f) / 100f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "dragProgress"
    )
    
    // Swipe hint animation
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            coroutineScope.launch {
                while (true) {
                    delay(5000) // Wait before showing hint animation
                    textScrollOffset.animateTo(
                        targetValue = -20f,
                        animationSpec = tween(800, easing = LinearEasing)
                    )
                    delay(400)
                    textScrollOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(800, easing = LinearEasing)
                    )
                }
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(
                elevation = 8.dp,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            )
            .clip(RoundedCornerShape(16.dp))
            .clickable { onExpand() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        dragX += dragAmount
                    },
                    onDragEnd = {
                        if (dragX.compareTo(100f) > 0) onPrev() else if (dragX.compareTo(-100f) < 0) onNext()
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f }
                )
            },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(gradient)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = dragProgress * 50f  // Moves slightly with drag
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album artwork with rounded corners
                Artwork(
                    data = currentTrack.artUri,
                    contentDescription = currentTrack.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                
                Spacer(Modifier.width(12.dp))
                
                // Track info with marquee effect for long titles
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { translationX = textScrollOffset.value }
                ) {
                    Text(
                        text = currentTrack.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = currentTrack.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                
                Spacer(Modifier.width(8.dp))
                
                // Media control buttons with animations
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Play/Pause button with scale animation
                    IconButton(
                        onClick = { onPlayPause() },
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    // Next button
                    IconButton(
                        onClick = { onNext() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            // Swipe indicators (shown only when dragging) with simple alpha animation
            val leftAlpha by animateFloatAsState(
                targetValue = if (dragX > 50f) 1f else 0f,
                animationSpec = tween(200),
                label = "leftAlpha"
            )
            val rightAlpha by animateFloatAsState(
                targetValue = if (dragX < -50f) 1f else 0f,
                animationSpec = tween(200),
                label = "rightAlpha"
            )
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(28.dp)
                    .graphicsLayer { alpha = leftAlpha }
            )

            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(28.dp)
                    .graphicsLayer { alpha = rightAlpha }
            )
        }
    }
}
