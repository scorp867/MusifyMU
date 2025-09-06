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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.musify.mu.util.toMediaItem
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import androidx.hilt.navigation.compose.hiltViewModel
import com.musify.mu.ui.viewmodels.HomeViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musify.mu.ui.components.SpotifyStyleArtwork
import com.musify.mu.ui.components.TrackArtwork
import com.musify.mu.ui.components.AlbumArtwork
import com.musify.mu.ui.navigation.Screen
import com.musify.mu.playback.LocalMediaController
import com.musify.mu.data.db.entities.Playlist
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.musify.mu.ui.components.AlphabeticalScrollBar
import com.musify.mu.ui.components.generateAlphabet
import com.musify.mu.ui.components.getFirstLetter
import com.musify.mu.ui.components.EnhancedSwipeableItem
import com.musify.mu.ui.components.SongDetailsEditor
import com.musify.mu.playback.QueueContextHelper
import com.musify.mu.playback.rememberQueueOperations
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.derivedStateOf
import com.musify.mu.ui.helpers.MediaControllerListener

// Composition local to provide scroll state to child components
val LocalScrollState = compositionLocalOf<LazyListState?> { null }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, onPlay: (List<Track>, Int) -> Unit) {
    val viewModel: HomeViewModel = hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    val haptic = LocalHapticFeedback.current
    val controller = LocalMediaController.current

    val recentPlayed by viewModel.recentlyPlayed.collectAsState()
    val recentAdded by viewModel.recentlyAdded.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    var customPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    var showSearchSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSongEditor by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cachedTracks by repo.dataManager.tracks.collectAsStateWithLifecycle(initialValue = emptyList())

    val listState = rememberLazyListState()

    // All tracks from new LocalFilesService - no paging needed
    val allTracks by remember(cachedTracks) {
        derivedStateOf { cachedTracks.sortedBy { it.title.lowercase() } }
    }

    // Prefetch artwork for visible tracks to reduce loading delays
    LaunchedEffect(allTracks) {
        if (allTracks.isNotEmpty()) {
            val visibleTrackUris = allTracks
                .take(50)
                .filter { it.artUri.isNullOrBlank() || it.artUri?.startsWith("content://media/external/audio/albumart") == true }
                .map { it.mediaId }
            if (visibleTrackUris.isNotEmpty()) {
                repo.dataManager.prefetchArtwork(visibleTrackUris)
            }
        }
    }

    // Function to refresh data with IO dispatcher
    val refreshData = {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val customPlaylistsData = repo.playlists()

                    withContext(Dispatchers.Main) {
                        customPlaylists = customPlaylistsData
                        android.util.Log.d("HomeScreen", "Playlists loaded: ${customPlaylists.size}")
                        // stop showing shimmer after first refresh
                        isLoading = false
                    }
                } catch (e: Exception) {
                    android.util.Log.w("HomeScreen", "Failed to refresh data", e)
                }
            }
        }
    }

    // Listen for changes in cached tracks and refresh home screen data
    LaunchedEffect(Unit) {
        repo.dataManager.tracks.collectLatest { tracks ->
            android.util.Log.d("HomeScreen", "Cached tracks changed: ${tracks.size} tracks, refreshing home data")
            refreshData()
        }
    }

    // Refresh data when trigger changes
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            refreshData()
        }
    }

    // Dynamically update recently played when the controller transitions
    MediaControllerListener(
        controller = controller,
        onMediaItemTransition = { _, _ ->
                // Recent played is now handled by the viewModel
        }
    )

    // Removed redundant delayed refresh to avoid duplicate loads

    // Create gradient background
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
        )
    )

    // Section tabs below welcome header
    var selectedSection by rememberSaveable { mutableStateOf(0) } // 0=LISTS, 1=SONGS, 2=ARTISTS, 3=ALBUMS
    var listsDisplayMode by rememberSaveable { mutableStateOf("carousel") } // "list" or "carousel"

    // Keep state on Home reselect (no reset per request)
    val themeManager = remember { com.musify.mu.ui.theme.AppThemeManager.getInstance(context) }
    val customLayoutEnabled = themeManager.customLayoutEnabled
    val homeLayoutOrder by remember { mutableStateOf(themeManager.homeLayoutConfigState) }

    // Derived data for section lists - optimized to prevent unnecessary recompositions
    val tracksFiltered by remember(cachedTracks) {
        derivedStateOf { cachedTracks }
    }
    val artistsAll by remember(cachedTracks) {
        derivedStateOf {
            cachedTracks
                .groupBy { it.artist.ifBlank { "Unknown Artist" } }
                .map { (name, tracks) -> name to tracks.size }
                .sortedBy { it.first.lowercase() }
        }
    }
    val artistsFiltered by remember(artistsAll) {
        derivedStateOf { artistsAll }
    }
    val albumsAll by remember(cachedTracks) {
        derivedStateOf { repo.dataManager.getUniqueAlbums() }
    }
    val albumsFiltered by remember(albumsAll) {
        derivedStateOf { albumsAll }
    }



    Box(modifier = Modifier.fillMaxSize().background(backgroundGradient)) {
        val endPadding = 16.dp

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = endPadding, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Performance optimizations
            userScrollEnabled = true
        ) {
            // Welcome header with animation (settings button removed per new design)
            item {
                WelcomeHeader(onSearchClick = { showSearchSheet = true })
            }

            // Top section tabs (sticky at top)
            stickyHeader {
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
                                var showDropdown by remember { mutableStateOf(false) }
                                Tab(
                                    selected = selectedSection == index,
                                    onClick = { selectedSection = index },
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
                                                            listsDisplayMode = "carousel"
                                                            showDropdown = false
                                                        },
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = Icons.Rounded.ViewCarousel,
                                                                contentDescription = null
                                                            )
                                                        },
                                                        trailingIcon = {
                                                            if (listsDisplayMode == "carousel") {
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
                                                            listsDisplayMode = "list"
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
                                                            if (listsDisplayMode == "list") {
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
                            } else {
                                Tab(
                                    selected = selectedSection == index,
                                    onClick = { selectedSection = index },
                                    text = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            when (selectedSection) {
                0 -> {
                    if (isLoading) {
                        items(3) { ShimmerCarousel() }
                    } else {
                        if (listsDisplayMode == "carousel") {
                            // Original carousel mode - show all sections with carousels
                            val listsOrder = if (customLayoutEnabled) homeLayoutOrder.value else listOf("welcome","recentlyPlayed","recentlyAdded","favorites","playlists")
                            listsOrder.forEach { sectionKey ->
                                when (sectionKey) {
                                    "welcome" -> item { /* header already shown above; skip to avoid duplicate */ }
                                    "recentlyPlayed" -> item {
                                        if (recentPlayed.isNotEmpty()) {
                                            AnimatedCarousel(
                                                title = "Recently Played",
                                                icon = Icons.Rounded.History,
                                                data = recentPlayed,
                                                onPlay = { tracks, index -> onPlay(tracks, index); scope.launch { kotlinx.coroutines.delay(500); refreshTrigger++ } },
                                                haptic = haptic,
                                                onSeeAll = { navController.navigate("see_all/recently_played") },
                                                onClear = {
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            repo.clearRecentlyPlayed()
                                                            withContext(Dispatchers.Main) {
                                                                refreshTrigger++
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    "recentlyAdded" -> item {
                                        if (recentAdded.isNotEmpty()) {
                                            AnimatedCarousel(
                                                title = "Recently Added",
                                                icon = Icons.Rounded.NewReleases,
                                                data = recentAdded,
                                                onPlay = { tracks, index -> onPlay(tracks, index); scope.launch { kotlinx.coroutines.delay(500); refreshTrigger++ } },
                                                haptic = haptic,
                                                onSeeAll = { navController.navigate("see_all/recently_added") },
                                                onClear = {
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            repo.clearRecentlyAdded()
                                                            withContext(Dispatchers.Main) {
                                                                refreshTrigger++
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    "favorites" -> item {
                                        if (favorites.isNotEmpty()) {
                                            AnimatedCarousel(
                                                title = "Favourites",
                                                icon = Icons.Rounded.Favorite,
                                                data = favorites,
                                                onPlay = { tracks, index -> onPlay(tracks, index); scope.launch { kotlinx.coroutines.delay(500); refreshTrigger++ } },
                                                haptic = haptic,
                                                onSeeAll = { navController.navigate("see_all/favorites") },
                                                onClear = { /* Favorites don't have a clear function */ }
                                            )
                                        }
                                    }
                                    "playlists" -> item {
                                        CustomPlaylistsCarousel(
                                            playlists = customPlaylists,
                                            navController = navController,
                                            haptic = haptic,
                                            onRefresh = { refreshTrigger++ }
                                        )
                                    }
                                }
                            }
                        } else {
                            // List mode - show section names only
                            val sections = listOf(
                                Triple("Recently Played", Icons.Rounded.History, recentPlayed.size),
                                Triple("Recently Added", Icons.Rounded.NewReleases, recentAdded.size),
                                Triple("Favourites", Icons.Rounded.Favorite, favorites.size),
                                @Suppress("DEPRECATION")
                                Triple("Playlists", Icons.Rounded.QueueMusic, customPlaylists.size)
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
                    }
                }
                1 -> {
                    // SONGS section using new LocalFilesService - no paging needed
                    items(allTracks.size, key = { idx -> "song_${allTracks[idx].mediaId}" }) { idx ->
                        val t = allTracks[idx]
                        val isPlaying = com.musify.mu.playback.LocalPlaybackMediaId.current == t.mediaId && com.musify.mu.playback.LocalIsPlaying.current

                        com.musify.mu.ui.components.CompactTrackRow(
                            title = t.title,
                            subtitle = t.artist,
                            artData = t.artUri,
                            mediaUri = t.mediaId,
                            contentDescription = t.title,
                            isPlaying = isPlaying,
                            showIndicator = (com.musify.mu.playback.LocalPlaybackMediaId.current == t.mediaId),
                            onClick = { onPlay(allTracks, idx) },
                            onLongClick = {
                                editingTrack = t
                                showSongEditor = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                2 -> {
                    // ARTISTS section
                    items(artistsFiltered.size, key = { i -> "artist_${artistsFiltered[i].first}" }) { idx ->
                        val (name, count) = artistsFiltered[idx]
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
                3 -> {
                    // ALBUMS section
                    items(albumsFiltered.size, key = { i -> "album_${albumsFiltered[i].albumId}_${albumsFiltered[i].albumName}" }) { idx ->
                        val a = albumsFiltered[idx]
                        Card(
                            modifier = Modifier.clickable {
                                val albumEnc = java.net.URLEncoder.encode(a.albumName, "UTF-8")
                                val artistEnc = java.net.URLEncoder.encode(a.artistName, "UTF-8")
                                navController.navigate("album_details/$albumEnc/$artistEnc")
                            }
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.musify.mu.ui.components.AlbumArtwork(
                                    albumId = a.albumId,
                                    contentDescription = a.albumName,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.albumName, style = MaterialTheme.typography.titleMedium)
                                    Text(a.artistName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                                Text("${a.trackCount}")
                            }
                        }
                    }
                }
            }
        }

        if (showSearchSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSearchSheet = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Tabs for Songs / Artists / Albums
                    var searchTab by remember { mutableStateOf(0) }
                    // Load search history
                    val searchHistory = remember(refreshTrigger) { repo.getSearchHistory() }
                    TabRow(selectedTabIndex = searchTab) {
                        Tab(selected = searchTab == 0, onClick = { searchTab = 0 }, text = { Text("Songs") })
                        Tab(selected = searchTab == 1, onClick = { searchTab = 1 }, text = { Text("Artists") })
                        Tab(selected = searchTab == 2, onClick = { searchTab = 2 }, text = { Text("Albums") })
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search songs, artists, albums") },
                        singleLine = true
                    )
                    // Save to history when user picks a result
                    val commitQuery: (String) -> Unit = { q ->
                        if (q.isNotBlank()) repo.addSearchHistory(q)
                    }

                    // When query is blank, only show search history (no recent results)
                    if (searchQuery.isBlank() && searchHistory.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Search history", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(searchHistory.size) { i ->
                                AssistChip(onClick = { searchQuery = searchHistory[i] }, label = { Text(searchHistory[i]) })
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { repo.clearSearchHistory(); refreshTrigger++ }) { Text("Clear history") }
                        }
                    }

                    when (searchTab) {
                        0 -> { // Songs
                            val results = remember(searchQuery, cachedTracks) {
                                if (searchQuery.isBlank()) emptyList() else cachedTracks.filter { t ->
                                    t.title.contains(searchQuery, true) ||
                                            t.artist.contains(searchQuery, true) ||
                                            t.album.contains(searchQuery, true)
                                }
                            }
                            if (results.isEmpty()) {
                                Text(
                                    text = if (searchQuery.isBlank()) "" else "No results",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            } else {
                                Spacer(Modifier.height(8.dp))
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(results.size, key = { i -> "search_${results[i].mediaId}" }) { idx ->
                                        val t = results[idx]
                                        ListItem(
                                            headlineContent = { Text(t.title) },
                                            supportingContent = { Text(t.artist) },
                                            leadingContent = {
                                                TrackArtwork(
                                                    trackUri = t.mediaId,
                                                    contentDescription = t.title,
                                                    modifier = Modifier.size(40.dp),
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                            },
                                            modifier = Modifier.clickable {
                                                commitQuery(searchQuery.ifBlank { t.title })
                                                onPlay(results, idx)
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        1 -> { // Artists
                            val results = remember(searchQuery, artistsAll) {
                                if (searchQuery.isBlank()) emptyList() else artistsAll.filter { it.first.contains(searchQuery, true) }
                            }
                            if (results.isEmpty()) {
                                Text(
                                    text = if (searchQuery.isBlank()) "" else "No results",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            } else {
                                Spacer(Modifier.height(8.dp))
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(results.size, key = { i -> "artist_res_${results[i].first}" }) { idx ->
                                        val (name, count) = results[idx]
                                        ListItem(
                                            headlineContent = { Text(name) },
                                            supportingContent = { Text("$count songs") },
                                            leadingContent = {
                                                Icon(imageVector = Icons.Rounded.Person, contentDescription = null)
                                            },
                                            modifier = Modifier.clickable {
                                                searchTab = 0
                                                searchQuery = name
                                                commitQuery(name)
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        2 -> { // Albums
                            val results = remember(searchQuery, albumsAll) {
                                if (searchQuery.isBlank()) emptyList() else albumsAll.filter { it.albumName.contains(searchQuery, true) }
                            }
                            if (results.isEmpty()) {
                                Text(
                                    text = if (searchQuery.isBlank()) "" else "No results",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            } else {
                                Spacer(Modifier.height(8.dp))
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(results.size, key = { i -> "album_res_${results[i].albumId}_${results[i].albumName}" }) { idx ->
                                        val a = results[idx]
                                        ListItem(
                                            headlineContent = { Text(a.albumName) },
                                            supportingContent = { Text(a.artistName) },
                                            leadingContent = {
                                                AlbumArtwork(
                                                    albumId = a.albumId,
                                                    firstTrackUri = null, // Will be determined by album ID
                                                    contentDescription = a.albumName,
                                                    modifier = Modifier.size(40.dp),
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                            },
                                            modifier = Modifier.clickable {
                                                searchTab = 0
                                                searchQuery = a.albumName
                                                commitQuery(a.albumName)
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }

        // Alphabetical overlay scroll bar (narrow)
        val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
        val headerAndTabsOffset = 2
        val indexMap = remember(selectedSection, tracksFiltered, artistsFiltered, albumsFiltered) {
            when (selectedSection) {
                1 -> {
                    val m = mutableMapOf<String, Int>()
                    tracksFiltered.forEachIndexed { i, t ->
                        val l = getFirstLetter(t.title)
                        if (m[l] == null) m[l] = i + headerAndTabsOffset
                    }
                    m as Map<String, Int>
                }
                2 -> {
                    val m = mutableMapOf<String, Int>()
                    artistsFiltered.forEachIndexed { i, p ->
                        val l = getFirstLetter(p.first)
                        if (m[l] == null) m[l] = i + headerAndTabsOffset
                    }
                    m as Map<String, Int>
                }
                3 -> {
                    val m = mutableMapOf<String, Int>()
                    albumsFiltered.forEachIndexed { i, a ->
                        val l = getFirstLetter(a.albumName)
                        if (m[l] == null) m[l] = i + headerAndTabsOffset
                    }
                    m as Map<String, Int>
                }
                else -> emptyMap()
            }
        }
        if (selectedSection in 1..3) {
            AlphabeticalScrollBar(
                letters = generateAlphabet(),
                onLetterSelected = { letter ->
                    val targetIndex = indexMap[letter] ?: -1
                    if (targetIndex >= 0) {
                        scope.launch { listState.scrollToItem(targetIndex) }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
                isVisible = isScrolling,
                barWidth = 20.dp,
                innerWidth = 18.dp,
                letterBoxSize = 12.dp
            )
        }
    }

    // Reduce scroll-triggered prefetch: perform a one-time light prefetch for Home lists
    LaunchedEffect(recentAdded, recentPlayed, favorites) {
        val uris = (recentAdded + recentPlayed + favorites)
            .asSequence()
            .map { it.mediaId }
            .distinct()
            .take(80)
            .toList()
        if (uris.isNotEmpty()) {
            repo.dataManager.prefetchArtwork(uris)
        }
    }

    // Song Details Editor Dialog
    if (showSongEditor && editingTrack != null) {
        SongDetailsEditor(
            track = editingTrack!!,
            onDismiss = {
                showSongEditor = false
                editingTrack = null
            },
            onSave = { updatedTrack ->
                scope.launch(Dispatchers.IO) {
                    try {
                        // Update track in database
                        repo.updateTrackMetadata(updatedTrack)
                        withContext(Dispatchers.Main) {
                            refreshTrigger++ // Trigger UI refresh
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("HomeScreen", "Failed to update track", e)
                    }
                }
            }
        )
    }
}

// (Flattened sections implemented inline above to avoid nested scrollables)

@Composable
private fun WelcomeHeader(onSearchClick: () -> Unit) {
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
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onSearchClick) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Search"
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Discover your music in a whole new way",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }

                // Settings button removed per new navigation design
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
    onSeeAll: () -> Unit,
    onClear: () -> Unit
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
            // More button with dropdown menu
            var showDropdown by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showDropdown = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                DropdownMenu(
                    expanded = showDropdown,
                    onDismissRequest = { showDropdown = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Clear") },
                        onClick = {
                            showDropdown = false
                            onClear()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = null
                            )
                        }
                    )
                }
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
    val isCurrent = com.musify.mu.playback.LocalPlaybackMediaId.current == track.mediaId
    val isPlaying = isCurrent && com.musify.mu.playback.LocalIsPlaying.current

    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .shadow(6.dp, RoundedCornerShape(14.dp))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                TrackArtwork(
                    trackUri = track.mediaId,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize()
                )
                if (isCurrent) {
                    com.musify.mu.ui.components.PlayingIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Text(
            text = track.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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


