package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.db.entities.Playlist
import com.musify.mu.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {
    
    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()
    
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    fun loadPlaylist(playlistId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                // Load playlist metadata
                val playlists = libraryRepository.playlists()
                _playlist.value = playlists.find { it.id == playlistId }
                
                // Load playlist tracks
                val tracks = libraryRepository.playlistTracks(playlistId)
                _tracks.value = tracks
                
            } catch (e: Exception) {
                _error.value = "Failed to load playlist"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun removeTrack(mediaId: String) {
        val playlistId = _playlist.value?.id ?: return
        
        viewModelScope.launch {
            try {
                libraryRepository.removeFromPlaylist(playlistId, mediaId)
                // Reload tracks
                loadPlaylist(playlistId)
            } catch (e: Exception) {
                _error.value = "Failed to remove track"
            }
        }
    }
    
    fun reorderTracks(orderedMediaIds: List<String>) {
        val playlistId = _playlist.value?.id ?: return
        
        viewModelScope.launch {
            try {
                libraryRepository.reorderPlaylist(playlistId, orderedMediaIds)
                // Update local state immediately for smooth UI
                val currentTracksMap = _tracks.value.associateBy { it.mediaId }
                val reorderedTracks = orderedMediaIds.mapNotNull { currentTracksMap[it] }
                _tracks.value = reorderedTracks
            } catch (e: Exception) {
                _error.value = "Failed to reorder tracks"
                // Reload to get correct order on error
                loadPlaylist(playlistId)
            }
        }
    }
    
    fun renamePlaylist(newName: String) {
        val playlistId = _playlist.value?.id ?: return
        
        viewModelScope.launch {
            try {
                libraryRepository.renamePlaylist(playlistId, newName)
                _playlist.update { it?.copy(name = newName) }
            } catch (e: Exception) {
                _error.value = "Failed to rename playlist"
            }
        }
    }
    
    fun deletePlaylist() {
        val playlistId = _playlist.value?.id ?: return
        
        viewModelScope.launch {
            try {
                libraryRepository.deletePlaylist(playlistId)
                // Playlist deleted, UI should navigate back
            } catch (e: Exception) {
                _error.value = "Failed to delete playlist"
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
}