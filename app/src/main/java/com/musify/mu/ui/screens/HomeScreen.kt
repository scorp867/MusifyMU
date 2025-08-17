package com.musify.mu.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.ui.components.Artwork
import com.musify.mu.ui.navigation.Screen
import com.musify.mu.playback.LocalMediaController
import com.musify.mu.data.db.entities.Playlist
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Composition local to provide scroll state to child components
val LocalScrollState = compositionLocalOf<LazyListState?> { null }

@Composable
fun HomeScreen(navController: NavController, onPlay: (List<Track>, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    val haptic = LocalHapticFeedback.current
    val controller = LocalMediaController.current

    var recentPlayed by remember { mutableStateOf<List<Track>>(emptyList()) }
    var recentAdded by remember { mutableStateOf<List<Track>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<Track>>(emptyList()) }
    var customPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Function to refresh data
    val refreshData = {
        scope.launch {
            try {
                recentPlayed = repo.recentlyPlayed(12)
                android.util.Log.d("HomeScreen", "Recently played loaded: ${recentPlayed.size} tracks")
                
                recentAdded = repo.recentlyAdded(12)
                android.util.Log.d("HomeScreen", "Recently added loaded: ${recentAdded.size} tracks")
                
                favorites = repo.favorites()
                android.util.Log.d("HomeScreen", "Favorites loaded: ${favorites.size} tracks")
                
                customPlaylists = repo.playlists()
                android.util.Log.d("HomeScreen", "Playlists loaded: ${customPlaylists.size}")
            } catch (e: Exception) {
                android.util.Log.w("HomeScreen", "Failed to refresh data", e)
            }
        }
    }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                // Get data from fast cache - background loading handles the heavy lifting
                recentPlayed = repo.recentlyPlayed(12)
                android.util.Log.d("HomeScreen", "Recently played loaded: ${recentPlayed.size} tracks")
                
                recentAdded = repo.recentlyAdded(12)
                android.util.Log.d("HomeScreen", "Recently added loaded: ${recentAdded.size} tracks")
                
                favorites = repo.favorites()
                android.util.Log.d("HomeScreen", "Favorites loaded: ${favorites.size} tracks")
                
                customPlaylists = repo.playlists()
                android.util.Log.d("HomeScreen", "Playlists loaded: ${customPlaylists.size}")
            } catch (e: Exception) {
                // Handle error gracefully
                android.util.Log.w("HomeScreen", "Failed to load home data", e)
            } finally {
                isLoading = false
            }
        }
    }

    // Refresh data when trigger changes
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            refreshData()
        }
    }

    // Dynamically update recently played when the controller transitions
    LaunchedEffect(controller) {
        controller?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                scope.launch { recentPlayed = repo.recentlyPlayed(12) }
            }
        })
    }

    // Refresh data when the screen becomes visible (simplified approach)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1000) // Initial delay
        refreshData()
    }

    // Create gradient background
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
        )
    )

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Welcome header with animation and settings button
        item {
            WelcomeHeader(navController)
        }

        if (isLoading) {
            items(3) {
                ShimmerCarousel()
            }
        } else {
            item {
                AnimatedCarousel(
                    title = "Recently Played",
                    icon = Icons.Rounded.History,
                    data = recentPlayed,
                    onPlay = { tracks, index ->
                        onPlay(tracks, index)
                        // Trigger refresh after a short delay to allow the database to update
                        scope.launch {
                            kotlinx.coroutines.delay(500)
                            refreshTrigger++
                        }
                    },
                    haptic = haptic,
                    onSeeAll = { navController.navigate("see_all/recently_played") }
                )
            }

            item {
                AnimatedCarousel(
                    title = "Recently Added",
                    icon = Icons.Rounded.NewReleases,
                    data = recentAdded,
                    onPlay = { tracks, index ->
                        onPlay(tracks, index)
                        // Trigger refresh after a short delay to allow the database to update
                        scope.launch {
                            kotlinx.coroutines.delay(500)
                            refreshTrigger++
                        }
                    },
                    haptic = haptic,
                    onSeeAll = { navController.navigate("see_all/recently_added") }
                )
            }

            item {
                AnimatedCarousel(
                    title = "Favourites",
                    icon = Icons.Rounded.Favorite,
                    data = favorites,
                    onPlay = { tracks, index ->
                        onPlay(tracks, index)
                        // Trigger refresh after a short delay to allow the database to update
                        scope.launch {
                            kotlinx.coroutines.delay(500)
                            refreshTrigger++
                        }
                    },
                    haptic = haptic,
                    onSeeAll = { navController.navigate("see_all/favorites") }
                )
            }

            // Custom Playlists Carousel (always visible so user can create playlists)
            item {
                CustomPlaylistsCarousel(
                    playlists = customPlaylists,
                    navController = navController,
                    haptic = haptic,
                    onRefresh = { refreshTrigger++ }
                )
            }
        }
    }

    // Prefetch embedded art for visible recent lists (row-based prefetch for the main column)
    LaunchedEffect(listState, recentAdded, recentPlayed, favorites) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collectLatest { _ ->
                val visibleTracks = (recentAdded + recentPlayed + favorites).take(60)
                val uris = visibleTracks.map { it.mediaId }
                // Simple artwork loading - no preloading needed
            }
    }
}

