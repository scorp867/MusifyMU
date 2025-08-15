package com.musify.mu.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.playback.PlayerService
import com.musify.mu.ui.components.NowPlayingBar
import com.musify.mu.ui.navigation.MusifyNavGraph
import com.musify.mu.ui.theme.MusifyTheme
import com.musify.mu.util.toMediaItem
import com.musify.mu.util.PermissionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri

class MainActivity : ComponentActivity() {
    
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = this@MainActivity
            var controllerState by remember { mutableStateOf<MediaController?>(null) }
            var hasPermissions by remember { mutableStateOf(false) }

            android.util.Log.d("MainActivity", "Composable created - initial hasPermissions: $hasPermissions")

            // Permission launcher for requesting media permissions
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                android.util.Log.d("MainActivity", "Permission result received: $permissions")

                // Check if required permissions are granted (ignore optional ones)
                val requiredPermissions = PermissionManager.getRequiredMediaPermissions()
                val requiredGranted = requiredPermissions.all { permission ->
                    permissions[permission] == true
                }

                android.util.Log.d("MainActivity", "Required permissions granted: $requiredGranted")
                android.util.Log.d("MainActivity", "Required permissions: ${requiredPermissions.toList()}")

                hasPermissions = requiredGranted
                android.util.Log.d("MainActivity", "Updated hasPermissions to: $hasPermissions")

