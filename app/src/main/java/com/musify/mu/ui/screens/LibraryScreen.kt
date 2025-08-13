package com.musify.mu.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.LibraryRefreshState
import com.musify.mu.playback.LocalMediaController
import com.musify.mu.util.PermissionHelper
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import android.Manifest
import android.os.Build
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

enum class SortType {
    TITLE, ARTIST, ALBUM, DATE_ADDED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController, onPlay: (List<Track>, Int) -> Unit) {
    val context = LocalContext.current
    val controller = LocalMediaController.current
    val coroutineScope = rememberCoroutineScope()
    val repo = remember { LibraryRepository.get(context) }
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf(0f) }
    var scanMessage by remember { mutableStateOf("") }
    var permissionChecked by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    
    // Permission launcher with better handling
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        hasPermission = allGranted
        permissionChecked = true
        
        if (allGranted) {
            coroutineScope.launch {
                isScanning = true
                repo.refreshLibraryWithProgress().collectLatest { state ->
                    when (state) {
                        is LibraryRefreshState.Loading -> {
                            if (state.total > 0) {
                                scanProgress = state.processed.toFloat() / state.total
                                scanMessage = "Scanning music library... ${state.processed}/${state.total}"
                            } else {
                                scanMessage = "Preparing to scan..."
                            }
                        }
                        is LibraryRefreshState.Success -> {
                            tracks = state.tracks
                            isLoading = false
                            isScanning = false
                            scanMessage = ""
                        }
                        is LibraryRefreshState.Error -> {
                            isLoading = false
                            isScanning = false
                            scanMessage = "Error: ${state.message}"
                        }
                    }
                }
            }
        } else {
            // Check if we should show rationale
            val activity = context as? android.app.Activity
            if (activity != null && PermissionHelper.shouldShowAudioRationale(activity)) {
                showPermissionRationale = true
            }
        }
    }

    // Check permissions and load library on launch
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val activity = context as? android.app.Activity
            hasPermission = activity?.let { PermissionHelper.hasAudioPermission(it) } ?: true
            
            if (!hasPermission && activity != null) {
                if (PermissionHelper.shouldShowAudioRationale(activity)) {
                    showPermissionRationale = true
                } else {
                    PermissionHelper.requestAudioPermission(audioPermissionLauncher)
                }
            } else {
                permissionChecked = true
                try {
                    // First try to load from cache
                    tracks = repo.getAllTracks()
                    
                    // If no tracks, scan
                    if (tracks.isEmpty()) {
                        isScanning = true
                        repo.refreshLibraryWithProgress().collectLatest { state ->
                            when (state) {
                                is LibraryRefreshState.Loading -> {
                                    if (state.total > 0) {
                                        scanProgress = state.processed.toFloat() / state.total
                                        scanMessage = "Scanning music library... ${state.processed}/${state.total}"
                                    } else {
                                        scanMessage = "Preparing to scan..."
                                    }
                                }
                                is LibraryRefreshState.Success -> {
                                    tracks = state.tracks
                                    isScanning = false
                                }
                                is LibraryRefreshState.Error -> {
                                    isScanning = false
                                    scanMessage = "Error: ${state.message}"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LibraryScreen", "Error loading library", e)
                } finally {
                    isLoading = false
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
        
        when {
            !permissionChecked || isLoading -> {
                LoadingLibrary()
            }
            !hasPermission -> {
                PermissionRequest(
                    onRequestPermission = {
                        PermissionHelper.requestAudioPermission(audioPermissionLauncher)
                    },
                    showRationale = showPermissionRationale
                )
            }
            isScanning -> {
                ScanningProgress(
                    progress = scanProgress,
                    message = scanMessage
                )
            }
            tracks.isEmpty() -> {
                EmptyLibrary(
                    onRefresh = {
                        coroutineScope.launch {
                            isScanning = true
                            repo.refreshLibraryWithProgress().collectLatest { state ->
                                when (state) {
                                    is LibraryRefreshState.Loading -> {
                                        if (state.total > 0) {
                                            scanProgress = state.processed.toFloat() / state.total
                                            scanMessage = "Scanning music library... ${state.processed}/${state.total}"
                                        }
                                    }
                                    is LibraryRefreshState.Success -> {
                                        tracks = state.tracks
                                        isScanning = false
                                    }
                                    is LibraryRefreshState.Error -> {
                                        isScanning = false
                                    }
                                }
                            }
                        }
                    }
                )
            }
            else -> {
                // Track list with improved interactions
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(tracks, key = { index, track -> "library_${index}_${track.mediaId}" }) { index, track ->
                        
                        // Improved track item without aggressive swipe gestures
                        TrackItem(
                            track = track,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlay(tracks, index)
                            },
                            onAddToQueue = { addToEnd ->
                                // Only add to queue when explicitly requested (not while scrolling)
                                if (addToEnd) {
                                    controller?.addMediaItem(track.toMediaItem())
                                } else {
                                    val insertIndex = ((controller?.currentMediaItemIndex ?: -1) + 1)
                                        .coerceAtMost(controller?.mediaItemCount ?: 0)
                                    controller?.addMediaItem(insertIndex, track.toMediaItem())
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Permission rationale dialog
    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("Permission Required") },
            text = { 
                Text(
                    "Musify needs access to your music library to play songs. " +
                    "Please grant the permission to continue."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionRationale = false
                        PermissionHelper.requestAudioPermission(audioPermissionLauncher)
                    }
                ) {
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) {
                    Text("Cancel")
                }
            }
        )
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
    onClick: () -> Unit,
    onAddToQueue: (Boolean) -> Unit
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
            
            // Queue actions
            var showMenu by remember { mutableStateOf(false) }
            
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        leadingIcon = {
                            Icon(Icons.Rounded.PlaylistPlay, contentDescription = null)
                        },
                        onClick = {
                            onAddToQueue(false)
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        leadingIcon = {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = null)
                        },
                        onClick = {
                            onAddToQueue(true)
                            showMenu = false
                        }
                    )
                }
            }
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
private fun EmptyLibrary(onRefresh: () -> Unit) {
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

            Button(onClick = onRefresh, modifier = Modifier.padding(top = 16.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Library")
            }
        }
    }
}

@Composable
private fun ScanningProgress(
    progress: Float,
    message: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                strokeWidth = 6.dp
            )
            
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            
            if (progress > 0) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun PermissionRequest(
    onRequestPermission: () -> Unit,
    showRationale: Boolean
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            )
            
            Text(
                text = "Permission Required",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            
            Text(
                text = if (showRationale) {
                    "Musify needs permission to access your music library. Without this permission, the app cannot play your local music files."
                } else {
                    "To play your music, Musify needs access to your device's music library."
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Grant Permission")
            }
            
            if (showRationale) {
                Text(
                    text = "If you denied the permission, you may need to enable it in your device settings.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
