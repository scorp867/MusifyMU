package com.musify.mu.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import com.musify.mu.data.media.LoadingState
import com.musify.mu.util.PermissionManager
import com.musify.mu.ui.navigation.Screen
import com.musify.mu.util.toMediaItem
import com.musify.mu.ui.components.AlphabeticalScrollBar
import com.musify.mu.ui.components.generateAlphabet
import com.musify.mu.ui.components.getFirstLetter
import com.musify.mu.playback.rememberQueueOperations
import com.musify.mu.playback.QueueContextHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.LazyListItemInfo
import com.musify.mu.playback.LocalMediaController
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.rememberDismissState
import androidx.compose.material.DismissValue
import androidx.compose.material.DismissDirection
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.material.ExperimentalMaterialApi
import kotlinx.coroutines.flow.map

enum class SortType {
    TITLE, ARTIST, ALBUM, DATE_ADDED
}

enum class QueueOperation {
    ADDING_TO_END, ADDING_TO_NEXT, REMOVING, COMPLETED_SUCCESS, COMPLETED_ERROR
}

@Composable
fun LibraryScreen(
    navController: NavController,
    onPlay: (List<Track>, Int) -> Unit,
    hasPermissions: Boolean = true
) {
    val context = LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    val haptic = LocalHapticFeedback.current
    val controller = LocalMediaController.current
    val queueOperationsManager = rememberQueueOperations()

    var allTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var visualSearchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // Visual state for add-to-queue operations
    var queueOperationsState by remember { mutableStateOf<Map<String, QueueOperation>>(emptyMap()) }
    var isProcessingQueueOps by remember { mutableStateOf(false) }

    // Debounced search with visual-only immediate feedback
    val tracks = if (searchQuery.isBlank()) allTracks else allTracks.filter { t ->
        t.title.contains(searchQuery, ignoreCase = true) ||
                t.artist.contains(searchQuery, ignoreCase = true) ||
                t.album.contains(searchQuery, ignoreCase = true)
    }

    // Visual tracks for immediate search feedback
    val visualTracks = if (visualSearchQuery.isBlank()) allTracks else allTracks.filter { t ->
        t.title.contains(visualSearchQuery, ignoreCase = true) ||
                t.artist.contains(visualSearchQuery, ignoreCase = true) ||
                t.album.contains(visualSearchQuery, ignoreCase = true)
    }

    // Debounced search to prevent excessive filtering
    LaunchedEffect(visualSearchQuery) {
        if (visualSearchQuery != searchQuery) {
            isSearching = true
            kotlinx.coroutines.delay(300) // 300ms debounce
            searchQuery = visualSearchQuery
            isSearching = false
        }
    }



    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Observe background loading progress and get data from cache
    LaunchedEffect(hasPermissions) {
        android.util.Log.d("LibraryScreen", "LaunchedEffect triggered - hasPermissions: $hasPermissions")
        if (hasPermissions) {
            // Ensure data manager is initialized
            coroutineScope.launch {
                try {
                    android.util.Log.d("LibraryScreen", "Ensuring data manager is initialized...")
                    repo.dataManager.ensureInitialized()
                    android.util.Log.d("LibraryScreen", "Data manager ready")
                } catch (e: Exception) {
                    android.util.Log.e("LibraryScreen", "Failed to initialize data manager", e)
                }
            }

            // Observe the cached tracks flow for real-time updates (this is the single source of truth)
            repo.dataManager.cachedTracks.collect { cachedTracks ->
                android.util.Log.d("LibraryScreen", "Cache updated: ${cachedTracks.size} tracks")
                allTracks = cachedTracks
                isLoading = false
            }
        } else {
            android.util.Log.d("LibraryScreen", "No permissions granted, clearing tracks")
            isLoading = false
            allTracks = emptyList()
        }
    }

    // Debug logging for tracks state
    LaunchedEffect(allTracks) {
        android.util.Log.d("LibraryScreen", "allTracks changed: ${allTracks.size} tracks")
        android.util.Log.d("LibraryScreen", "tracks computed: ${tracks.size} tracks")
        android.util.Log.d("LibraryScreen", "visualTracks computed: ${visualTracks.size} tracks")
        android.util.Log.d("LibraryScreen", "isLoading: $isLoading")

        // Log sample track details for debugging
        if (allTracks.isNotEmpty()) {
            val sampleTrack = allTracks.first()
            android.util.Log.d("LibraryScreen", "Sample track - Title: ${sampleTrack.title}, Artist: ${sampleTrack.artist}, Album: ${sampleTrack.album}, AlbumID: ${sampleTrack.albumId}")
        }
    }

    // No artwork prefetching needed - all artwork is extracted at app startup

    // Background gradient
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header with working search
        LibraryHeader(
            query = visualSearchQuery,
            onQueryChange = { visualSearchQuery = it },
            onClear = {
                visualSearchQuery = ""
                searchQuery = ""
            },
            isSearching = isSearching
        )

        when {
            !hasPermissions -> {
                PermissionDeniedState()
            }
            isLoading -> {
                LoadingLibrary()
            }
            tracks.isEmpty() -> {
                EmptyLibrary()
            }
            else -> {
                // Track list with alphabetical scroll bar
                Box(modifier = Modifier.fillMaxSize()) {


                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 44.dp, top = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // Performance optimizations for smooth scrolling
                        flingBehavior = rememberSnapFlingBehavior(listState)
                    ) {
                        itemsIndexed(visualTracks, key = { index, track -> "library_${index}_${track.mediaId}" }) { index, track ->

                            // Improved track item without aggressive swipe gestures
                            TrackItem(
                                track = track,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPlay(visualTracks, index)
                                },
                                queueOperation = queueOperationsState[track.mediaId],
                                onAddToQueue = { addToEnd ->
                                    // Visual-only queue operation - immediate UI feedback
                                    val operation = if (addToEnd) QueueOperation.ADDING_TO_END else QueueOperation.ADDING_TO_NEXT
                                    queueOperationsState = queueOperationsState + (track.mediaId to operation)

                                    // Perform actual queue operation in background
                                    coroutineScope.launch {
                                        try {
                                            // Create library context for the operation
                                            val context = QueueContextHelper.createSearchContext("library")

                                            if (addToEnd) {
                                                // Add to User Queue (Add to next segment)
                                                queueOperationsManager.addToUserQueueWithContext(
                                                    items = listOf(track.toMediaItem()),
                                                    context = context
                                                )
                                            } else {
                                                // Add to Priority Queue (Play Next)
                                                queueOperationsManager.playNextWithContext(
                                                    items = listOf(track.toMediaItem()),
                                                    context = context
                                                )
                                            }

                                            // Success feedback
                                            queueOperationsState = queueOperationsState + (track.mediaId to QueueOperation.COMPLETED_SUCCESS)
                                            delay(2000) // Show success for 2 seconds
                                            queueOperationsState = queueOperationsState - track.mediaId

                                        } catch (e: Exception) {
                                            // Error feedback
                                            queueOperationsState = queueOperationsState + (track.mediaId to QueueOperation.COMPLETED_ERROR)
                                            delay(3000) // Show error longer
                                            queueOperationsState = queueOperationsState - track.mediaId
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Alphabetical scroll bar
                    // Precompute first-letter index map for O(1) jumps with better performance
                    val indexMap by remember(visualTracks) {
                        derivedStateOf {
                            val map = mutableMapOf<String, Int>()
                            visualTracks.forEachIndexed { i, t ->
                                val l = getFirstLetter(t.title)
                                if (map[l] == null) map[l] = i
                            }
                            map
                        }
                    }

                    AlphabeticalScrollBar(
                        letters = generateAlphabet(),
                        onLetterSelected = { letter ->
                            val targetIndex = indexMap[letter] ?: -1
                            if (targetIndex >= 0) {
                                // Use immediate scrolling for instant response
                                coroutineScope.launch {
                                    // Use scrollToItem for instant jump without animation
                                    listState.scrollToItem(
                                        index = targetIndex,
                                        scrollOffset = 0
                                    )
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        isVisible = visualTracks.size > 10 // Show for even smaller lists
                    )
                }
            }
        }
    }
    // Prefetch artwork for items around the viewport
    LaunchedEffect(listState, visualTracks) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.toList() }
            .distinctUntilChanged()
            .collectLatest { visibleItems: List<LazyListItemInfo> ->
                if (visibleItems.isEmpty()) return@collectLatest
                val start = (visibleItems.minOf { it.index } - 5).coerceAtLeast(0)
                val end = (visibleItems.maxOf { it.index } + 5).coerceAtMost(visualTracks.lastIndex)
                if (start <= end && visualTracks.isNotEmpty()) {
                    val prefetchUris: List<String> = visualTracks.subList(start, end + 1).map { it.mediaId }
                    com.musify.mu.util.OptimizedArtworkLoader.prefetch(prefetchUris)
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    isSearching: Boolean = false
) {

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
                value = query,
                onValueChange = onQueryChange,
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

            if (query.isNotEmpty()) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(
                        onClick = onClear,
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
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun TrackItem(
    track: Track,
    onClick: () -> Unit,
    onAddToQueue: (Boolean) -> Unit,
    queueOperation: QueueOperation? = null
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    // Visual feedback colors based on queue operation
    val containerColor by animateColorAsState(
        targetValue = when (queueOperation) {
            QueueOperation.ADDING_TO_END, QueueOperation.ADDING_TO_NEXT ->
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            QueueOperation.COMPLETED_SUCCESS ->
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            QueueOperation.COMPLETED_ERROR ->
                MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        },
        animationSpec = tween(300),
        label = "containerColor"
    )

    com.musify.mu.ui.components.EnhancedSwipeableItem(
        onSwipeRight = { onAddToQueue(false) }, // Play Next
        onSwipeLeft = { onAddToQueue(true) },   // Add to Queue
        isInQueue = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable { onClick() },
            colors = CardDefaults.cardColors(
                containerColor = containerColor
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
                    com.musify.mu.ui.components.SmartArtwork(
                        artworkUri = track.artUri, // Use pre-extracted artwork from database
                        mediaUri = track.mediaId,
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

                // Queue operation visual indicator
                AnimatedVisibility(
                    visible = queueOperation != null,
                    enter = slideInHorizontally(animationSpec = tween(200)) + fadeIn(),
                    exit = slideOutHorizontally(animationSpec = tween(200)) + fadeOut()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        when (queueOperation) {
                            QueueOperation.ADDING_TO_END, QueueOperation.ADDING_TO_NEXT -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (queueOperation == QueueOperation.ADDING_TO_END) "Adding..." else "Adding next...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            QueueOperation.COMPLETED_SUCCESS -> {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Added successfully",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Added",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            QueueOperation.COMPLETED_ERROR -> {
                                Icon(
                                    imageVector = Icons.Rounded.Error,
                                    contentDescription = "Failed to add",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Failed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            else -> {}
                        }
                    }
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

@Composable
private fun PermissionDeniedState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicOff,
                contentDescription = "No permissions",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )

            Text(
                text = "Music Access Required",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Text(
                text = "Please grant media permissions to access your music library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}