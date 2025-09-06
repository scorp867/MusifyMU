package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.db.entities.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    init {
        loadHomeData()
    }

    private fun loadHomeData() {
        viewModelScope.launch {
            try {
                // Load recently played tracks
                val recent = libraryRepository.recentlyPlayed(10)
                _recentlyPlayed.value = recent

                // Load recently added tracks
                val added = libraryRepository.recentlyAdded(10)
                _recentlyAdded.value = added

                // Load favorites
                val favs = libraryRepository.favorites()
                _favorites.value = favs.take(10)

            } catch (e: Exception) {
                // Handle errors gracefully
                _recentlyPlayed.value = emptyList()
                _recentlyAdded.value = emptyList()
                _favorites.value = emptyList()
            }
        }
    }

    fun refreshData() {
        loadHomeData()
    }
}
