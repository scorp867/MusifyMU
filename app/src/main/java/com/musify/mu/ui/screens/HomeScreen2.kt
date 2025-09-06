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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.db.entities.Playlist
import com.musify.mu.ui.components.*
import com.musify.mu.ui.navigation.Screen
import com.musify.mu.ui.viewmodels.HomeViewModel
import com.musify.mu.ui.viewmodels.ArtworkViewModel
import com.musify.mu.ui.viewmodels.PlaybackViewModel
import com.musify.mu.playback.LocalMediaController
import com.musify.mu.playback.LocalPlaybackMediaId
import com.musify.mu.playback.LocalIsPlaying
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen2(
    navController: NavController,
    onPlay: (List<Track>, Int) -> Unit
) {
    // ViewModels
    val homeViewModel: HomeViewModel = hiltViewModel()
    val artworkViewModel: ArtworkViewModel = hiltViewModel()
    val playbackViewModel: PlaybackViewModel = hiltViewModel()
    
    // State from ViewModels
    val recentlyPlayed by homeViewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val recentlyAdded by homeViewModel.recentlyAdded.collectAsStateWithLifecycle()
    val favorites by homeViewModel.favorites.collectAsStateWithLifecycle()
    val playlists by homeViewModel.playlists.collectAsStateWithLifecycle()
    val isLoading by homeViewModel.isLoading.collectAsStateWithLifecycle()
    val error by homeViewModel.error.collectAsStateWithLifecycle()
    val allTracks by homeViewModel.allTracks.collectAsStateWithLifecycle()
    val searchQuery by homeViewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by homeViewModel.searchResults.collectAsStateWithLifecycle()
    
    // UI state
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    var showSearchSheet by remember { mutableStateOf(false) }
    var showSongEditor by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    // Section tabs
    var selectedSection by rememberSaveable { mutableStateOf(0) }
    var listsDisplayMode by rememberSaveable { mutableStateOf("carousel") }
    
    // Theme manager for custom layout
    val context = LocalContext.current
    val themeManager = remember { com.musify.mu.ui.theme.AppThemeManager.getInstance(context) }
    val customLayoutEnabled = themeManager.customLayoutEnabled
    val homeLayoutOrder by remember { mutableStateOf(themeManager.homeLayoutConfigState) }
    
    // Prefetch artwork for visible tracks
    LaunchedEffect(listState.firstVisibleItemIndex, allTracks) {
        if (allTracks.isNotEmpty()) {
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
            
            artworkViewModel.prefetchForVisibleRange(
                allTrackUris = allTracks.map { it.mediaId },
                firstVisibleIndex = firstVisible,
                lastVisibleIndex = lastVisible
            )
        }
    }
    
    // Listen for playback changes to update recently played
    val currentTrack by playbackViewModel.currentTrack.collectAsStateWithLifecycle()
    LaunchedEffect(currentTrack) {
        if (currentTrack != null) {
            homeViewModel.refreshData()
        }
    }
    
    // Error handling
    error?.let { errorMessage ->
        LaunchedEffect(errorMessage) {
            // Show snackbar or handle error
            homeViewModel.clearError()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    )
                )
            )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Welcome header
            item {
                WelcomeHeader(onSearchClick = { showSearchSheet = true })
            }
            
            // Section tabs
            stickyHeader {
                SectionTabs(
                    selectedSection = selectedSection,
                    onSectionSelected = { selectedSection = it },
                    listsDisplayMode = listsDisplayMode,
                    onListsDisplayModeChanged = { listsDisplayMode = it }
                )
            }
            
            // Content based on selected section
            when (selectedSection) {
                0 -> { // Lists
                    if (isLoading) {
                        items(3) { ShimmerCarousel() }
                    } else {
                        if (listsDisplayMode == "carousel") {
                            renderCarouselLists(
                                homeLayoutOrder = homeLayoutOrder.value,
                                customLayoutEnabled = customLayoutEnabled,
                                recentlyPlayed = recentlyPlayed,
                                recentlyAdded = recentlyAdded,
                                favorites = favorites,
                                playlists = playlists,
                                onPlay = onPlay,
                                onSeeAll = { type -> navController.navigate("see_all/$type") },
                                onClearRecentlyPlayed = { homeViewModel.clearRecentlyPlayed() },
                                onClearRecentlyAdded = { homeViewModel.clearRecentlyAdded() },
                                onPlaylistClick = { playlist ->
                                    navController.navigate(Screen.PlaylistDetails.route.replace("{id}", playlist.id.toString()))
                                },
                                onCreatePlaylistClick = { showCreatePlaylistDialog = true },
                                haptic = haptic
                            )
                        } else {
                            renderListMode(
                                recentlyPlayed = recentlyPlayed,
                                recentlyAdded = recentlyAdded,
                                favorites = favorites,
                                playlists = playlists,
                                navController = navController
                            )
                        }
                    }
                }
                1 -> { // Songs
                    items(allTracks.size, key = { idx -> "song_${allTracks[idx].mediaId}" }) { idx ->
                        val track = allTracks[idx]
                        val isPlaying = LocalPlaybackMediaId.current == track.mediaId && LocalIsPlaying.current
                        
                        CompactTrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            artData = track.artUri,
                            mediaUri = track.mediaId,
                            contentDescription = track.title,
                            isPlaying = isPlaying,
                            showIndicator = (LocalPlaybackMediaId.current == track.mediaId),
                            onClick = { onPlay(allTracks, idx) },
                            onLongClick = {
                                editingTrack = track
                                showSongEditor = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                2 -> { // Artists
                    val artists = remember(allTracks) {
                        allTracks
                            .groupBy { it.artist.ifBlank { "Unknown Artist" } }
                            .map { (name, tracks) -> name to tracks.size }
                            .sortedBy { it.first.lowercase() }
                    }
                    
                    items(artists.size, key = { i -> "artist_${artists[i].first}" }) { idx ->
                        val (name, count) = artists[idx]
                        Card(
                            modifier = Modifier.clickable {
                                val encoded = java.net.URLEncoder.encode(name, "UTF-8")
                                navController.navigate("artist_details/$encoded")
                            }
                        ) {
                            ListItem(
                                headlineContent = { Text(name) },
                                supportingContent = { Text("$count songs") }
                            )
                        }
                    }
                }
                3 -> { // Albums
                    val albums = remember(allTracks) {
                        allTracks
                            .filter { it.albumId != null }
                            .distinctBy { "${it.album}_${it.artist}" }
                            .map { track ->
                                Triple(
                                    track.albumId!!,
                                    track.album,
                                    track.artist
                                )
                            }
                            .sortedBy { it.second.lowercase() }
                    }
                    
                    items(albums.size, key = { i -> "album_${albums[i].first}" }) { idx ->
                        val (albumId, albumName, artistName) = albums[idx]
                        Card(
                            modifier = Modifier.clickable {
                                val albumEnc = java.net.URLEncoder.encode(albumName, "UTF-8")
                                val artistEnc = java.net.URLEncoder.encode(artistName, "UTF-8")
                                navController.navigate("album_details/$albumEnc/$artistEnc")
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumArtwork(
                                    albumId = albumId,
                                    contentDescription = albumName,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = albumName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = artistName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Alphabetical scroll bar for Songs/Artists/Albums
        if (selectedSection in 1..3) {
            AlphabeticalScrollBar(
                letters = generateAlphabet(),
                onLetterSelected = { letter ->
                    // Find first item starting with letter
                    val targetIndex = when (selectedSection) {
                        1 -> allTracks.indexOfFirst { getFirstLetter(it.title) == letter }
                        2 -> allTracks.map { it.artist }.distinct().sorted()
                            .indexOfFirst { getFirstLetter(it) == letter }
                        3 -> allTracks.filter { it.albumId != null }
                            .distinctBy { "${it.album}_${it.artist}" }
                            .sortedBy { it.album.lowercase() }
                            .indexOfFirst { getFirstLetter(it.album) == letter }
                        else -> -1
                    }
                    
                    if (targetIndex >= 0) {
                        scope.launch {
                            listState.scrollToItem(targetIndex + 2) // +2 for header items
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
                isVisible = listState.isScrollInProgress,
                barWidth = 20.dp,
                innerWidth = 18.dp,
                letterBoxSize = 12.dp
            )
        }
    }
    
    // Search sheet
    if (showSearchSheet) {
        SearchBottomSheet(
            isVisible = showSearchSheet,
            onDismiss = { showSearchSheet = false },
            searchQuery = searchQuery,
            onSearchQueryChange = { homeViewModel.setSearchQuery(it) },
            searchResults = searchResults,
            allTracks = allTracks,
            onTrackClick = { track ->
                val index = searchResults.indexOf(track)
                if (index >= 0) {
                    onPlay(searchResults, index)
                }
                showSearchSheet = false
            }
        )
    }
    
    // Song editor dialog
    if (showSongEditor && editingTrack != null) {
        SongDetailsEditor(
            track = editingTrack!!,
            onDismiss = {
                showSongEditor = false
                editingTrack = null
            },
            onSave = { updatedTrack ->
                // TODO: Implement track metadata update
                showSongEditor = false
                editingTrack = null
            }
        )
    }
    
    // Playlist creation dialog
    PlaylistCreationDialog(
        isVisible = showCreatePlaylistDialog,
        onDismiss = { showCreatePlaylistDialog = false },
        onCreatePlaylist = { name, imageUri ->
            scope.launch {
                // Create playlist through repository
                showCreatePlaylistDialog = false
                homeViewModel.refreshData()
            }
        }
    )
}

@Composable
private fun LazyListScope.renderCarouselLists(
    homeLayoutOrder: List<String>,
    customLayoutEnabled: Boolean,
    recentlyPlayed: List<Track>,
    recentlyAdded: List<Track>,
    favorites: List<Track>,
    playlists: List<Playlist>,
    onPlay: (List<Track>, Int) -> Unit,
    onSeeAll: (String) -> Unit,
    onClearRecentlyPlayed: () -> Unit,
    onClearRecentlyAdded: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    val listsOrder = if (customLayoutEnabled) homeLayoutOrder else 
        listOf("recentlyPlayed", "recentlyAdded", "favorites", "playlists")
    
    listsOrder.forEach { sectionKey ->
        when (sectionKey) {
            "recentlyPlayed" -> {
                if (recentlyPlayed.isNotEmpty()) {
                    item {
                        AnimatedCarousel(
                            title = "Recently Played",
                            icon = Icons.Rounded.History,
                            data = recentlyPlayed,
                            onPlay = onPlay,
                            haptic = haptic,
                            onSeeAll = { onSeeAll("recently_played") },
                            onClear = onClearRecentlyPlayed
                        )
                    }
                }
            }
            "recentlyAdded" -> {
                if (recentlyAdded.isNotEmpty()) {
                    item {
                        AnimatedCarousel(
                            title = "Recently Added",
                            icon = Icons.Rounded.NewReleases,
                            data = recentlyAdded,
                            onPlay = onPlay,
                            haptic = haptic,
                            onSeeAll = { onSeeAll("recently_added") },
                            onClear = onClearRecentlyAdded
                        )
                    }
                }
            }
            "favorites" -> {
                if (favorites.isNotEmpty()) {
                    item {
                        AnimatedCarousel(
                            title = "Favourites",
                            icon = Icons.Rounded.Favorite,
                            data = favorites,
                            onPlay = onPlay,
                            haptic = haptic,
                            onSeeAll = { onSeeAll("favorites") },
                            onClear = {}
                        )
                    }
                }
            }
            "playlists" -> {
                item {
                    PlaylistCarousel(
                        playlists = playlists,
                        onPlaylistClick = onPlaylistClick,
                        onCreatePlaylistClick = onCreatePlaylistClick
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyListScope.renderListMode(
    recentlyPlayed: List<Track>,
    recentlyAdded: List<Track>,
    favorites: List<Track>,
    playlists: List<Playlist>,
    navController: NavController
) {
    val sections = listOf(
        Triple("Recently Played", Icons.Rounded.History, recentlyPlayed.size),
        Triple("Recently Added", Icons.Rounded.NewReleases, recentlyAdded.size),
        Triple("Favourites", Icons.Rounded.Favorite, favorites.size),
        @Suppress("DEPRECATION")
        Triple("Playlists", Icons.Rounded.QueueMusic, playlists.size)
    )
    
    sections.forEach { (title, icon, count) ->
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val route = when (title) {
                            "Recently Played" -> "see_all/recently_played"
                            "Recently Added" -> "see_all/recently_added"
                            "Favourites" -> "see_all/favorites"
                            "Playlists" -> "see_all/playlists"
                            else -> ""
                        }
                        if (route.isNotEmpty()) {
                            navController.navigate(route)
                        }
                    },
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = {
                        Text("$count ${when (title) {
                            "Playlists" -> if (count == 1) "playlist" else "playlists"
                            else -> if (count == 1) "song" else "songs"
                        }}")
                    },
                    leadingContent = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Navigate"
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionTabs(
    selectedSection: Int,
    onSectionSelected: (Int) -> Unit,
    listsDisplayMode: String,
    onListsDisplayModeChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val tabs = listOf("LISTS", "SONGS", "ARTISTS", "ALBUMS")
        TabRow(
            selectedTabIndex = selectedSection,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ) {
            tabs.forEachIndexed { index, label ->
                if (index == 0) { // LISTS tab with dropdown
                    ListsTabWithDropdown(
                        selected = selectedSection == index,
                        onClick = { onSectionSelected(index) },
                        label = label,
                        displayMode = listsDisplayMode,
                        onDisplayModeChanged = onListsDisplayModeChanged
                    )
                } else {
                    Tab(
                        selected = selectedSection == index,
                        onClick = { onSectionSelected(index) },
                        text = { Text(label) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ListsTabWithDropdown(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    displayMode: String,
    onDisplayModeChanged: (String) -> Unit
) {
    var showDropdown by remember { mutableStateOf(false) }
    
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(label)
                Box {
                    IconButton(
                        onClick = { showDropdown = true },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = "Lists mode options",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showDropdown,
                        onDismissRequest = { showDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Carousel Mode") },
                            onClick = {
                                onDisplayModeChanged("carousel")
                                showDropdown = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.ViewCarousel,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (displayMode == "carousel") {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("List Mode") },
                            onClick = {
                                onDisplayModeChanged("list")
                                showDropdown = false
                            },
                            leadingIcon = {
                                @Suppress("DEPRECATION")
                                Icon(
                                    imageVector = Icons.Rounded.List,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (displayMode == "list") {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    )
}

// ... Rest of the helper composables (WelcomeHeader, AnimatedCarousel, etc.) remain the same