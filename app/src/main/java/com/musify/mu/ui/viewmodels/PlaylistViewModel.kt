package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.db.entities.Playlist
import com.musify.mu.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {
    
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    init {
        loadPlaylists()
    }
    
    fun loadPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val playlists = libraryRepository.playlists()
                _playlists.value = playlists
            } catch (e: Exception) {
                _error.value = "Failed to load playlists"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun createPlaylist(name: String, imageUri: String? = null) {
        viewModelScope.launch {
            try {
                libraryRepository.createPlaylist(name, imageUri)
                loadPlaylists() // Reload playlists
            } catch (e: Exception) {
                _error.value = "Failed to create playlist"
            }
        }
    }
    
    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            try {
                libraryRepository.deletePlaylist(playlistId)
                _playlists.update { it.filter { p -> p.id != playlistId } }
            } catch (e: Exception) {
                _error.value = "Failed to delete playlist"
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
}