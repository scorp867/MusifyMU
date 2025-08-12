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
import com.musify.mu.data.repo.LibraryRepository
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
        
        setContent {
            MusifyTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()
                val context = androidx.compose.ui.platform.LocalContext.current
                val repo = remember { LibraryRepository.get(context) }

                var controller by remember { mutableStateOf<MediaController?>(null) }
                var currentTrack by remember { mutableStateOf<Track?>(null) }
                var isPlaying by remember { mutableStateOf(false) }
                var hasPlayedBefore by remember { mutableStateOf(false) }

                // Create controller only when we need to play
                val createController = {
                    val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
                    MediaController.Builder(context, token).buildAsync()
                }

                // Check if we should navigate to player from notification
                LaunchedEffect(intent?.getStringExtra("navigate_to")) {
                    if (intent?.getStringExtra("navigate_to") == "player") {
                        navController.navigate(com.musify.mu.ui.navigation.Screen.NowPlaying.route)
                    }
                }

                // Check for last played track on app start
                LaunchedEffect(Unit) {
                    try {
                        val recentTracks = repo.recentlyPlayed(1)
                        if (recentTracks.isNotEmpty()) {
                            currentTrack = recentTracks.first()
                            hasPlayedBefore = true
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "Failed to load recent tracks", e)
                    }
                }

                val onPlay: (List<Track>, Int) -> Unit = { tracks, index ->
                    scope.launch {
                        try {
                            // Create controller only when we need to play
                            if (controller == null) {
                                controller = createController().await()
                                controller?.addListener(object : Player.Listener {
                                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                        currentTrack = mediaItem?.toTrack()
                                        hasPlayedBefore = true
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
                                                hasPlayedBefore = true
                                            }
                                        }
                                    }
                                })
                            }
                            
                            controller?.let { c ->
                                c.setMediaItems(tracks.map { it.toMediaItem() }, index, 0)
                                c.prepare()
                                c.play()
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

                // Cleanup controller when activity is destroyed
                DisposableEffect(Unit) {
                    onDispose {
                        controller?.release()
                    }
                }

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
                                    // Show now playing bar only when there's a current track and we've played before
                                    AnimatedVisibility(
                                        visible = currentTrack != null && hasPlayedBefore,
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
