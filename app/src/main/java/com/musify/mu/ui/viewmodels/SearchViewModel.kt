package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.db.entities.Track
import com.musify.mu.domain.usecase.GetTracksUseCase
import com.musify.mu.domain.usecase.LibraryManagementUseCase
import com.musify.mu.domain.usecase.ArtworkUseCase
import com.musify.mu.data.cache.CacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val getTracksUseCase: GetTracksUseCase,
    private val libraryManagementUseCase: LibraryManagementUseCase,
    private val artworkUseCase: ArtworkUseCase,
    private val cacheManager: CacheManager
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    // Backward compatibility
    val isSearching: StateFlow<Boolean> = _uiState.map { it.isSearching }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private var searchJob: Job? = null

    init {
        loadSearchHistory()
    }

    fun search(query: String, debounceMs: Long = 300L) {
        searchJob?.cancel()
        
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _uiState.value = _uiState.value.copy(isSearching = false, error = null)
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            
            // Debounce search
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            
            try {
                // Check cache first
                val cachedResults = cacheManager.getCachedSearchResults(query)
                if (cachedResults != null) {
                    _searchResults.value = cachedResults
                    _uiState.value = _uiState.value.copy(isSearching = false)
                    return@launch
                }

                // Perform search
                val results = getTracksUseCase.searchTracks(query)
                _searchResults.value = results
                
                // Cache results
                cacheManager.cacheSearchResults(query, results)
                
                // Prefetch artwork for results
                if (results.isNotEmpty()) {
                    artworkUseCase.prefetchArtwork(
                        results.take(10).map { it.mediaId }
                    )
                    // Add to search history
                    addToSearchHistory(query)
                }
                
                _uiState.value = _uiState.value.copy(isSearching = false)
                
            } catch (e: Exception) {
                _searchResults.value = emptyList()
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = "Search failed: ${e.message}"
                )
            }
        }
    }

    fun addToSearchHistory(query: String) {
        if (query.isBlank()) return
        
        libraryManagementUseCase.addSearchHistory(query)
        loadSearchHistory()
    }

    fun clearSearchHistory() {
        libraryManagementUseCase.clearSearchHistory()
        _searchHistory.value = emptyList()
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _uiState.value = _uiState.value.copy(isSearching = false, error = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun loadSearchHistory() {
        viewModelScope.launch {
            try {
                val history = libraryManagementUseCase.getSearchHistory()
                _searchHistory.value = history
            } catch (e: Exception) {
                _searchHistory.value = emptyList()
            }
        }
    }

    // Filter methods for different search categories
    fun searchByCategory(query: String, category: SearchCategory) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSearching = true, error = null)
                
                val allTracks = getTracksUseCase.getAllTracks().first()
                val results = when (category) {
                    SearchCategory.TRACKS -> {
                        allTracks.filter { track ->
                            track.title.contains(query, ignoreCase = true)
                        }
                    }
                    SearchCategory.ARTISTS -> {
                        allTracks.filter { track ->
                            track.artist.contains(query, ignoreCase = true) ||
                            track.albumArtist?.contains(query, ignoreCase = true) == true
                        }
                    }
                    SearchCategory.ALBUMS -> {
                        allTracks.filter { track ->
                            track.album.contains(query, ignoreCase = true)
                        }
                    }
                    SearchCategory.GENRES -> {
                        allTracks.filter { track ->
                            track.genre?.contains(query, ignoreCase = true) == true
                        }
                    }
                }
                
                _searchResults.value = results
                _uiState.value = _uiState.value.copy(isSearching = false)
                
            } catch (e: Exception) {
                _searchResults.value = emptyList()
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = "Category search failed: ${e.message}"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
    }
}

data class SearchUiState(
    val isSearching: Boolean = false,
    val error: String? = null,
    val selectedCategory: SearchCategory = SearchCategory.TRACKS
)

enum class SearchCategory {
    TRACKS, ARTISTS, ALBUMS, GENRES
}
