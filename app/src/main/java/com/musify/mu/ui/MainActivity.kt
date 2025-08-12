package com.musify.mu.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.musify.mu.playback.PlayerService
import com.musify.mu.ui.components.NowPlayingBar
import com.musify.mu.ui.navigation.MusifyNavGraph
import com.musify.mu.ui.theme.MusifyTheme
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen for better app launch experience
        installSplashScreen()
        
        // Enable edge-to-edge display for modern look
        enableEdgeToEdge()
        
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
        
        setContent {
            MusifyTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                var controller by remember { mutableStateOf<MediaController?>(null) }
                var currentTrack by remember { mutableStateOf<Track?>(null) }
                var isPlaying by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val token = SessionToken(applicationContext, ComponentName(applicationContext, PlayerService::class.java))
                    controller = MediaController.Builder(applicationContext, token).buildAsync().await()
                    controller?.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            currentTrack = mediaItem?.toTrack()
                        }
                        
                        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                            isPlaying = isPlayingNow
                        }
                        
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isPlaying = controller?.isPlaying == true
                            // Update track info when ready
                            if (playbackState == Player.STATE_READY) {
                                val item = controller?.currentMediaItem
                                if (item != null) {
                                    currentTrack = item.toTrack()
                                }
                            }
                        }
                    })
                }

                // Check if we should navigate to player from notification
                LaunchedEffect(intent?.getStringExtra("navigate_to")) {
                    if (intent?.getStringExtra("navigate_to") == "player") {
                        navController.navigate(com.musify.mu.ui.navigation.Screen.NowPlaying.route)
                    }
                }

                val onPlay: (List<Track>, Int) -> Unit = { tracks, index ->
                    scope.launch {
                        controller?.let { c ->
                            c.setMediaItems(tracks.map { it.toMediaItem() }, index, 0)
                            c.prepare()
                            c.play()
                        }
                    }
                }

                // Track the current destination to hide bottom elements on player screen
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val isPlayerScreen = currentRoute == com.musify.mu.ui.navigation.Screen.NowPlaying.route

                androidx.compose.runtime.CompositionLocalProvider(com.musify.mu.playback.LocalMediaController provides controller) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        bottomBar = {
                            // Animated visibility for smoother transitions
                            AnimatedVisibility(
                                visible = !isPlayerScreen,
                                enter = fadeIn(animationSpec = tween(300)),
                                exit = fadeOut(animationSpec = tween(300))
                            ) {
                                Column {
                                    // Show now playing bar only when there's a current track
                                    AnimatedVisibility(
                                        visible = currentTrack != null,
                                        enter = fadeIn(animationSpec = tween(300)),
                                        exit = fadeOut(animationSpec = tween(300))
                                    ) {
                                        NowPlayingBar(
                                            navController = navController,
                                            currentTrack = currentTrack,
                                            isPlaying = isPlaying,
                                            onPlayPause = { 
                                                controller?.let { 
                                                    if (it.isPlaying) it.pause() else it.play() 
                                                } 
                                            },
                                            onNext = { controller?.seekToNext() },
                                            onPrev = { controller?.seekToPrevious() },
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
                            modifier = Modifier.padding(if (!isPlayerScreen) paddingValues else PaddingValues(0.dp)),
                            onPlay = onPlay
                        )
                    }
                }
                }
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
