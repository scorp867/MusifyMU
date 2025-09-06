package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.db.entities.Playlist
import com.musify.mu.domain.usecase.LibraryManagementUseCase
import com.musify.mu.domain.usecase.ArtworkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryManagementUseCase: LibraryManagementUseCase,
    private val artworkUseCase: ArtworkUseCase
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _recentlyPlayed = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recentlyPlayed.asStateFlow()

    private val _recentlyAdded = MutableStateFlow<List<Track>>(emptyList())
    val recentlyAdded: StateFlow<List<Track>> = _recentlyAdded.asStateFlow()

    private val _favorites = MutableStateFlow<List<Track>>(emptyList())
    val favorites: StateFlow<List<Track>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    init {
        loadHomeData()
    }

    private fun loadHomeData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                // Load all home data in parallel
                val recentPlayedDeferred = async { libraryManagementUseCase.getRecentlyPlayed(10) }
                val recentAddedDeferred = async { libraryManagementUseCase.getRecentlyAdded(10) }
                val favoritesDeferred = async { libraryManagementUseCase.getFavorites() }
                val playlistsDeferred = async { libraryManagementUseCase.getPlaylists() }

                val recentPlayed = recentPlayedDeferred.await()
                val recentAdded = recentAddedDeferred.await()
                val favorites = favoritesDeferred.await()
                val playlists = playlistsDeferred.await()

                _recentlyPlayed.value = recentPlayed
                _recentlyAdded.value = recentAdded
                _favorites.value = favorites.take(10)
                _playlists.value = playlists

                // Prefetch artwork for visible items
                val visibleTracks = (recentPlayed + recentAdded + favorites.take(10))
                    .distinctBy { it.mediaId }
                    .take(30)
                if (visibleTracks.isNotEmpty()) {
                    artworkUseCase.prefetchArtwork(visibleTracks.map { it.mediaId })
                }

                _uiState.value = _uiState.value.copy(isLoading = false)

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load home data: ${e.message}"
                )
                // Set empty lists as fallback
                _recentlyPlayed.value = emptyList()
                _recentlyAdded.value = emptyList()
                _favorites.value = emptyList()
                _playlists.value = emptyList()
            }
        }
    }

    fun refreshData() {
        loadHomeData()
    }

    fun clearRecentlyPlayed() {
        viewModelScope.launch {
            try {
                libraryManagementUseCase.clearRecentlyPlayed()
                _recentlyPlayed.value = emptyList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to clear recently played: ${e.message}"
                )
            }
        }
    }

    fun clearRecentlyAdded() {
        viewModelScope.launch {
            try {
                libraryManagementUseCase.clearRecentlyAdded()
                _recentlyAdded.value = emptyList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to clear recently added: ${e.message}"
                )
            }
        }
    }

    fun createPlaylist(name: String, imageUri: String? = null) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isCreatingPlaylist = true, error = null)
                libraryManagementUseCase.createPlaylist(name, imageUri)
                // Refresh playlists
                val playlists = libraryManagementUseCase.getPlaylists()
                _playlists.value = playlists
                _uiState.value = _uiState.value.copy(isCreatingPlaylist = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreatingPlaylist = false,
                    error = "Failed to create playlist: ${e.message}"
                )
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            try {
                libraryManagementUseCase.deletePlaylist(playlistId)
                // Refresh playlists
                val playlists = libraryManagementUseCase.getPlaylists()
                _playlists.value = playlists
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to delete playlist: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // Search functionality
    fun getSearchHistory(): List<String> {
        return libraryManagementUseCase.getSearchHistory()
    }

    fun addSearchHistory(query: String) {
        libraryManagementUseCase.addSearchHistory(query)
    }

    fun clearSearchHistory() {
        libraryManagementUseCase.clearSearchHistory()
    }
}

data class HomeUiState(
    val isLoading: Boolean = false,
    val isCreatingPlaylist: Boolean = false,
    val error: String? = null
)

