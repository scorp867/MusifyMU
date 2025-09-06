package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.db.entities.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        loadSearchHistory()
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        _isSearching.value = true
        val results = libraryRepository.search(query)
        _searchResults.value = results
        _isSearching.value = false

        // Add to search history if we have results
        if (results.isNotEmpty()) {
            addToSearchHistory(query)
        }
    }

    private fun loadSearchHistory() {
        _searchHistory.value = libraryRepository.getSearchHistory()
    }

    private fun addToSearchHistory(query: String) {
        libraryRepository.addSearchHistory(query)
        loadSearchHistory()
    }

    fun clearSearchHistory() {
        libraryRepository.clearSearchHistory()
        _searchHistory.value = emptyList()
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
        _isSearching.value = false
    }
}