@Composable
private fun WelcomeHeader(navController: NavController) {
    val infiniteTransition = rememberInfiniteTransition(label = "welcome")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f + shimmer * 0.1f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f + shimmer * 0.1f)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Welcome to Musify",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Discover your music in a whole new way",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
                
                // Settings button
                IconButton(
                    onClick = { navController.navigate(Screen.Settings.route) },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}



@Composable
private fun AnimatedCarousel(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    data: List<Track>,
    onPlay: (List<Track>, Int) -> Unit,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onSeeAll: () -> Unit
) {
    if (data.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSeeAll) {
                Text("See all")
            }
        }

        // Create a scroll state for this row
        val rowScrollState = rememberLazyListState()

        // Provide the scroll state to child components via CompositionLocal
        CompositionLocalProvider(LocalScrollState provides rowScrollState) {
            LazyRow(
                state = rowScrollState,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(data.size, key = { index -> "carousel_${title}_${index}_${data[index].mediaId}" }) { index ->
                    val track = data[index]
                    TrackCard(
                        track = track,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlay(data, index)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: Track,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    // Get scroll state from parent LazyRow
    val scrollState = LocalScrollState.current
    val scrollOffset = scrollState?.firstVisibleItemScrollOffset ?: 0

    Card(
        modifier = Modifier
            .width(150.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                // Use regular Artwork
                Artwork(
                    data = track.artUri,
                    audioUri = track.mediaId,
                    albumId = track.albumId,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize()
                )

                // Play overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.3f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                Color.White.copy(alpha = 0.2f),
                                RoundedCornerShape(24.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = track.title,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = track.artist,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ShimmerCarousel() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Title shimmer
        Box(
            modifier = Modifier
                .height(24.dp)
                .width(150.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f + shimmer * 0.1f),
                    shape = RoundedCornerShape(8.dp)
                )
        )

        // Cards shimmer
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(5) {
                Card(
                    modifier = Modifier.width(150.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f + shimmer * 0.1f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Box(
                            modifier = Modifier
                                .height(16.dp)
                                .fillMaxWidth(0.8f)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f + shimmer * 0.1f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .height(14.dp)
                                .fillMaxWidth(0.6f)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f + shimmer * 0.1f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomPlaylistsCarousel(
    playlists: List<Playlist>,
    navController: NavController,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRefresh: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    val scope = rememberCoroutineScope()

    var showCreateDialog by remember { mutableStateOf(false) }

    // Enhanced playlist creation dialog
    com.musify.mu.ui.components.PlaylistCreationDialog(
        isVisible = showCreateDialog,
        onDismiss = { showCreateDialog = false },
        onCreatePlaylist = { name, imageUri ->
            scope.launch {
                repo.createPlaylist(name, imageUri)
                showCreateDialog = false
                onRefresh()
            }
        }
    )

    // Enhanced playlist carousel
    com.musify.mu.ui.components.PlaylistCarousel(
        playlists = playlists,
        onPlaylistClick = { playlist ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            navController.navigate(com.musify.mu.ui.navigation.Screen.PlaylistDetails.route.replace("{id}", playlist.id.toString()))
        },
        onCreatePlaylistClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showCreateDialog = true
        }
    )
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    var trackCount by remember { mutableStateOf(0) }
    var firstTrackArt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlist.id) {
        val tracks = repo.playlistTracks(playlist.id)
        trackCount = tracks.size
        firstTrackArt = tracks.firstOrNull()?.artUri
    }

    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (firstTrackArt != null) {
                    coil.compose.AsyncImage(
                        model = firstTrackArt,
                        contentDescription = playlist.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "$trackCount ${if (trackCount == 1) "song" else "songs"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}


