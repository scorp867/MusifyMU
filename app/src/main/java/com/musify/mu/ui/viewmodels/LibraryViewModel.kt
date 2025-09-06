package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.media.LoadingState
import com.musify.mu.data.db.entities.Track
import com.musify.mu.domain.model.TrackWithPlaybackInfo
import com.musify.mu.domain.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val getTracksUseCase: GetTracksUseCase,
    private val libraryManagementUseCase: LibraryManagementUseCase,
    private val artworkUseCase: ArtworkUseCase,
    private val playbackUseCase: PlaybackUseCase
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Expose loading state
    val loadingState: StateFlow<LoadingState> = libraryManagementUseCase.getLoadingState()
    
    // Expose tracks with playback info
    val tracksWithPlaybackInfo: StateFlow<List<TrackWithPlaybackInfo>> = 
        getTracksUseCase.getTracksWithPlaybackInfo()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    // Simple tracks flow for backward compatibility
    val allTracks: StateFlow<List<Track>> = getTracksUseCase.getAllTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // For backward compatibility
    val dataManagerTracks = allTracks

    init {
        // Initialize data manager
        viewModelScope.launch {
            try {
                libraryManagementUseCase.ensureDataManagerInitialized()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to initialize library: ${e.message}"
                )
            }
        }
    }

    fun searchTracks(query: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSearching = true, error = null)
                val results = getTracksUseCase.searchTracks(query)
                _uiState.value = _uiState.value.copy(
                    searchResults = results,
                    isSearching = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = "Search failed: ${e.message}"
                )
            }
        }
    }

    fun getTrackByMediaId(mediaId: String): Track? {
        return allTracks.value.find { it.mediaId == mediaId }
    }

    fun recordPlayed(mediaId: String) {
        viewModelScope.launch {
            try {
                playbackUseCase.recordTrackPlayed(mediaId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to record play: ${e.message}"
                )
            }
        }
    }

    fun likeTrack(mediaId: String) {
        viewModelScope.launch {
            try {
                libraryManagementUseCase.likeTrack(mediaId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to like track: ${e.message}"
                )
            }
        }
    }

    fun unlikeTrack(mediaId: String) {
        viewModelScope.launch {
            try {
                libraryManagementUseCase.unlikeTrack(mediaId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to unlike track: ${e.message}"
                )
            }
        }
    }

    suspend fun isLiked(mediaId: String): Boolean {
        return try {
            libraryManagementUseCase.isTrackLiked(mediaId)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                error = "Failed to check like status: ${e.message}"
            )
            false
        }
    }

    fun ensureDataManagerInitialized() {
        viewModelScope.launch {
            try {
                libraryManagementUseCase.ensureDataManagerInitialized()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to initialize data manager: ${e.message}"
                )
            }
        }
    }

    fun prefetchArtwork(uris: List<String>) {
        viewModelScope.launch {
            try {
                artworkUseCase.prefetchArtwork(uris)
            } catch (e: Exception) {
                // Don't show error for artwork prefetch failures
                android.util.Log.w("LibraryViewModel", "Failed to prefetch artwork", e)
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
                libraryManagementUseCase.forceRefreshLibrary()
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = "Failed to refresh library: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSearchResults() {
        _uiState.value = _uiState.value.copy(searchResults = emptyList())
    }
}

data class LibraryUiState(
    val isSearching: Boolean = false,
    val isRefreshing: Boolean = false,
    val searchResults: List<Track> = emptyList(),
    val error: String? = null
)
