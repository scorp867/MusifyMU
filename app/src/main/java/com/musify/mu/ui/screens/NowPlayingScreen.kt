package com.musify.mu.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import com.musify.mu.data.db.entities.Track
import com.musify.mu.playback.LocalMediaController
import com.musify.mu.util.toTrack
import kotlinx.coroutines.launch
import com.musify.mu.data.repo.LibraryRepository

@Composable
fun NowPlayingScreen(navController: NavController) {
    val controller = LocalMediaController.current
    val context = LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    
    var currentTrack by remember { mutableStateOf<Track?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var shuffleOn by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(0L) }
    var isLiked by remember { mutableStateOf(false) }
    
    // Dynamic color extraction from album art
    var dominantColor by remember { mutableStateOf(Color(0xFF6236FF)) }
    var vibrantColor by remember { mutableStateOf(Color(0xFF38B6FF)) }
    
    // Animation states for enhanced visual experience
    val colorTransition = animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "colorTransition"
    )
    val vibrantTransition = animateColorAsState(
        targetValue = vibrantColor,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "vibrantTransition"
    )
    
    // Pulsing animation for when music is playing
    val pulseAnimation = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnimation.animateFloat(
        initialValue = 1f,
        targetValue = if (isPlaying) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    

    
    val coroutineScope = rememberCoroutineScope()

    // Extract colors from album artwork
    LaunchedEffect(currentTrack?.artUri) {
        currentTrack?.artUri?.let { artUri ->
            coroutineScope.launch {
                try {
                    val imageRequest = ImageRequest.Builder(context)
                        .data(artUri)
                        .allowHardware(false)
                        .build()
                    
                    val drawable = ImageLoader(context).execute(imageRequest).drawable
                    val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    
                    bitmap?.let {
                        val palette = Palette.from(it).generate()
                        dominantColor = Color(palette.getDominantColor(0xFF6236FF.toInt()))
                        vibrantColor = Color(palette.getVibrantColor(0xFF38B6FF.toInt()))
                    }
                } catch (e: Exception) {
                    // Use default colors if extraction fails
                }
            }
        }
    }

    // Listen for player state changes
    LaunchedEffect(controller) {
        controller?.let { mediaController ->
            // Initial state
            currentTrack = mediaController.currentMediaItem?.toTrack()
            isPlaying = mediaController.isPlaying
            shuffleOn = mediaController.shuffleModeEnabled
            repeatMode = when(mediaController.repeatMode) {
                Player.REPEAT_MODE_ONE -> 1
                Player.REPEAT_MODE_ALL -> 2
                else -> 0
            }
            progress = if (mediaController.duration > 0) {
                mediaController.currentPosition.toFloat() / mediaController.duration.toFloat()
            } else 0f
            duration = mediaController.duration
            // Load like state
            currentTrack?.let { t ->
                isLiked = repo.isLiked(t.mediaId)
            }
            
            // Add listener for real-time updates
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    currentTrack = mediaItem?.toTrack()
                    currentTrack?.let { t ->
                        // refresh like state on track change
                        coroutineScope.launch { isLiked = repo.isLiked(t.mediaId) }
                    }
                }
                
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    isPlaying = isPlayingNow
                }
                
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    shuffleOn = shuffleModeEnabled
                }
                
                override fun onRepeatModeChanged(repeatModeValue: Int) {
                    repeatMode = when(repeatModeValue) {
                        Player.REPEAT_MODE_ONE -> 1
                        Player.REPEAT_MODE_ALL -> 2
                        else -> 0
                    }
                }
                
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        duration = mediaController.duration
                    }
                }
            }
            
            mediaController.addListener(listener)
            
            // Update progress periodically
            while (true) {
                if (mediaController.isPlaying && mediaController.duration > 0) {
                    progress = mediaController.currentPosition.toFloat() / mediaController.duration.toFloat()
                }
                kotlinx.coroutines.delay(500) // Update every 500ms
            }
        }
    }

    // Dynamic background gradient based on album art colors with animation
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            colorTransition.value.copy(alpha = 0.4f),
            vibrantTransition.value.copy(alpha = 0.3f),
            colorTransition.value.copy(alpha = 0.5f),
            vibrantTransition.value.copy(alpha = 0.2f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top section with album art and track info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Top bar with back button and menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { navController.navigateUp() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            Color.White.copy(alpha = 0.2f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                IconButton(
                    onClick = { /* More options */ },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            Color.White.copy(alpha = 0.2f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
                        // Album artwork - large and prominent with animations
            currentTrack?.let { track ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                        }
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(20.dp),
                            spotColor = colorTransition.value.copy(alpha = 0.6f),
                            ambientColor = vibrantTransition.value.copy(alpha = 0.3f)
                        )
                        .clip(RoundedCornerShape(20.dp))
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.3f),
                                    colorTransition.value.copy(alpha = 0.2f),
                                    vibrantTransition.value.copy(alpha = 0.2f)
                                )
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                ) {
                    com.musify.mu.ui.components.Artwork(
                        data = track.artUri,
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Subtle overlay for depth
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.1f)
                                    ),
                                    radius = 300f
                                )
                            )
                    )
                }
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.2f),
                                Color.White.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(80.dp)
                        .align(Alignment.Center)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Song info
            currentTrack?.let { track ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            }
        }
        
        // Fixed bottom section with controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Progress bar with glassmorphism
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // Seekable progress bar
                Slider(
                    value = progress,
                    onValueChange = { newProgress ->
                        progress = newProgress
                    },
                    onValueChangeFinished = {
                        // Seek to the new position
                        controller?.let { mediaController ->
                            if (mediaController.duration > 0) {
                                val seekPosition = (progress * mediaController.duration).toLong()
                                mediaController.seekTo(seekPosition)
                            }
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Time indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration((progress * duration).toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Glassmorphism control panel - FIXED POSITION
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.2f),
                                Color.White.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.6f),
                                Color.White.copy(alpha = 0.2f)
                            )
                        ),
                        shape = RoundedCornerShape(25.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle
                    IconButton(
                        onClick = {
                            controller?.let { c ->
                                val now = !shuffleOn
                                c.shuffleModeEnabled = now
                                shuffleOn = now
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (shuffleOn) vibrantTransition.value else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    // Previous
                    IconButton(
                        onClick = { controller?.seekToPrevious() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    // Play/Pause - Main button
                                            Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White,
                                            Color.White.copy(alpha = 0.9f)
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .border(
                                    width = 2.dp,
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            colorTransition.value.copy(alpha = 0.3f),
                                            vibrantTransition.value.copy(alpha = 0.2f)
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .shadow(
                                    elevation = 8.dp,
                                    shape = CircleShape,
                                    spotColor = colorTransition.value.copy(alpha = 0.4f)
                                )
                                .clickable {
                                    controller?.let {
                                        if (it.isPlaying) it.pause() else it.play()
                                        // rely on listener to update isPlaying
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) 
                                    Icons.Rounded.Pause 
                                else 
                                    Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = colorTransition.value,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    
                    // Next
                    IconButton(
                        onClick = { controller?.seekToNext() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    // Repeat
                    IconButton(
                        onClick = {
                repeatMode = (repeatMode + 1) % 3
                controller?.repeatMode = when (repeatMode) {
                    1 -> Player.REPEAT_MODE_ONE
                    2 -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = when (repeatMode) {
                                1 -> Icons.Rounded.RepeatOne
                                else -> Icons.Rounded.Repeat
                            },
                            contentDescription = "Repeat",
                            tint = if (repeatMode != 0) vibrantTransition.value else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Bottom action buttons - pill shaped with requested layout
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                // Pill-shaped container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.15f),
                                    Color.White.copy(alpha = 0.08f)
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.3f),
                                    Color.White.copy(alpha = 0.1f)
                                )
                            ),
                            shape = RoundedCornerShape(30.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Share button (left)
                        IconButton(
                            onClick = { /* Share song */ },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        
                        // Queue button (center)
                        IconButton(
                            onClick = { /* Queue */ },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QueueMusic,
                                contentDescription = "Queue",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        
                        // Like button (right)
                        IconButton(
                            onClick = {
                                val t = currentTrack ?: return@IconButton
                                coroutineScope.launch {
                                    if (isLiked) repo.unlike(t.mediaId) else repo.like(t.mediaId)
                                    isLiked = !isLiked
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (isLiked) "Unlike" else "Like",
                                tint = if (isLiked) vibrantTransition.value else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper function to format duration in mm:ss format
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
