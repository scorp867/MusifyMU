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
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import com.musify.mu.data.db.entities.Track
import com.musify.mu.playback.LocalMediaController
import com.musify.mu.util.toMediaItem
import com.musify.mu.util.toTrack
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.ui.components.AnimatedBackground
import com.musify.mu.ui.components.EnhancedLyricsView
import androidx.hilt.navigation.compose.hiltViewModel
import com.musify.mu.ui.viewmodels.LibraryViewModel
import com.musify.mu.ui.components.LargeArtwork
import com.musify.mu.ui.components.CompactArtwork
import com.musify.mu.ui.navigation.Screen
import com.musify.mu.ui.helpers.resolveTrack
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import com.musify.mu.playback.rememberQueueState
import com.musify.mu.playback.rememberQueueOperations
import com.musify.mu.playback.rememberCurrentItem
import com.musify.mu.playback.QueueContextHelper
import com.musify.mu.playback.QueueManager
import com.musify.mu.voice.VoiceControlManager
import com.musify.mu.ui.components.MoreOptionsMenu
import com.musify.mu.ui.components.GymModeIndicator
import com.musify.mu.util.PermissionHelper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.distinctUntilChanged
import android.net.Uri
import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import android.os.Environment
import androidx.compose.foundation.gestures.scrollBy
import androidx.core.content.FileProvider
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalMaterialApi::class
)
@Composable
fun NowPlayingScreen(navController: NavController) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val controller = LocalMediaController.current
    val context = LocalContext.current

    // Get or create VoiceControlManager singleton
    val voiceControlManager = remember {
        controller?.let { mediaController ->
            VoiceControlManager.createInstance(context, mediaController)
        }
    }

    // Runtime mic permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voiceControlManager?.toggleGymMode()
        } else {
            android.widget.Toast.makeText(context, "Microphone permission required", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    

    // Update controller reference when it changes
    LaunchedEffect(controller) {
        if (controller != null && voiceControlManager != null) {
            voiceControlManager.updatePlayer(controller)
        }
    }

    // Voice control state - sync with VoiceControlManager state
    var isGymModeEnabled by remember {
        mutableStateOf(voiceControlManager?.isGymModeEnabled() ?: false)
    }
    var canEnableGymMode by remember {
        mutableStateOf(voiceControlManager?.canEnableGymMode() ?: false)
    }

    // Sync state when screen resumes or voiceControlManager changes
    LaunchedEffect(voiceControlManager) {
        voiceControlManager?.let { vcm ->
            // Initial state sync
            val initialGymMode = vcm.isGymModeEnabled()
            val initialCanEnable = vcm.canEnableGymMode()

            isGymModeEnabled = initialGymMode
            canEnableGymMode = initialCanEnable

            android.util.Log.d("NowPlayingScreen", "Initial state sync - Gym mode: $initialGymMode, Can enable: $initialCanEnable")

            // Set callback to update UI state immediately when VoiceControlManager changes
            vcm.onGymModeChanged = { enabled ->
                android.util.Log.d("NowPlayingScreen", "VoiceControlManager callback - Gym mode changed to: $enabled")
                // Update UI state on Main thread using Handler
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isGymModeEnabled = enabled
                    android.util.Log.d("NowPlayingScreen", "UI state updated - isGymModeEnabled: $enabled")
                }
            }
        }
    }

    // Observe headphone connection and gym mode changes
    LaunchedEffect(voiceControlManager) {
        voiceControlManager?.let { vcm ->
            // Observe headphone connection with microphone check
            vcm.observeHeadphoneConnection().collect { isConnected ->
                val hasMicrophone = vcm.getHeadphoneDetector().hasHeadsetMicrophone()
                val newCanEnable = isConnected && hasMicrophone

                // Update on Main thread to ensure UI consistency
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (canEnableGymMode != newCanEnable) {
                        canEnableGymMode = newCanEnable
                        android.util.Log.d("NowPlayingScreen", "canEnableGymMode updated to: $newCanEnable")
                    }

                    if (!isConnected && isGymModeEnabled) {
                        isGymModeEnabled = false
                        android.util.Log.d("NowPlayingScreen", "Gym mode disabled due to disconnect")
                    }
                }
            }
        }
    }

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

    // Extract colors from album art on track change using PaletteUtil
    // Use both mediaId and artUri as keys to trigger when artwork changes
    LaunchedEffect(currentTrack?.mediaId, currentTrack?.artUri) {
        currentTrack?.let { track ->
            coroutineScope.launch(Dispatchers.IO) {
                try { android.util.Log.d("NowPlayingScreen", "Extracting colors for track: ${track.title}")

                    val palette = com.musify.mu.util.extractPalette(
                        context.contentResolver,
                        track.artUri
                    )

                    val dominant = palette?.getDominantColor(0xFF6236FF.toInt()) ?: 0xFF6236FF.toInt()
                    val vibrant = palette?.getVibrantColor(0xFF38B6FF.toInt()) ?: 0xFF38B6FF.toInt()

                    withContext(Dispatchers.Main) {
                        dominantColor = Color(dominant)
                        vibrantColor = Color(vibrant)
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

    // Gallery picker launcher (needs currentTrack and coroutineScope in scope)
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { imageUri ->
            currentTrack?.let { track ->
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        // Store the custom artwork using the new Spotify-style approach
                        val customArtworkUri = com.musify.mu.util.SpotifyStyleArtworkLoader.storeCustomArtwork(track.mediaId, imageUri)
                        if (customArtworkUri != null) {
                            // Artwork is already cached by SpotifyStyleArtworkLoader
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Custom artwork set successfully", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Failed to set custom artwork", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("NowPlayingScreen", "Error setting custom artwork", e)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Error setting custom artwork", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // Storage permission launcher for custom artwork (references galleryLauncher)
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            galleryLauncher.launch("image/*")
        } else {
            android.widget.Toast.makeText(context, "Storage permission required to select images", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Listen for player state changes with proper lifecycle management
    DisposableEffect(controller) {
        if (controller == null) {
            return@DisposableEffect onDispose { }
        }

        // Initial state setup
        coroutineScope.launch {
            currentTrack = resolveTrack(controller.currentMediaItem, com.musify.mu.data.repo.LibraryRepository.get(context))
            isPlaying = controller.isPlaying
            shuffleOn = controller.shuffleModeEnabled
            repeatMode = when(controller.repeatMode) {
                Player.REPEAT_MODE_ONE -> 1
                Player.REPEAT_MODE_ALL -> 2
                else -> 0
            }
            progress = if (controller.duration > 0) {
                controller.currentPosition.toFloat() / controller.duration.toFloat()
            } else 0f
            duration = controller.duration

            // Load like state with IO dispatcher
            currentTrack?.let { t ->
                withContext(Dispatchers.IO) {
                    val liked = viewModel.isLiked(t.mediaId)
                    withContext(Dispatchers.Main) {
                        isLiked = liked
                    }
                }
            }
        }

        // Add listener for real-time updates
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                currentTrack = resolveTrack(mediaItem, com.musify.mu.data.repo.LibraryRepository.get(context))
                currentTrack?.let { t ->
                    // refresh like state on track change
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            val liked = viewModel.isLiked(t.mediaId)
                            withContext(Dispatchers.Main) {
                                isLiked = liked
                            }
                        }
                    }
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
                    duration = controller.duration
                }
            }
        }

        controller.addListener(listener)

        // Progress updater in separate coroutine
        val progressJob = coroutineScope.launch {
            while (true) {
                try {
                    val currentPos = controller.currentPosition
                    val dur = controller.duration
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

        onDispose {
            controller.removeListener(listener)
            progressJob.cancel()
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

    var showQueue by remember { mutableStateOf(false) }
    LaunchedEffect(showQueue) {
        if (showQueue) android.util.Log.d("QueueScreenDBG", "Queue modal opened")
        else android.util.Log.d("QueueScreenDBG", "Queue modal closed")
    }

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

                    MoreOptionsMenu(
                        isGymModeEnabled = isGymModeEnabled,
                        canEnableGymMode = canEnableGymMode,
                        onGymModeToggle = {
                            val activity = context as? android.app.Activity
                            if (activity != null && !PermissionHelper.hasMicPermission(activity)) {
                                micPermissionLauncher.launch(PermissionHelper.MIC_PERMISSION)
                            } else {
                                isGymModeEnabled = !isGymModeEnabled
                                voiceControlManager?.toggleGymMode()
                            }
                        },
                        onCustomArtworkClick = {
                            // Request storage permission if needed
                            val activity = context as? android.app.Activity
                            if (activity != null && !PermissionHelper.hasStoragePermission(activity)) {
                                storagePermissionLauncher.launch(PermissionHelper.STORAGE_PERMISSION)
                            } else {
                                galleryLauncher.launch("image/*")
                            }
                        }
                    )
                }

                // Gym Mode Indicator
                GymModeIndicator(
                    isActive = isGymModeEnabled,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

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
                        // Smooth crossfade on track artwork change
                        androidx.compose.animation.Crossfade(
                            targetState = track.mediaId,
                            animationSpec = tween(400, easing = FastOutSlowInEasing),
                            label = "artCrossfade"
                        ) { _ ->
                            LargeArtwork(
                                trackUri = track.mediaId,
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(16.dp)
                            )
                        }

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
                            IconButton(onClick = {
                                currentTrack?.let { track ->
                                    // Show loading indicator
                                    val progressDialog = android.app.ProgressDialog(context).apply {
                                        setMessage("Preparing song for sharing...")
                                        setCancelable(false)
                                        show()
                                    }

                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            val shareResult = shareSongWithMetadata(track, context)

                                            withContext(Dispatchers.Main) {
                                                progressDialog.dismiss()

                                                when (shareResult) {
                                                    is ShareSongResult.Success -> {
                                                        val shareIntent = android.content.Intent().apply {
                                                            action = android.content.Intent.ACTION_SEND
                                                            type = shareResult.mimeType
                                                            putExtra(android.content.Intent.EXTRA_STREAM, shareResult.uri)
                                                            putExtra(android.content.Intent.EXTRA_TEXT,
                                                                "🎵 ${track.title} by ${track.artist}\nAlbum: ${track.album}\nShared from Musify MU")
                                                            putExtra(android.content.Intent.EXTRA_SUBJECT, "${track.title} - ${track.artist}")
                                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }

                                                        val chooserIntent = android.content.Intent.createChooser(
                                                            shareIntent,
                                                            "Share song file via"
                                                        )

                                                        try {
                                                            context.startActivity(chooserIntent)
                                                        } catch (e: Exception) {
                                                            android.util.Log.e("NowPlayingScreen", "Error starting share chooser", e)
                                                            android.widget.Toast.makeText(
                                                                context,
                                                                "Unable to open share options",
                                                                android.widget.Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                    is ShareSongResult.Error -> {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "Failed to share: ${shareResult.message}",
                                                            android.widget.Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("NowPlayingScreen", "Error sharing song", e)
                                            withContext(Dispatchers.Main) {
                                                progressDialog.dismiss()
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Error preparing song for sharing",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                } ?: run {
                                    android.widget.Toast.makeText(
                                        context,
                                        "No song to share",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = "Share",
                                    tint = Color.White.copy(alpha = 0.85f)
                                )
                            }
                            IconButton(onClick = { showQueue = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = "Queue",
                                    tint = Color.White.copy(alpha = 0.85f)
                                )
                            }
                            IconButton(onClick = {
                                val t = currentTrack ?: return@IconButton
                                coroutineScope.launch {
                                    if (isLiked) viewModel.unlike(t.mediaId) else viewModel.like(t.mediaId)
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


        if (showQueue) {
            val queueOps = com.musify.mu.playback.rememberQueueOperations()
            ModalBottomSheet(
                onDismissRequest = { showQueue = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                // Sticky header with now playing and play/pause, plus a single context header above it
                val currentQueueItem = controller?.currentMediaItem
                val t = currentQueueItem?.let { viewModel.getTrackByMediaId(it.mediaId) ?: it.toTrack() }
                val qState by rememberQueueState()
                val queueChanges by com.musify.mu.playback.rememberQueueChanges()
                val queueItems = remember(qState.currentIndex, qState.playNextCount, qState.totalItems, controller?.currentMediaItem?.mediaId) {
                    queueOps.getVisibleQueue()
                }

                // Enhanced reorderable state with improved drag logic
                var visualQueueItems by remember { mutableStateOf(queueItems) }
                var isDragging by remember { mutableStateOf(false) }
                var draggingUid by remember { mutableStateOf<String?>(null) }
                var dragFromVisibleIndex by remember { mutableStateOf<Int?>(null) }
                var autoscrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                var lastVisualUpdateTime by remember { mutableStateOf(0L) }
                var latestAbsIndex by remember { mutableStateOf(0) }

                // Sync visual queue with real queue data
                LaunchedEffect(queueItems) {
                    if (!isDragging) {
                        visualQueueItems = queueItems.toList()
                    }
                }

                // Calculate header offset based on actual LazyList structure
                // Context header (always present) + Sticky header (if current track exists)
                val headerOffset = remember(t, qState.currentIndex) { 
                    val contextHeaderCount = 1 // "Playing from {source}" header
                    val stickyHeaderCount = if (t != null) 1 else 0 // Now playing sticky header
                    contextHeaderCount + stickyHeaderCount
                }

                // React to queue changes to update visuals in real-time
                LaunchedEffect(queueChanges) {
                    queueChanges?.let {
                        if (!isDragging) {
                            val updated = queueOps.getVisibleQueue()
                            visualQueueItems = updated
                        }
                    }
                }

                // Use an explicit LazyListState so we can control autoscroll without self-reference issues
                val queueListState = rememberLazyListState()

                val reorderState = rememberReorderableLazyListState(
                    listState = queueListState,
                    onMove = { from, to ->
                        val contentStart = headerOffset
                        val contentEndExclusive = headerOffset + visualQueueItems.size

                        val fromAbs = from.index
                        val toAbs = to.index
                        latestAbsIndex = toAbs // Update shared state for autoscroll

                        val fromVis = fromAbs - contentStart
                        // if drag started from header/outside visible content, ignore the move
                        if (fromVis !in visualQueueItems.indices) {
                            isDragging = true
                            return@rememberReorderableLazyListState
                        }

                        // capture initial drag info once
                        if (dragFromVisibleIndex == null) {
                            dragFromVisibleIndex = fromVis
                            draggingUid = visualQueueItems.getOrNull(fromVis)?.uid
                        }

                        // autoscroll hot zones based on actual visible content, not header position
                        // Get the first visible content item position from the LazyList state
                        val firstVisibleContentIndex = queueListState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index >= contentStart }?.index ?: contentStart
                        
                        val topHotZoneAbs = firstVisibleContentIndex + 1 // Start autoscroll when item is 1 position above first visible content
                        val bottomHotZoneAbs = contentEndExclusive - 2 // Start autoscroll when item is 2 positions from bottom

                        // Debug logging
                        android.util.Log.d("DragDebug", "toAbs=$toAbs, contentStart=$contentStart, firstVisibleContentIndex=$firstVisibleContentIndex, topHotZoneAbs=$topHotZoneAbs, bottomHotZoneAbs=$bottomHotZoneAbs")

                        // Cancel any existing autoscroll job
                        autoscrollJob?.cancel()

                        // Enable autoscroll when dragging above the first visible content item
                        if (toAbs <= topHotZoneAbs) {
                            android.util.Log.d("DragDebug", "Triggering UPWARD autoscroll (above first visible content item)")
                            autoscrollJob = coroutineScope.launch {
                                while (isDragging) {
                                    try { 
                                        // Re-read current position from shared state
                                        val currentAbs = latestAbsIndex
                                        if (currentAbs > topHotZoneAbs) break // Exit if no longer in hot zone
                                        
                                        queueListState.scrollBy(-32f) // Smaller increments for smoother movement
                                        kotlinx.coroutines.delay(20) // Higher frequency for smoother perception
                                    } catch (_: Throwable) { break }
                                }
                            }
                        } else if (toAbs >= bottomHotZoneAbs && toAbs < contentEndExclusive) {
                            // Only trigger downward autoscroll if we're actually dragging downward
                            // Check if the current position is below the original drag position
                            val originalDragPos = dragFromVisibleIndex?.let { it + contentStart } ?: fromAbs
                            if (toAbs > originalDragPos) {
                                android.util.Log.d("DragDebug", "Triggering DOWNWARD autoscroll (dragging downward)")
                                autoscrollJob = coroutineScope.launch {
                                    while (isDragging) {
                                        try { 
                                            // Re-read current position from shared state
                                            val currentAbs = latestAbsIndex
                                            if (currentAbs < bottomHotZoneAbs || currentAbs >= contentEndExclusive) break // Exit if no longer in hot zone
                                            
                                            queueListState.scrollBy(32f) // Smaller increments for smoother movement
                                            kotlinx.coroutines.delay(20) // Higher frequency for smoother perception
                                        } catch (_: Throwable) { break }
                                    }
                                }
                            } else {
                                android.util.Log.d("DragDebug", "No downward autoscroll - not actually dragging downward (toAbs=$toAbs, originalDragPos=$originalDragPos)")
                            }
                        } else {
                            android.util.Log.d("DragDebug", "No autoscroll triggered - toAbs=$toAbs not in autoscroll zones")
                        }

                        // Always perform visual reordering within the content segment, regardless of direction
                        // Use a "ghost insert row" approach for items dragged above the first visible content item
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastVisualUpdateTime > 20) { // Reduced throttle to 20ms for smoother updates
                            val targetVisualIndex = when {
                                toAbs < contentStart -> 0 // Above header zone - target first position
                                toAbs >= contentEndExclusive -> visualQueueItems.lastIndex.coerceAtLeast(0) // Below content - target last position
                                else -> (toAbs - contentStart).coerceIn(0, visualQueueItems.lastIndex) // Within content area
                            }

                            // Only reorder if the target position is different from current position
                            val currentIndexOfDragging = draggingUid?.let { uid ->
                                visualQueueItems.indexOfFirst { it.uid == uid }
                            } ?: -1

                            if (currentIndexOfDragging >= 0 && currentIndexOfDragging != targetVisualIndex) {
                                val mutable = visualQueueItems.toMutableList()
                                val moved = mutable.removeAt(currentIndexOfDragging)
                                val insertTo = targetVisualIndex.coerceIn(0, mutable.size)
                                mutable.add(insertTo, moved)
                                visualQueueItems = mutable
                                lastVisualUpdateTime = currentTime
                                android.util.Log.d("DragDebug", "Visual reordering: moved item from $currentIndexOfDragging to position $insertTo")
                            }
                        }
                        isDragging = true
                    },
                    onDragEnd = { from, to ->
                        val contentStart = headerOffset
                        val contentEndExclusive = headerOffset + visualQueueItems.size

                        val fromAbs = from
                        val toAbs = to
                        val fallbackFromVis = (fromAbs - contentStart)
                        val clampedToVis = (toAbs.coerceIn(contentStart, contentEndExclusive)) - contentStart

                        // Determine final visual position by finding current index of dragged item
                        val finalIdxFromVisual = draggingUid?.let { uid ->
                            visualQueueItems.indexOfFirst { it.uid == uid }
                        }?.takeIf { it >= 0 } ?: run {
                            // Fallback: compute from pointer position
                            when {
                                toAbs <= contentStart -> 0
                                toAbs >= (contentEndExclusive - 1) -> (visualQueueItems.size - 1).coerceAtLeast(0)
                                else -> clampedToVis
                            }
                        }
                        
                        val fromIdx = (dragFromVisibleIndex ?: fallbackFromVis)
                        
                        // Debug logging for onDragEnd
                        android.util.Log.d("DragDebug", "onDragEnd: toAbs=$toAbs, contentStart=$contentStart, contentEndExclusive=$contentEndExclusive")
                        android.util.Log.d("DragDebug", "onDragEnd: clampedToVis=$clampedToVis, draggingUid=$draggingUid, finalIdxFromVisual=$finalIdxFromVisual")

                        val clearTracking: () -> Unit = {
                            draggingUid = null
                            dragFromVisibleIndex = null
                            autoscrollJob?.cancel()
                            autoscrollJob = null
                        }

                        try {
                            if (fromIdx >= 0 && fromIdx < visualQueueItems.size &&
                                finalIdxFromVisual >= 0 && finalIdxFromVisual < visualQueueItems.size &&
                                fromIdx != finalIdxFromVisual) {
                                coroutineScope.launch {
                                    try {
                                        // Use the existing mapping functions which are designed for this purpose
                                        val fromCombinedIdx = queueOps.getVisibleToCombinedIndexMapping(fromIdx)
                                        val toCombinedIdx = queueOps.getVisibleToCombinedIndexMapping(finalIdxFromVisual)
                                        
                                        // Debug logging for move operation
                                        android.util.Log.d("DragDebug", "Move operation: fromIdx=$fromIdx, finalIdxFromVisual=$finalIdxFromVisual")
                                        android.util.Log.d("DragDebug", "Combined indices: fromCombinedIdx=$fromCombinedIdx, toCombinedIdx=$toCombinedIdx")
                                        
                                        if (fromCombinedIdx >= 0 && toCombinedIdx >= 0 && fromCombinedIdx != toCombinedIdx) {
                                            queueOps.moveItem(fromCombinedIdx, toCombinedIdx)
                                        } else {
                                            android.util.Log.w("DragDebug", "Invalid move operation: fromCombinedIdx=$fromCombinedIdx, toCombinedIdx=$toCombinedIdx")
                                        }
                                        visualQueueItems = queueOps.getVisibleQueue()
                                    } catch (e: Exception) {
                                        android.util.Log.e("QueueScreenDBG", "NP: Error during drag operation", e)
                                        visualQueueItems = queueOps.getVisibleQueue()
                                    } finally {
                                        clearTracking()
                                        isDragging = false
                                    }
                                }
                            } else {
                                android.util.Log.d("DragDebug", "No move operation: fromIdx=$fromIdx, finalIdxFromVisual=$finalIdxFromVisual, visualQueueItems.size=${visualQueueItems.size}")
                                clearTracking()
                                isDragging = false
                                visualQueueItems = queueItems.toList()
                            }
                        } catch (e: Exception) {
                            // Emergency cleanup in case of any unexpected errors
                            android.util.Log.e("QueueScreenDBG", "NP: Critical error during drag end", e)
                            clearTracking()
                            isDragging = false
                            visualQueueItems = queueItems.toList()
                        }
                    }
                )

                // Prefetch after scrolling stops: load from first to last visible queue item (excluding headers)
                LaunchedEffect(queueListState, visualQueueItems) {
                    snapshotFlow { queueListState.isScrollInProgress }
                        .distinctUntilChanged()
                        .collect { isScrolling ->
                            if (isScrolling || visualQueueItems.isEmpty()) return@collect
                            val lastVisible = queueListState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: return@collect
                            val contentLast = lastVisible - headerOffset
                            val end = contentLast.coerceAtMost(visualQueueItems.lastIndex)
                            if (end >= 0) {
                                val ids = visualQueueItems.subList(0, end + 1).map { it.mediaItem.mediaId }
                                viewModel.prefetchArtwork(ids)
                            }
                        }
                }

                LazyColumn(
                    state = queueListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .reorderable(reorderState),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Single context header: "Playing from {source}"
                    item(key = "queue_context_header_single") {
                        val contextTitle = when (qState.context?.type) {
                            QueueManager.ContextType.PLAYLIST -> qState.context?.name ?: "Playlist"
                            QueueManager.ContextType.ALBUM -> qState.context?.name ?: "Album"
                            QueueManager.ContextType.LIKED_SONGS -> "Favorites"
                            QueueManager.ContextType.ARTIST -> qState.context?.name ?: "Artist"
                            QueueManager.ContextType.GENRE -> qState.context?.name ?: "Genre"
                            QueueManager.ContextType.SEARCH -> qState.context?.name ?: "Search"
                            QueueManager.ContextType.DISCOVER -> qState.context?.name ?: "Discover"
                            else -> "Queue"
                        }
                        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Playing from $contextTitle",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                                TextButton(onClick = {
                                    coroutineScope.launch {
                                        // Clear only the visible queue (upcoming items) but keep current playing
                                        queueOps.clearTransientQueues(keepCurrent = true)
                                        // Don't reset playback - just clear the upcoming queue
                                    }
                                }) { Text("Clear queue") }
                            }
                        }
                    }

                    // Sticky header with now playing and play/pause
                    stickyHeader {
                        if (t != null) {
                            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CompactArtwork(
                                        trackUri = t.mediaId,
                                        modifier = Modifier.size(48.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(t.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${t.artist} • ${t.album}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { if (isPlaying) controller?.pause() else controller?.play() }) {
                                        Icon(imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }

                    // Removed section headers; one unified header + sticky now playing row remain
                    // Use visual queue items with stable keys
                    itemsIndexed(
                        items = visualQueueItems,
                        key = { _, item -> "queue_${item.uid}" },
                        contentType = { _, _ -> "queue_item" }
                    ) { idx, qi ->
                        val vt = viewModel.getTrackByMediaId(qi.mediaItem.mediaId) ?: qi.mediaItem.toTrack()
                        ReorderableItem(reorderState, key = "queue_${qi.uid}") { isDragging ->
                            com.musify.mu.ui.components.EnhancedSwipeableItem(
                                onSwipeRight = {
                                    android.util.Log.d("QueueScreenDBG", "NP: swipe right (Play Next) id=${qi.id}")
                                    val ctx = qState.context
                                    coroutineScope.launch {
                                        queueOps.playNextWithContext(items = listOf(vt.toMediaItem()), context = ctx)
                                        android.util.Log.d("QueueScreenDBG", "NP: after swipe right")
                                    }
                                },
                                onSwipeLeft = {
                                    android.util.Log.d("QueueScreenDBG", "NP: swipe left (Remove) id=${qi.id}")
                                    coroutineScope.launch {
                                        queueOps.removeItemByUid(qi.uid)
                                        android.util.Log.d("QueueScreenDBG", "NP: after remove")
                                    }
                                },
                                isInQueue = true,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                com.musify.mu.ui.components.CompactTrackRow(
                                    title = vt.title,
                                    subtitle = "${vt.artist} • ${vt.album}",
                                    artData = vt.artUri,
                                    mediaUri = vt.mediaId,
                                    contentDescription = vt.title,
                                    isPlaying = false, // Remove the now playing indicator from queue items since we have a sticky header
                                    onClick = {
                                        val combinedIndex = queueOps.getVisibleToCombinedIndexMapping(idx)
                                        if (combinedIndex >= 0) {
                                            controller?.seekToDefaultPosition(combinedIndex)
                                        }
                                    },
                                    extraArtOverlay = {
                                        // Enhanced visual indicators matching QueueScreen
                                        when (qi.source) {
                                            QueueManager.QueueSource.PLAY_NEXT -> {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(2.dp)
                                                ) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                        shape = RoundedCornerShape(6.dp),
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.padding(
                                                                horizontal = 6.dp,
                                                                vertical = 2.dp
                                                            )
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.SkipNext,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.onTertiary,
                                                                modifier = Modifier.size(12.dp)
                                                            )
                                                            Spacer(Modifier.width(4.dp))
                                                            Text(
                                                                text = "NEXT",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onTertiary
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            QueueManager.QueueSource.USER_QUEUE -> {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(2.dp)
                                                ) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.secondary,
                                                        shape = RoundedCornerShape(6.dp),
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.padding(
                                                                horizontal = 6.dp,
                                                                vertical = 2.dp
                                                            )
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Person,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.onSecondary,
                                                                modifier = Modifier.size(12.dp)
                                                            )
                                                            Spacer(Modifier.width(4.dp))
                                                            Text(
                                                                text = "YOU",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSecondary
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            else -> {
                                                // Show isolation status for main queue items
                                                if (qi.isIsolated) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopStart)
                                                            .padding(2.dp)
                                                    ) {
                                                        Surface(
                                                            color = MaterialTheme.colorScheme.primaryContainer,
                                                            shape = RoundedCornerShape(6.dp),
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Lock,
                                                                contentDescription = "Protected from source changes",
                                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                modifier = Modifier
                                                                    .size(16.dp)
                                                                    .padding(2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { }, modifier = Modifier.detectReorderAfterLongPress(reorderState)) {
                                            Icon(imageVector = Icons.Rounded.DragHandle, contentDescription = "Drag")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Share result types
sealed class ShareSongResult {
    data class Success(val uri: Uri, val mimeType: String) : ShareSongResult()
    data class Error(val message: String) : ShareSongResult()
}

// Function to share song with metadata and album art
suspend fun shareSongWithMetadata(track: com.musify.mu.data.db.entities.Track, context: android.content.Context): ShareSongResult {
    return withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val originalUri = Uri.parse(track.mediaId)

            // Get MIME type
            val mimeType = contentResolver.getType(originalUri) ?: run {
                val extension = MimeTypeMap.getFileExtensionFromUrl(track.mediaId)
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            } ?: "audio/*"

            // Create temporary file for sharing
            val tempDir = File(context.cacheDir, "shared_music").apply { mkdirs() }
            val fileName = "${track.title.replace(Regex("[^a-zA-Z0-9]"), "_")}_${track.artist.replace(Regex("[^a-zA-Z0-9]"), "_")}"
            val extension = when (mimeType) {
                "audio/mpeg" -> "mp3"
                "audio/flac" -> "flac"
                "audio/ogg" -> "ogg"
                "audio/wav" -> "wav"
                "audio/aac" -> "aac"
                else -> "mp3"
            }
            val tempFile = File(tempDir, "$fileName.$extension")

            // Copy the file with metadata
            contentResolver.openInputStream(originalUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ShareSongResult.Error("Unable to access song file")

            // Try to embed metadata if it's MP3
            if (mimeType == "audio/mpeg" || extension == "mp3") {
                try {
                    embedMp3Metadata(tempFile, track, context)
                } catch (e: Exception) {
                    android.util.Log.w("ShareSong", "Failed to embed metadata", e)
                    // Continue without metadata embedding
                }
            }

            // Create content URI for sharing
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )

            ShareSongResult.Success(contentUri, mimeType)
        } catch (e: Exception) {
            android.util.Log.e("ShareSong", "Error preparing song for sharing", e)
            ShareSongResult.Error("Failed to prepare song: ${e.message}")
        }
    }
}

// Function to embed metadata in MP3 files
private suspend fun embedMp3Metadata(file: File, track: com.musify.mu.data.db.entities.Track, context: android.content.Context) {
    try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, Uri.parse(track.mediaId))

        // Get existing embedded picture if available
        val existingArtwork = retriever.embeddedPicture

        retriever.release()

        // If no embedded artwork but we have a cached artwork URI, try to get it
        val artworkBytes = existingArtwork ?: if (!track.artUri.isNullOrBlank()) {
            try {
                getArtworkBytes(track.artUri, context)
            } catch (e: Exception) {
                android.util.Log.w("ShareSong", "Failed to get artwork for embedding", e)
                null
            }
        } else null

        // For now, we'll log what metadata would be embedded
        // To fully implement MP3 metadata embedding, you would need a library like:
        // - jaudiotagger (https://github.com/hexagonframework/jaudiotagger)
        // - or similar MP3 metadata manipulation library

        android.util.Log.d("ShareSong", "Would embed metadata for: ${track.title}")
        android.util.Log.d("ShareSong", "Artist: ${track.artist}")
        android.util.Log.d("ShareSong", "Album: ${track.album}")
        android.util.Log.d("ShareSong", "Genre: ${track.genre}")
        android.util.Log.d("ShareSong", "Year: ${track.year}")
        android.util.Log.d("ShareSong", "Artwork available: ${artworkBytes?.isNotEmpty() ?: false}")

        // The file already contains the original metadata from the source
        // For a complete implementation, you would:
        // 1. Add jaudiotagger dependency to build.gradle.kts
        // 2. Use AudioFileIO.read(file) to read existing metadata
        // 3. Update title, artist, album, artwork, etc.
        // 4. Use AudioFileIO.write() to save changes

    } catch (e: Exception) {
        android.util.Log.w("ShareSong", "Failed to process MP3 metadata", e)
        throw e
    }
}

// Helper function to get artwork bytes from URI
private suspend fun getArtworkBytes(artUri: String, context: android.content.Context): ByteArray? {
    return try {
        val uri = Uri.parse(artUri)
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        }
    } catch (e: Exception) {
        android.util.Log.w("ShareSong", "Failed to read artwork bytes", e)
        null
    }
}

// Helper function to format duration in mm:ss format
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}