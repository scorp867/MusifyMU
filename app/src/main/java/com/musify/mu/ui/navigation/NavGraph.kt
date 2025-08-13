package com.musify.mu.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.musify.mu.data.db.entities.Track
import com.musify.mu.ui.screens.*

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Library : Screen("library")
    object Queue : Screen("queue")
    object NowPlaying : Screen("now_playing")
    object Lyrics : Screen("lyrics")
    object SeeAll : Screen("see_all/{type}")
    object PlaylistDetails : Screen("playlist/{id}")
}

@Composable
fun MusifyNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onPlay: (List<Track>, Int) -> Unit
) {
    // Configure back behavior for player screen
    val onBackFromPlayer: () -> Unit = {
        // Pop back to previous screen without re-creating that screen
        navController.popBackStack()
    }
    
    // Smooth animation constants
    val animationDurationMs = 400
    val fastAnimationDurationMs = 300
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        // Home screen with smooth horizontal transitions
        composable(
            route = Screen.Home.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(animationDurationMs, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(animationDurationMs))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it / 3 },
                    animationSpec = tween(animationDurationMs, easing = LinearOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(animationDurationMs))
            }
        ) { HomeScreen(navController, onPlay) }
        
        // Library screen with smooth horizontal transitions
        composable(
            route = Screen.Library.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it / 3 },
                    animationSpec = tween(animationDurationMs, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(animationDurationMs))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it / 3 },
                    animationSpec = tween(animationDurationMs, easing = LinearOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(animationDurationMs))
            }
        ) { LibraryScreen(navController, onPlay) }
        
        // Queue screen as a smooth modal overlay
        composable(
            route = Screen.Queue.route,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(fastAnimationDurationMs, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(fastAnimationDurationMs))
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(fastAnimationDurationMs, easing = LinearOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(fastAnimationDurationMs))
            }
        ) { 
            BackHandler(enabled = true) {
                navController.popBackStack()
            }
            QueueScreen(navController) 
        }
        
        // See All screen with smooth transitions
        composable(
            route = Screen.SeeAll.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it / 2 },
                    animationSpec = tween(animationDurationMs, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(animationDurationMs))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it / 2 },
                    animationSpec = tween(animationDurationMs, easing = LinearOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(animationDurationMs))
            }
        ) { backStackEntry ->
            val type = backStackEntry.arguments?.getString("type") ?: ""
            SeeAllScreen(navController = navController, type = type, onPlay = onPlay)
        }
        
        // Playlist Details screen with smooth transitions
        composable(
            route = Screen.PlaylistDetails.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it / 2 },
                    animationSpec = tween(animationDurationMs, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(animationDurationMs))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it / 2 },
                    animationSpec = tween(animationDurationMs, easing = LinearOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(animationDurationMs))
            }
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: -1L
            PlaylistDetailsScreen(navController = navController, playlistId = id, onPlay = onPlay)
        }
        
        // Player screen as a smooth modal overlay
        composable(
            route = Screen.NowPlaying.route,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(fastAnimationDurationMs, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(fastAnimationDurationMs))
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(fastAnimationDurationMs, easing = LinearOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(fastAnimationDurationMs))
            }
        ) { 
            BackHandler(enabled = true, onBack = onBackFromPlayer)
            NowPlayingScreen(navController) 
        }
        
        // Lyrics screen with fade transition
        composable(
            route = Screen.Lyrics.route,
            enterTransition = {
                fadeIn(animationSpec = tween(fastAnimationDurationMs))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(fastAnimationDurationMs))
            }
        ) { LyricsView(navController) }
    }
}
