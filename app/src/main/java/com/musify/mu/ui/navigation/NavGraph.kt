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
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) { HomeScreen(navController, onPlay) }
        composable(Screen.Library.route) { LibraryScreen(navController, onPlay) }
        
        // Queue screen as a modal overlay - only accessible from player screen
        composable(
            route = Screen.Queue.route,
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
            BackHandler(enabled = true) {
                // Always go back to the previous screen (should be player)
                navController.popBackStack()
            }
            QueueScreen(navController) 
        }
        
        composable(Screen.SeeAll.route) { backStackEntry ->
            val type = backStackEntry.arguments?.getString("type") ?: ""
            SeeAllScreen(navController = navController, type = type, onPlay = onPlay)
        }
        composable(Screen.PlaylistDetails.route) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: -1L
            PlaylistDetailsScreen(navController = navController, playlistId = id, onPlay = onPlay)
        }
        
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
