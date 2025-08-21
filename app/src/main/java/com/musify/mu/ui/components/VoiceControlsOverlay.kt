package com.musify.mu.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.musify.mu.voice.CommandController
import com.musify.mu.playback.LocalMediaController
import com.musify.mu.util.PermissionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.media3.common.Player

@Composable
fun VoiceControlsOverlay(
    isGymMode: Boolean,
    onToggleGymMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val controller = LocalMediaController.current
    val scope = rememberCoroutineScope()
    
    var isListening by remember { mutableStateOf(false) }
    var lastCommand by remember { mutableStateOf<String?>(null) }
    var commandFeedback by remember { mutableStateOf<String?>(null) }
    val hasMicPermission = remember { PermissionManager.checkMicPermission(context) }
    
    val commandController = remember { CommandController(context) }
    
    // Voice command handling
    LaunchedEffect(isListening) {
        if (isListening) {
            scope.launch {
                try {
                    commandController.listen().collectLatest { spokenText ->
                        lastCommand = spokenText
                        val command = commandController.interpretCommand(spokenText)
                        
                        command?.let { cmd ->
                            controller?.let { mediaController ->
                                when (cmd) {
                                    CommandController.Command.PLAY -> {
                                        mediaController.play()
                                        commandFeedback = "Playing"
                                    }
                                    CommandController.Command.PAUSE -> {
                                        mediaController.pause()
                                        commandFeedback = "Paused"
                                    }
                                    CommandController.Command.NEXT -> {
                                        mediaController.seekToNext()
                                        commandFeedback = "Next track"
                                    }
                                    CommandController.Command.PREV -> {
                                        mediaController.seekToPrevious()
                                        commandFeedback = "Previous track"
                                    }
                                    CommandController.Command.SHUFFLE_ON -> {
                                        mediaController.shuffleModeEnabled = true
                                        commandFeedback = "Shuffle on"
                                    }
                                    CommandController.Command.SHUFFLE_OFF -> {
                                        mediaController.shuffleModeEnabled = false
                                        commandFeedback = "Shuffle off"
                                    }
                                    CommandController.Command.REPEAT_ONE -> {
                                        mediaController.repeatMode = Player.REPEAT_MODE_ONE
                                        commandFeedback = "Repeat one"
                                    }
                                    CommandController.Command.REPEAT_ALL -> {
                                        mediaController.repeatMode = Player.REPEAT_MODE_ALL
                                        commandFeedback = "Repeat all"
                                    }
                                    CommandController.Command.REPEAT_OFF -> {
                                        mediaController.repeatMode = Player.REPEAT_MODE_OFF
                                        commandFeedback = "Repeat off"
                                    }
                                }
                            }
                        } ?: run {
                            commandFeedback = "Command not recognized"
                        }
                        
                        isListening = false
                        
                        // Clear feedback after delay
                        kotlinx.coroutines.delay(2000)
                        commandFeedback = null
                    }
                } catch (e: Exception) {
                    commandFeedback = "Voice recognition failed"
                    isListening = false
                    kotlinx.coroutines.delay(2000)
                    commandFeedback = null
                }
            }
        }
    }
    
    // Gym mode overlay
    AnimatedVisibility(
        visible = isGymMode,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(300)
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = tween(300)
        ) + fadeOut(),
        modifier = modifier.zIndex(10f)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.FitnessCenter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Gym Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    IconButton(onClick = onToggleGymMode) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Exit Gym Mode",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Voice control button
                val pulseAnimation = rememberInfiniteTransition(label = "pulse")
                val pulseScale by pulseAnimation.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isListening) 1.1f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )
                
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                        }
                        .background(
                            brush = if (isListening) {
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color.Red,
                                        Color.Red.copy(alpha = 0.8f)
                                    )
                                )
                            } else {
                                Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    )
                                )
                            },
                            shape = CircleShape
                        )
                        .clip(CircleShape)
                        .clickable {
                            if (!hasMicPermission) {
                                commandFeedback = "Microphone permission required"
                                scope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    commandFeedback = null
                                }
                            } else if (!isListening) {
                                isListening = true
                                commandFeedback = null
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isListening) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = if (isListening) "Stop Listening" else "Start Voice Command",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                
                Spacer(Modifier.height(12.dp))
                
                // Status text
                Text(
                    text = when {
                        isListening -> "Listening..."
                        commandFeedback != null -> commandFeedback!!
                        else -> "Tap to give voice command"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isListening) Color.Red else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (commandFeedback != null) FontWeight.Bold else FontWeight.Normal
                )
                
                // Last command display
                lastCommand?.let { command ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "\"$command\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Quick commands help
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            "Voice Commands:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        
                        val commands = listOf(
                            "Play • Pause • Next • Previous",
                            "Shuffle on/off • Repeat one/all/off"
                        )
                        
                        commands.forEach { commandText ->
                            Text(
                                commandText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}