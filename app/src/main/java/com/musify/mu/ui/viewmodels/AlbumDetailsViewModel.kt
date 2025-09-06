package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {
    
    private val _albumTracks = MutableStateFlow<List<Track>>(emptyList())
    val albumTracks: StateFlow<List<Track>> = _albumTracks.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    fun loadAlbumTracks(albumName: String, artistName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val allTracks = libraryRepository.dataManager.getAllTracks()
                val tracks = allTracks
                    .filter { it.album == albumName && it.artist == artistName }
                    .sortedBy { it.track ?: Int.MAX_VALUE }
                
                _albumTracks.value = tracks
            } catch (e: Exception) {
                _error.value = "Failed to load album tracks"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun toggleLike(track: Track) {
        viewModelScope.launch {
            try {
                val isLiked = libraryRepository.isLiked(track.mediaId)
                if (isLiked) {
                    libraryRepository.unlike(track.mediaId)
                } else {
                    libraryRepository.like(track.mediaId)
                }
            } catch (e: Exception) {
                _error.value = "Failed to update like status"
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
}