package com.musify.mu.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.util.PermissionHelper
import com.musify.mu.ui.navigation.Screen
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.musify.mu.playback.LocalMediaController

enum class SortType {
    TITLE, ARTIST, ALBUM, DATE_ADDED
}

@Composable
fun LibraryScreen(
    navController: NavController,
    onPlay: (List<Track>, Int) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    val haptic = LocalHapticFeedback.current
    val controller = LocalMediaController.current
    
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var permissionChecked by remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val audioPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        permissionChecked = true
        if (granted) {
            coroutineScope.launch {
                tracks = repo.refreshLibrary()
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            val hasPermission = (context as? android.app.Activity)?.let { PermissionHelper.hasAudioPermission(it) } ?: true
            if (!hasPermission && context is android.app.Activity) {
                PermissionHelper.requestAudioPermission(audioPermissionLauncher)
            } else {
                try {
                    tracks = repo.getAllTracks()
                    if (tracks.isEmpty()) {
                        tracks = repo.refreshLibrary()
                    }
                } catch (e: Exception) {
                    // Handle error gracefully
                } finally {
                    isLoading = false
                    permissionChecked = true
                }
            }
        }
    }

    // Background gradient
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        // Simple header
        LibraryHeader()
        
        if (isLoading) {
            LoadingLibrary()
        } else if (tracks.isEmpty()) {
            EmptyLibrary()
        } else {
            // Track list with animations
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = tracks,
                    key = { track -> track.mediaId }
                ) { track ->
                    val index = tracks.indexOf(track)
                    TrackItem(
                        track = track,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlay(tracks, index)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryHeader() {
    var searchQuery by remember { mutableStateOf("") }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(25.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Search your music library...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                },
                modifier = Modifier
                    .weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = true
            )
            
            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { searchQuery = "" },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Clear,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackItem(
    track: Track,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album artwork
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                com.musify.mu.ui.components.Artwork(
                    data = track.artUri,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Track info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Play indicator
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun LoadingLibrary() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(10) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f + shimmer * 0.1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .height(16.dp)
                                .fillMaxWidth(0.7f)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f + shimmer * 0.1f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Box(
                            modifier = Modifier
                                .height(14.dp)
                                .fillMaxWidth(0.5f)
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
private fun EmptyLibrary() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "No music found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
            
            Text(
                text = "Add some music to your device to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}
