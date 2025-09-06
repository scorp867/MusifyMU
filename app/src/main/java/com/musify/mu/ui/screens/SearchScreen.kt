package com.musify.mu.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.ui.navigation.Screen
import com.musify.mu.ui.viewmodels.SearchViewModel

import kotlinx.coroutines.launch
import com.musify.mu.playback.rememberQueueOperations
import com.musify.mu.playback.QueueContextHelper
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun SearchScreen(
    navController: NavController,
    onPlay: (List<Track>, Int) -> Unit
) {
    val viewModel: SearchViewModel = hiltViewModel()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()

    var currentQuery by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }

    // Background gradient
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        )
    )

    // Debounced search function

    // Auto-focus search field when screen opens
    LaunchedEffect(Unit) {
        delay(100) // Small delay to ensure UI is ready
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Search Music",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundGradient)
                .padding(paddingValues)
        ) {
            // Search bar with enhanced styling
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                OutlinedTextField(
                    value = currentQuery,
                    onValueChange = { newQuery ->
                        currentQuery = newQuery
                        viewModel.search(newQuery)
                    },
                    label = { Text("Search songs, artists, albums...") },
                    placeholder = { Text("What do you want to listen to?") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Row {
                            if (isSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (currentQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        currentQuery = ""
                                        viewModel.clearSearch()
                                        hasSearched = false
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = "Clear search"
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .focusRequester(focusRequester),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
            }

            // Search results
            AnimatedContent(
                targetState = when {
                    isSearching -> "loading"
                    results.isNotEmpty() -> "results"
                    hasSearched && results.isEmpty() -> "no_results"
                    else -> "empty"
                },
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) with fadeOut(animationSpec = tween(300))
                },
                label = "search_content"
            ) { state ->
                when (state) {
                    "loading" -> {
                        SearchLoadingState()
                    }
                    "results" -> {
                        SearchResults(
                            results = results,
                            onPlay = onPlay,
                            navController = navController,
                            lazyListState = lazyListState
                        )
                    }
                    "no_results" -> {
                        SearchEmptyState(
                            query = currentQuery,
                            isNoResults = true
                        )
                    }
                    else -> {
                        SearchEmptyState(
                            query = "",
                            isNoResults = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    results: List<Track>,
    onPlay: (List<Track>, Int) -> Unit,
    navController: NavController,
    lazyListState: androidx.compose.foundation.lazy.LazyListState
) {
    Column {
        // Results header
        Text(
            text = "${results.size} ${if (results.size == 1) "result" else "results"} found",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(results) { track ->
                val index = results.indexOf(track)
                SearchResultItem(
                    track = track,
                    onPlay = {
                        onPlay(results, index)
                        navController.navigate(Screen.NowPlaying.route)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun SearchResultItem(
    track: Track,
    onPlay: () -> Unit
) {
    val queueOps = rememberQueueOperations()
    val scope = rememberCoroutineScope()
    
    com.musify.mu.ui.components.EnhancedSwipeableItem(
        onSwipeRight = {
            // Swipe right: Play Next (priority queue)
            val ctx = QueueContextHelper.createSearchContext("search")
            scope.launch { queueOps.playNextWithContext(items = listOf(track.toMediaItem()), context = ctx) }
        },
        onSwipeLeft = {
            // Swipe left: Add to User Queue
            val ctx = QueueContextHelper.createSearchContext("search")
            scope.launch { queueOps.addToUserQueueWithContext(items = listOf(track.toMediaItem()), context = ctx) }
        },
        isInQueue = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlay() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album artwork
            AsyncImage(
                model = track.artUri,
                contentDescription = track.title,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            )

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

            // Play button
            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
        }

}

@Composable
private fun SearchLoadingState() {
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
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 3.dp
            )
            Text(
                text = "Searching your music...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SearchEmptyState(
    query: String,
    isNoResults: Boolean
) {
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
                imageVector = if (isNoResults) Icons.Rounded.SearchOff else Icons.Rounded.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )

            Text(
                text = if (isNoResults) {
                    "No results for \"$query\""
                } else {
                    "Search your music library"
                },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            Text(
                text = if (isNoResults) {
                    "Try searching with different keywords"
                } else {
                    "Find songs, artists, and albums"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
