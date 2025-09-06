package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.db.entities.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val _recentlyPlayed = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recentlyPlayed.asStateFlow()

    private val _recentlyAdded = MutableStateFlow<List<Track>>(emptyList())
    val recentlyAdded: StateFlow<List<Track>> = _recentlyAdded.asStateFlow()

    private val _favorites = MutableStateFlow<List<Track>>(emptyList())
    val favorites: StateFlow<List<Track>> = _favorites.asStateFlow()
    
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Tracks from data manager
    val allTracks: StateFlow<List<Track>> = libraryRepository.dataManager.tracks
    
    // Search functionality
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    val searchResults: StateFlow<List<Track>> = _searchQuery.combine(allTracks) { query, tracks ->
        if (query.isBlank()) emptyList()
        else tracks.filter { track ->
            track.title.contains(query, true) ||
            track.artist.contains(query, true) ||
            track.album.contains(query, true)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        loadHomeData()
        observeDataChanges()
    }
    
    private fun observeDataChanges() {
        // Reload home data when tracks change
        viewModelScope.launch {
            libraryRepository.dataManager.tracks.collect { tracks ->
                if (tracks.isNotEmpty()) {
                    loadHomeData()
                }
            }
        }
    }

    private fun loadHomeData() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                withContext(Dispatchers.IO) {
                    // Load all data in parallel
                    val recent = libraryRepository.recentlyPlayed(10)
                    val added = libraryRepository.recentlyAdded(10)
                    val favs = libraryRepository.favorites().take(10)
                    val playlistList = libraryRepository.playlists()
                    
                    withContext(Dispatchers.Main) {
                        _recentlyPlayed.value = recent
                        _recentlyAdded.value = added
                        _favorites.value = favs
                        _playlists.value = playlistList
                    }
                }
            } catch (e: Exception) {
                _error.value = "Failed to load home data"
                _recentlyPlayed.value = emptyList()
                _recentlyAdded.value = emptyList()
                _favorites.value = emptyList()
                _playlists.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshData() {
        loadHomeData()
    }
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    fun clearRecentlyPlayed() {
        viewModelScope.launch {
            try {
                libraryRepository.clearRecentlyPlayed()
                loadHomeData()
            } catch (e: Exception) {
                _error.value = "Failed to clear recently played"
            }
        }
    }
    
    fun clearRecentlyAdded() {
        viewModelScope.launch {
            try {
                libraryRepository.clearRecentlyAdded()
                loadHomeData()
            } catch (e: Exception) {
                _error.value = "Failed to clear recently added"
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
}
