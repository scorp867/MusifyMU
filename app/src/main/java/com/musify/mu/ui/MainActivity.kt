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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.musify.mu.ui.helpers.rememberMediaController
import com.musify.mu.ui.helpers.resolveTrack
import com.musify.mu.ui.helpers.PlaybackRestorer
import com.musify.mu.ui.helpers.MediaControllerListener
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var injectedRepo: LibraryRepository
    
    // Store Media3 controller independently to avoid shadowing Activity.mediaController (framework)
    companion object {
        @Volatile
        private var appMediaController: MediaController? = null
    }
    private var pendingOpenUri: android.net.Uri? = null

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                // Remember for when controller is ready
                pendingOpenUri = uri
                tryPlayPendingOpenUri()
            }
        }
    }

    fun tryPlayPendingOpenUri() {
        val uri = pendingOpenUri ?: return
        val controller = appMediaController ?: return
        try {
            controller.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            controller.prepare()
            controller.play()
            pendingOpenUri = null
        } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Debug instrumentation removed

        // Capture any incoming VIEW intent (open with Musify)
        handleViewIntent(intent)

        setContent {
            val context = this@MainActivity
            val controllerState = rememberMediaController()
            MainActivity.appMediaController = controllerState // Update global reference for VIEW intents
            
            var hasPermissions by remember { mutableStateOf(false) }
            var permissionStatuses by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

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

                // Store all permission statuses in a map
                permissionStatuses = permissions.toMap()

                if (requiredGranted) {
                    android.util.Log.d("MainActivity", "Required media permissions granted - updating UI state")

                    // Data loading is handled automatically on app launch
                    android.util.Log.d("MainActivity", "Permissions granted - data loading handled automatically")
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
                    
                    // Data loading is now initialized on app launch automatically
                    android.util.Log.d("MainActivity", "Permissions granted - data loading handled by app launch")
                } else {
                    android.util.Log.d("MainActivity", "Requesting all permissions: ${allPermissions.toList()}")
                    permissionLauncher.launch(allPermissions)
                }
            }

            // Try to play any pending file open request when controller is ready
            LaunchedEffect(controllerState) {
                if (controllerState != null) {
                    tryPlayPendingOpenUri()
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
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // MediaController is now managed by rememberMediaController()
        // Stop WakeWordService to ensure microphone is released
        try {
            com.musify.mu.voice.WakeWordService.stop(this)
            android.util.Log.d("MainActivity", "WakeWordService stopped in onDestroy")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping WakeWordService", e)
        }
        // Cleanup voice controls on app destroy
        try {
            com.musify.mu.voice.VoiceControlManager.getInstance(this)?.cleanupOnAppDestroy()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error cleaning up VoiceControlManager", e)
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
    val stateStore = remember { PlaybackStateStore(context) }
    val libraryViewModel: com.musify.mu.ui.viewmodels.LibraryViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val playbackViewModel: com.musify.mu.ui.viewmodels.PlaybackViewModel = androidx.hilt.navigation.compose.hiltViewModel()

    // Initialize library data on app launch (after permissions are granted)
    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            try {
                libraryViewModel.ensureDataManagerInitialized()
            } catch (_: Exception) { }
        }
    }

    var currentTrack by remember { mutableStateOf<Track?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var hasPlayableQueue by remember { mutableStateOf(false) }
    var previewTrack by remember { mutableStateOf<Track?>(null) }

    // Listen to media controller changes with proper lifecycle management
    MediaControllerListener(
        controller = mediaController,
        onMediaItemTransition = { mediaItem, _ ->
            currentTrack = playbackViewModel.resolveTrack(mediaItem)
            hasPlayableQueue = (mediaController?.mediaItemCount ?: 0) > 0
            if (hasPlayableQueue) previewTrack = null
        },
        onIsPlayingChanged = { isPlayingNow ->
            isPlaying = isPlayingNow
        },
        onPlaybackStateChanged = { _ ->
        mediaController?.let { controller ->
                    isPlaying = controller.isPlaying
                    hasPlayableQueue = controller.mediaItemCount > 0
                    if (controller.mediaItemCount > 0) previewTrack = null
                }
        },
        onTimelineChanged = { _, _ ->
            mediaController?.let { controller ->
                    hasPlayableQueue = controller.mediaItemCount > 0
                    if (controller.currentMediaItem != null) {
                    currentTrack = playbackViewModel.resolveTrack(controller.currentMediaItem)
                }
            }
        }
    )
    
    // Initialize UI state when controller connects
    LaunchedEffect(mediaController) {
        mediaController?.let { controller ->
            currentTrack = playbackViewModel.resolveTrack(controller.currentMediaItem)
            isPlaying = controller.isPlaying
            hasPlayableQueue = controller.mediaItemCount > 0
            if (controller.mediaItemCount > 0) previewTrack = null
        }
    }

    // Load preview of last played track for miniplayer when there is no controller queue yet
    LaunchedEffect(hasPermissions, mediaController) {
        if (hasPermissions && (mediaController?.mediaItemCount ?: 0) == 0) {
            scope.launch {
                val preview = playbackViewModel.getPreviewTrack()
                        if ((mediaController?.mediaItemCount ?: 0) == 0) {
                            previewTrack = preview
                            currentTrack = preview ?: currentTrack
                        }
            }
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
    // Helper: restore last queue from store and play
    val restoreLastQueueAndPlay: () -> Unit = {
        scope.launch {
            playbackViewModel.restoreQueueAndPlay(mediaController, onPlay)
        }
    }

    // Track the current destination to hide bottom elements on player screen
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isPlayerScreen = currentRoute == com.musify.mu.ui.navigation.Screen.NowPlaying.route
    val isQueueScreen = currentRoute == com.musify.mu.ui.navigation.Screen.Queue.route
    val shouldHideBottomBar = isPlayerScreen || isQueueScreen

    val currentMediaId = currentTrack?.mediaId
    androidx.compose.runtime.CompositionLocalProvider(
        com.musify.mu.playback.LocalMediaController provides mediaController,
        com.musify.mu.playback.LocalPlaybackMediaId provides currentMediaId,
        com.musify.mu.playback.LocalIsPlaying provides isPlaying
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                bottomBar = {
                    // Animated visibility for smoother transitions
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !shouldHideBottomBar,
                        enter = fadeIn(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(300))
                    ) {
                        Column {
                            // Show now playing bar only when controller has a queue
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                            androidx.compose.animation.AnimatedVisibility(
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
                                        val target = com.musify.mu.ui.navigation.Screen.NowPlaying.route
                                        // Navigate only if we're not already showing it
                                        if (navController.currentDestination?.route != target) {
                                            navController.navigate(target) {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                )
                                }
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
