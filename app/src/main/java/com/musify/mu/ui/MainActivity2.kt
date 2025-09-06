package com.musify.mu.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.musify.mu.data.db.entities.Track
import com.musify.mu.playback.PlayerService
import com.musify.mu.playback.PlaybackStateManager
import com.musify.mu.ui.components.NowPlayingBar
import com.musify.mu.ui.navigation.MusifyNavGraph
import com.musify.mu.ui.theme.MusifyTheme
import com.musify.mu.ui.viewmodels.*
import com.musify.mu.util.PermissionManager
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.launch
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity2 : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // ViewModels
    private val mainViewModel: MainViewModel by viewModels()
    private val playbackViewModel: PlaybackViewModel by viewModels()
    private val scanningViewModel: ScanningViewModel by viewModels()
    
    // Injected dependencies
    @Inject
    lateinit var playbackStateManager: PlaybackStateManager
    
    // Media controller
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingOpenUri: android.net.Uri? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Handle any incoming VIEW intent
        handleViewIntent(intent)
        
        setContent {
            MusifyTheme {
                MainContent()
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        initializeMediaController()
    }
    
    override fun onStop() {
        super.onStop()
        releaseMediaController()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop wake word service
        try {
            com.musify.mu.voice.WakeWordService.stop(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WakeWordService", e)
        }
        
        // Cleanup voice controls
        try {
            com.musify.mu.voice.VoiceControlManager.getInstance(this)?.cleanupOnAppDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up VoiceControlManager", e)
        }
    }
    
    private fun initializeMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlayerService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        
        controllerFuture?.addListener(
            {
                try {
                    mediaController = controllerFuture?.get()
                    mediaController?.let { controller ->
                        playbackViewModel.setMediaController(controller)
                        
                        // Set up automatic state saving
                        playbackStateManager.setupAutoSave(controller)
                        
                        // Set up media controller listener
                        controller.addListener(MediaControllerListener())
                        
                        // Try to play any pending file
                        tryPlayPendingOpenUri()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting media controller", e)
                }
            },
            MoreExecutors.directExecutor()
        )
    }
    
    private fun releaseMediaController() {
        mediaController?.let { controller ->
            // Save state before releasing
            playbackStateManager.saveState(controller, immediate = true)
        }
        
        mediaController = null
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controllerFuture = null
    }
    
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                pendingOpenUri = uri
                tryPlayPendingOpenUri()
            }
        }
    }
    
    private fun tryPlayPendingOpenUri() {
        val uri = pendingOpenUri ?: return
        val controller = mediaController ?: return
        
        try {
            controller.setMediaItem(MediaItem.fromUri(uri))
            controller.prepare()
            controller.play()
            pendingOpenUri = null
        } catch (e: Exception) {
            Log.e(TAG, "Error playing URI: $uri", e)
        }
    }
    
    @Composable
    private fun MainContent() {
        val context = LocalContext.current
        val navController = rememberNavController()
        val scope = rememberCoroutineScope()
        
        // Collect states from ViewModels
        val hasPermissions by mainViewModel.hasMediaPermissions.collectAsStateWithLifecycle()
        val isInitialized by mainViewModel.isInitialized.collectAsStateWithLifecycle()
        val currentTrack by playbackViewModel.currentTrack.collectAsStateWithLifecycle()
        val isPlaying by playbackViewModel.isPlaying.collectAsStateWithLifecycle()
        val hasPlayableQueue by playbackViewModel.hasPlayableQueue.collectAsStateWithLifecycle()
        val previewTrack by playbackViewModel.previewTrack.collectAsStateWithLifecycle()
        
        // Permission launcher
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val requiredPermissions = PermissionManager.getRequiredMediaPermissions()
            val requiredGranted = requiredPermissions.all { permission ->
                permissions[permission] == true
            }
            
            if (requiredGranted) {
                mainViewModel.onPermissionsGranted()
            } else {
                mainViewModel.onPermissionsDenied()
            }
        }
        
        // Initialize app on first composition
        LaunchedEffect(Unit) {
            mainViewModel.initializeApp()
            
            // Request permissions if not granted
            if (!hasPermissions) {
                val allPermissions = PermissionManager.getAllPermissions()
                permissionLauncher.launch(allPermissions)
            }
        }
        
        // Initialize media controller in ViewModel
        LaunchedEffect(mediaController) {
            playbackViewModel.setMediaController(mediaController)
        }
        
        // Track the current destination
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val isPlayerScreen = currentRoute == com.musify.mu.ui.navigation.Screen.NowPlaying.route
        val isQueueScreen = currentRoute == com.musify.mu.ui.navigation.Screen.Queue.route
        val shouldHideBottomBar = isPlayerScreen || isQueueScreen
        
        // Play function for screens
        val onPlay: (List<Track>, Int) -> Unit = { tracks, index ->
            playbackViewModel.playTracks(tracks, index)
        }
        
        // Provide media controller through composition local
        val currentMediaId = currentTrack?.mediaId
        CompositionLocalProvider(
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
                        AnimatedVisibility(
                            visible = !shouldHideBottomBar,
                            enter = fadeIn(animationSpec = tween(300)),
                            exit = fadeOut(animationSpec = tween(300))
                        ) {
                            Column {
                                // Now playing bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    AnimatedVisibility(
                                        visible = hasPlayableQueue && currentTrack != null,
                                        enter = fadeIn(animationSpec = tween(300)),
                                        exit = fadeOut(animationSpec = tween(300))
                                    ) {
                                        NowPlayingBar(
                                            navController = navController,
                                            currentTrack = currentTrack ?: previewTrack,
                                            isPlaying = isPlaying,
                                            onPlayPause = { playbackViewModel.togglePlayPause() },
                                            onNext = { playbackViewModel.skipToNext() },
                                            onPrev = { playbackViewModel.skipToPrevious() },
                                            onExpand = {
                                                val target = com.musify.mu.ui.navigation.Screen.NowPlaying.route
                                                if (navController.currentDestination?.route != target) {
                                                    navController.navigate(target) {
                                                        launchSingleTop = true
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                                
                                // Bottom navigation bar
                                com.musify.mu.ui.components.BottomBar(navController)
                            }
                        }
                    }
                ) { paddingValues ->
                    MusifyNavGraph(
                        navController = navController,
                        modifier = Modifier.padding(
                            if (!shouldHideBottomBar) paddingValues else PaddingValues(0.dp)
                        ),
                        onPlay = onPlay,
                        hasPermissions = hasPermissions
                    )
                }
            }
        }
    }
    
    /**
     * Media controller listener to update playback state
     */
    private inner class MediaControllerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playbackViewModel.updateFromMediaItem(mediaItem, reason)
        }
        
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mediaController?.let { controller ->
                playbackViewModel.updatePlaybackState(
                    isPlaying = isPlaying,
                    hasQueue = controller.mediaItemCount > 0
                )
            }
        }
        
        override fun onPlaybackStateChanged(playbackState: Int) {
            mediaController?.let { controller ->
                playbackViewModel.updatePlaybackState(
                    isPlaying = controller.isPlaying,
                    hasQueue = controller.mediaItemCount > 0
                )
            }
        }
        
        override fun onTimelineChanged(timeline: Player.Timeline, reason: Int) {
            mediaController?.let { controller ->
                playbackViewModel.updatePlaybackState(
                    isPlaying = controller.isPlaying,
                    hasQueue = controller.mediaItemCount > 0
                )
                
                if (controller.currentMediaItem != null) {
                    playbackViewModel.updateFromMediaItem(controller.currentMediaItem)
                }
            }
        }
    }
}