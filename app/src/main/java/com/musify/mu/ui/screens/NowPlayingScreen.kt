package com.musify.mu.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.ui.components.AnimatedBackground
import com.musify.mu.ui.components.EnhancedLyricsView
import com.musify.mu.ui.navigation.Screen

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
    var userSeeking by remember { mutableStateOf(false) }
    
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

    // Extract colors from pre-cached album artwork on track change
    LaunchedEffect(currentTrack?.mediaId) {
        currentTrack?.let { track ->
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    android.util.Log.d("NowPlayingScreen", "Extracting colors for track: ${track.title}")
                    
                    // Use pre-extracted artwork URI from Track entity
                    val artworkUri = track.artUri
                    
                    val sourceBitmap = if (!artworkUri.isNullOrBlank()) {
                        try {
                            // Convert file URI to file path and load bitmap
                            val artworkPath = if (artworkUri.startsWith("file://")) {
                                artworkUri.substring(7) // Remove "file://" prefix
                            } else {
                                artworkUri
                            }
                            android.graphics.BitmapFactory.decodeFile(artworkPath)?.also {
                                android.util.Log.d("NowPlayingScreen", "Loaded pre-cached artwork for color extraction: $artworkPath")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("NowPlayingScreen", "Failed to load pre-cached artwork file: $artworkUri", e)
                            null
                        }
                    } else {
                        android.util.Log.d("NowPlayingScreen", "No pre-cached artwork available for ${track.title}")
                        null
                    }
                    
                    // Extract palette colors on background thread
                    val extractedColors = sourceBitmap?.let { bitmap ->
                        val palette = androidx.palette.graphics.Palette.from(bitmap)
                            .maximumColorCount(16)
                            .generate()
                        
                        val vibrant = palette.getVibrantColor(0xFF38B6FF.toInt())
                        val dominant = palette.getDominantColor(0xFF6236FF.toInt())
                        val darkVibrant = palette.getDarkVibrantColor(dominant)
                        
                        android.util.Log.d("NowPlayingScreen", "Extracted colors - Dominant: ${Integer.toHexString(dominant)}, Vibrant: ${Integer.toHexString(vibrant)}")
                        Pair(Color(dominant), Color(vibrant))
                    } ?: run {
                        android.util.Log.d("NowPlayingScreen", "Using default colors for ${track.title}")
                        Pair(Color(0xFF6236FF), Color(0xFF38B6FF))
                    }
                    
                    // Update colors on main thread
                    withContext(Dispatchers.Main) {
                        dominantColor = extractedColors.first
                        vibrantColor = extractedColors.second
                        android.util.Log.d("NowPlayingScreen", "Applied new colors for ${track.title}")
                    }
                    
                } catch (e: Exception) {
                    android.util.Log.w("NowPlayingScreen", "Failed to extract colors for ${track.title}", e)
                    withContext(Dispatchers.Main) {
                        dominantColor = Color(0xFF6236FF)
                        vibrantColor = Color(0xFF38B6FF)
                    }
                }
            }
        } ?: run {
            // No track, use default colors
            android.util.Log.d("NowPlayingScreen", "No current track, using default colors")
            dominantColor = Color(0xFF6236FF)
            vibrantColor = Color(0xFF38B6FF)
        }
    }

    // Listen for player state changes
    LaunchedEffect(controller) {
        controller?.let { mediaController ->
            // Initial state
            currentTrack = mediaController.currentMediaItem?.let { item ->
                repo.getTrackByMediaId(item.mediaId) ?: item.toTrack()
            }
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
                    currentTrack = mediaItem?.let { item ->
                        repo.getTrackByMediaId(item.mediaId) ?: item.toTrack()
                    }
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
            
            // Continuous progress updates - pause updates while user is seeking
            launch {
                while (true) {
                    try {
                        val currentPos = mediaController.currentPosition
                        val dur = mediaController.duration
                        if (dur > 0) {
                            duration = dur
                            if (!userSeeking) {
                                progress = (currentPos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                        // Update every 150ms to reduce main-thread churn without visible regression
                        delay(150)
                    } catch (e: Exception) {
                        delay(500)
                    }
                }
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
        // Animated background
        AnimatedBackground(
            isPlaying = isPlaying,
            primaryColor = colorTransition.value,
            secondaryColor = vibrantTransition.value,
            modifier = Modifier.fillMaxSize()
        )
        
        // Scrollable content for the entire screen
        val scrollState = rememberScrollState()
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top section with album art and track info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
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
                            audioUri = track.mediaId,
                            albumId = track.albumId,
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
                
                // Song info (fixed height to avoid control panel shifting)
                currentTrack?.let { track ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
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

            // Add spacing so the control panel sits lower and doesn't shift with title height
            Spacer(modifier = Modifier.height(44.dp))

            // Player controls section - merged glass panel with thin seek bar inside
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Unified glass panel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(206.dp)
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Thin seek bar inside panel
                        Slider(
                            value = progress,
                            onValueChange = { newProgress ->
                                userSeeking = true
                                progress = newProgress
                            },
                            onValueChangeFinished = {
                                controller?.let { c ->
                                    if (c.duration > 0) c.seekTo((progress * c.duration).toLong())
                                }
                                userSeeking = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White.copy(alpha = 0.9f),
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                        )

                        // Time row
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

                        Spacer(Modifier.height(8.dp))

                        // Controls row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
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

                        Spacer(Modifier.height(12.dp))

                        // Actions directly inside the glass panel (no pill)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { /* Share song */ }) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = "Share",
                                    tint = Color.White.copy(alpha = 0.85f)
                                )
                            }
                            IconButton(onClick = { navController.navigate(Screen.Queue.route) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = "Queue",
                                    tint = Color.White.copy(alpha = 0.85f)
                                )
                            }
                            IconButton(onClick = {
                                val t = currentTrack ?: return@IconButton
                                coroutineScope.launch {
                                    if (isLiked) repo.unlike(t.mediaId) else repo.like(t.mediaId)
                                    isLiked = !isLiked
                                }
                            }) {
                                Icon(
                                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = if (isLiked) "Unlike" else "Like",
                                    tint = if (isLiked) vibrantTransition.value else Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }
            
            // Add lyrics section
            Spacer(modifier = Modifier.height(32.dp))
            
            // Simple divider
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                thickness = 2.dp,
                color = Color.White.copy(alpha = 0.3f)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Lyrics section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                currentTrack?.let { track ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Lyrics",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        // Progress ticker to drive lyrics auto-scroll
                        var progressMs by remember { mutableStateOf(0L) }
                        LaunchedEffect(controller, isPlaying, currentTrack?.mediaId) {
                            while (true) {
                                progressMs = controller?.currentPosition ?: progressMs
                                delay(300)
                            }
                        }

                        // Show lyrics view - lyrics will be loaded in PlayerService when track changes
                        EnhancedLyricsView(
                            mediaId = track.mediaId,
                            currentPositionMs = progressMs,
                            dominantColor = dominantColor,
                            vibrantColor = vibrantColor
                        )
                    }
                }
            }
            
            // Add space at bottom to ensure scrolling works properly
            Spacer(modifier = Modifier.height(100.dp))
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