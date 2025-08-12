package com.musify.mu.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) { HomeScreen(navController, onPlay) }
        composable(Screen.Library.route) { LibraryScreen(navController, onPlay) }
        composable(Screen.Queue.route) { QueueScreen(navController) }
        
        // Player screen as a modal overlay - doesn't participate in bottom navigation
        composable(
            route = Screen.NowPlaying.route,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it }, // Start from full screen height (bottom)
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it }, // Slide down to full screen height
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) { 
            BackHandler(enabled = true, onBack = onBackFromPlayer)
            NowPlayingScreen(navController) 
        }
        
        composable(Screen.Lyrics.route) { LyricsView(navController) }
    }
}
