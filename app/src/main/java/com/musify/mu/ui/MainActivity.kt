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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musify.mu.util.resolveTrack

class MainActivity : ComponentActivity() {
    
    private var mediaController: MediaController? = null
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
        val controller = mediaController ?: return
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

        // Capture any incoming VIEW intent (open with Musify)
        handleViewIntent(intent)

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

                    // Data loading is handled automatically on app launch
                    android.util.Log.d("MainActivity", "Permissions granted - data loading handled automatically")

                    // Store optional permissions map for cleaner debugging/possible UI
                    val optionalPermissions = PermissionManager.getOptionalPermissions()
                    val optionalStatus = optionalPermissions.associateWith { perm -> permissions[perm] == true }
                    android.util.Log.d("MainActivity", "Optional permissions: $optionalStatus")
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

            // Use helper to remember and auto-release MediaController
            val rememberedController = rememberMediaController()
            LaunchedEffect(rememberedController) {
                mediaController = rememberedController
                controllerState = rememberedController
                tryPlayPendingOpenUri()
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
        // Ensure MediaController is properly released
        try {
            mediaController?.release()
            mediaController = null
            android.util.Log.d("MainActivity", "MediaController released in onDestroy")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error releasing MediaController in onDestroy", e)
        }
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
    val repo = remember { LibraryRepository.get(context) }
    val stateStore = remember { PlaybackStateStore(context) }

    // Initialize library data on app launch (after permissions are granted)
    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            try {
                repo.dataManager.ensureInitialized()
            } catch (_: Exception) { }
        }
    }

    var currentTrack by remember { mutableStateOf<Track?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var hasPlayableQueue by remember { mutableStateOf(false) }
    var previewTrack by remember { mutableStateOf<Track?>(null) }

    // Listen to media controller changes and clean up listener when controller changes
    DisposableEffect(mediaController) {
        val controller = mediaController
        var listener: Player.Listener? = null
        if (controller != null) {
            listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // Get the full track data from repository to ensure we have the pre-extracted artwork
                    currentTrack = mediaItem?.let { item -> resolveTrack(repo, item) }
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
                        currentTrack = controller.currentMediaItem?.let { item -> resolveTrack(repo, item) }
                    }
                }
            }
            controller.addListener(listener)

            // Initialize UI state
            currentTrack = controller.currentMediaItem?.let { item -> resolveTrack(repo, item) }
            isPlaying = controller.isPlaying
            hasPlayableQueue = controller.mediaItemCount > 0
            if (controller.mediaItemCount > 0) previewTrack = null
        }
        onDispose {
            try { if (listener != null) controller?.removeListener(listener!!) } catch (_: Exception) {}
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
    fun restoreLastQueueAndPlay() {
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
                    AnimatedVisibility(
                        visible = !shouldHideBottomBar,
                        enter = fadeIn(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(300))
                    ) {
                        Column {
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp)) {
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
                                            val target = com.musify.mu.ui.navigation.Screen.NowPlaying.route
                                            if (navController.currentDestination?.route != target) {
                                                navController.navigate(target) { launchSingleTop = true }
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

@Composable
private fun rememberMediaController(): MediaController? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    LaunchedEffect(Unit) {
        try {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, PlayerService::class.java)
            )
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controller = controllerFuture.await()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "rememberMediaController: connection failed", e)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { controller?.release() } catch (e: Exception) {
                android.util.Log.e("MainActivity", "rememberMediaController: release error", e)
            } finally { controller = null }
        }
    }
    return controller
}