                if (requiredGranted) {
                    android.util.Log.d("MainActivity", "Required media permissions granted - updating UI state")

                    // Initialize background data loading when permissions are granted
                    val repo = LibraryRepository.get(context)
                    repo.initializeBackgroundLoading()
                    android.util.Log.d("MainActivity", "Background data loading initialized after permission grant")

                    // Log optional permissions status
                    val optionalPermissions = PermissionManager.getOptionalPermissions()
                    optionalPermissions.forEach { permission ->
                        val granted = permissions[permission] == true
                        android.util.Log.d("MainActivity", "Optional permission $permission: ${if (granted) "GRANTED" else "DENIED"}")
                    }
                } else {
                    val deniedRequired = requiredPermissions.filter { permissions[it] != true }
                    android.util.Log.w("MainActivity", "Required permissions denied: $deniedRequired")
                }
            }

            // Check permissions immediately when the composable is created
            LaunchedEffect(Unit) {
                android.util.Log.d("MainActivity", "LaunchedEffect started - checking permissions")
                val requiredPermissions = PermissionManager.getRequiredMediaPermissions()
                val allPermissions = PermissionManager.getAllPermissions()
                android.util.Log.d("MainActivity", "Required permissions: ${requiredPermissions.toList()}")
                android.util.Log.d("MainActivity", "All permissions to request: ${allPermissions.toList()}")

                val currentlyHasPermissions = PermissionManager.checkMediaPermissions(context)
                android.util.Log.d("MainActivity", "Current permission status: $currentlyHasPermissions")

                if (currentlyHasPermissions) {
                    hasPermissions = true
                    android.util.Log.d("MainActivity", "Required permissions already granted - set hasPermissions to true")
                    
                    // Initialize background data loading immediately when permissions are available
                    val repo = LibraryRepository.get(context)
                    repo.initializeBackgroundLoading()
                    android.util.Log.d("MainActivity", "Background data loading initialized")
                } else {
                    android.util.Log.d("MainActivity", "Requesting all permissions: ${allPermissions.toList()}")
                    permissionLauncher.launch(allPermissions)
                }
            }

            // Connect to media service with proper cleanup
            LaunchedEffect(Unit) {
                try {
                    val sessionToken = SessionToken(
                        context,
                        ComponentName(context, PlayerService::class.java)
                    )
                    val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                    val controller = controllerFuture.await()
                    mediaController = controller
                    controllerState = controller
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to connect to MediaController", e)
                }
            }
            
            // Cleanup MediaController when leaving composition
            DisposableEffect(Unit) {
                onDispose {
                    try {
                        mediaController?.release()
                        mediaController = null
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error releasing MediaController", e)
                    }
                }
            }

            android.util.Log.d("MainActivity", "About to render UI with hasPermissions: $hasPermissions")

            MusifyTheme {
                Surface {
                    AppContent(
                        mediaController = controllerState,
                        hasPermissions = hasPermissions
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop the PlayerService when the main activity is destroyed (app swiped away)
        try {
            val serviceIntent = Intent(this, PlayerService::class.java)
            stopService(serviceIntent)
            android.util.Log.d("MainActivity", "PlayerService stopped")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping PlayerService", e)
        }
        
        // Ensure MediaController is properly released
        try {
            mediaController?.release()
            mediaController = null
            android.util.Log.d("MainActivity", "MediaController released in onDestroy")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error releasing MediaController in onDestroy", e)
        }
    }
}

@Composable
private fun AppContent(
    mediaController: MediaController?,
    hasPermissions: Boolean
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    val stateStore = remember { PlaybackStateStore(context) }

    var currentTrack by remember { mutableStateOf<Track?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var hasPlayableQueue by remember { mutableStateOf(false) }
    var previewTrack by remember { mutableStateOf<Track?>(null) }

    // Listen to media controller changes
    LaunchedEffect(mediaController) {
        mediaController?.let { controller ->
            controller.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentTrack = mediaItem?.toTrack()
                    hasPlayableQueue = (controller.mediaItemCount > 0)
                    if (controller.mediaItemCount > 0) previewTrack = null
                }

                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    isPlaying = isPlayingNow
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    isPlaying = controller.isPlaying
                    hasPlayableQueue = controller.mediaItemCount > 0
                    if (controller.mediaItemCount > 0) previewTrack = null
                }

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int
                ) {
                    hasPlayableQueue = controller.mediaItemCount > 0
                    if (controller.currentMediaItem != null) {
                        currentTrack = controller.currentMediaItem?.toTrack()
                    }
                }
            })

            // Initialize UI state
            currentTrack = controller.currentMediaItem?.toTrack()
            isPlaying = controller.isPlaying
            hasPlayableQueue = controller.mediaItemCount > 0
            if (controller.mediaItemCount > 0) previewTrack = null
        }
    }

    // Load preview of last played track for miniplayer when there is no controller queue yet
    LaunchedEffect(hasPermissions, mediaController) {
        if (hasPermissions && (mediaController?.mediaItemCount ?: 0) == 0) {
            scope.launch(Dispatchers.IO) {
                try {
                    val state = stateStore.load()
                    val trackId = state?.mediaIds?.getOrNull(state.index ?: 0)
                    val preview = trackId?.let { repo.getTrackByMediaId(it) }
                    withContext(Dispatchers.Main) {
                        if ((mediaController?.mediaItemCount ?: 0) == 0) {
                            previewTrack = preview
                            currentTrack = preview ?: currentTrack
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // Helper: restore last queue from store and play
    val restoreLastQueueAndPlay: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            try {
                val state = stateStore.load()
                if (state == null || state.mediaIds.isEmpty()) return@launch
                val validTracks = state.mediaIds.mapNotNull { id ->
                    repo.getTrackByMediaId(id)?.let { track ->
                        try {
                            val uri = Uri.parse(track.mediaId)
                            context.contentResolver.openInputStream(uri)?.close()
                            track
                        } catch (_: Exception) { null }
                    }
                }
                if (validTracks.isEmpty()) return@launch
                val items = validTracks.map { it.toMediaItem() }
                val validIndex = state.index.coerceIn(0, items.size - 1)
                val pos = state.posMs.coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    mediaController?.let { c ->
                        c.setMediaItems(items, validIndex, pos)
                        c.prepare()
                        c.play()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    val onPlay: (List<Track>, Int) -> Unit = { tracks, index ->
        scope.launch {
            try {
                mediaController?.let { controller ->
                    controller.setMediaItems(tracks.map { it.toMediaItem() }, index, 0)
                    // Prepare/play triggers service lazy init under the hood
                    controller.prepare()
                    controller.play()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to start playback", e)
            }
        }
    }

    // Track the current destination to hide bottom elements on player screen
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isPlayerScreen = currentRoute == com.musify.mu.ui.navigation.Screen.NowPlaying.route
    val isQueueScreen = currentRoute == com.musify.mu.ui.navigation.Screen.Queue.route
    val shouldHideBottomBar = isPlayerScreen || isQueueScreen

    androidx.compose.runtime.CompositionLocalProvider(com.musify.mu.playback.LocalMediaController provides mediaController) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                bottomBar = {
                    // Animated visibility for smoother transitions
                    AnimatedVisibility(
                        visible = !shouldHideBottomBar,
                        enter = fadeIn(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(300))
                    ) {
                        Column {
                            // Show now playing bar only when controller has a queue
                            AnimatedVisibility(
                                visible = hasPlayableQueue && currentTrack != null,
                                enter = fadeIn(animationSpec = tween(300)),
                                exit = fadeOut(animationSpec = tween(300))
                            ) {
                                NowPlayingBar(
                                    navController = navController,
                                    currentTrack = currentTrack ?: previewTrack,
                                    isPlaying = isPlaying,
                                    onPlayPause = {
                                        mediaController?.let { c ->
                                            if ((c.mediaItemCount == 0) && previewTrack != null) {
                                                restoreLastQueueAndPlay()
                                            } else {
                                                if (c.isPlaying) c.pause() else c.play()
                                            }
                                        }
                                    },
                                    onNext = {
                                        mediaController?.let { c ->
                                            if ((c.mediaItemCount == 0) && previewTrack != null) {
                                                restoreLastQueueAndPlay()
                                            } else {
                                                c.seekToNext()
                                                if (!c.isPlaying) c.play()
                                            }
                                        }
                                    },
                                    onPrev = {
                                        mediaController?.let { c ->
                                            if ((c.mediaItemCount == 0) && previewTrack != null) {
                                                restoreLastQueueAndPlay()
                                            } else {
                                                c.seekToPrevious()
                                                if (!c.isPlaying) c.play()
                                            }
                                        }
                                    },
                                    onExpand = {
                                        navController.navigate(com.musify.mu.ui.navigation.Screen.NowPlaying.route)
                                    }
                                )
                            }
                            com.musify.mu.ui.components.BottomBar(navController)
                        }
                    }
                }
            ) { paddingValues ->
                MusifyNavGraph(
                    navController = navController,
                    modifier = Modifier.padding(
                        if (!shouldHideBottomBar) paddingValues else PaddingValues(
                            0.dp
                        )
                    ),
                    onPlay = onPlay,
                    hasPermissions = hasPermissions
                )
            }
        }
    }
}

private fun MediaItem.toTrack(): Track {
    val md: MediaMetadata = mediaMetadata
    return Track(
        mediaId = mediaId,
        title = md.title?.toString() ?: "",
        artist = md.artist?.toString() ?: "",
        album = md.albumTitle?.toString() ?: "",
        durationMs = 0L,
        artUri = md.artworkUri?.toString(),
        albumId = null
    )
}
