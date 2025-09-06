package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.db.entities.Playlist
import com.musify.mu.data.db.entities.FavoritesOrder
import com.musify.mu.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SeeAllViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {
    
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private var currentType: String? = null
    
    fun loadData(type: String) {
        currentType = type
        
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                when (type) {
                    "recently_played" -> {
                        _title.value = "Recently Played"
                        _tracks.value = libraryRepository.recentlyPlayed(50)
                        _playlists.value = emptyList()
                    }
                    "recently_added" -> {
                        _title.value = "Recently Added"
                        _tracks.value = libraryRepository.recentlyAdded(50)
                        _playlists.value = emptyList()
                    }
                    "favorites" -> {
                        _title.value = "Favourites"
                        _tracks.value = libraryRepository.favorites()
                        _playlists.value = emptyList()
                    }
                    "playlists" -> {
                        _title.value = "Playlists"
                        _tracks.value = emptyList()
                        _playlists.value = libraryRepository.playlists()
                    }
                    else -> {
                        _error.value = "Unknown list type"
                    }
                }
            } catch (e: Exception) {
                _error.value = "Failed to load data"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun reorderFavorites(orderedMediaIds: List<String>) {
        if (currentType != "favorites") return
        
        viewModelScope.launch {
            try {
                val favoritesOrder = orderedMediaIds.mapIndexed { index, mediaId ->
                    FavoritesOrder(mediaId = mediaId, position = index)
                }
                libraryRepository.saveFavoritesOrder(favoritesOrder)
                
                // Update local state immediately for smooth UI
                val currentTracksMap = _tracks.value.associateBy { it.mediaId }
                val reorderedTracks = orderedMediaIds.mapNotNull { currentTracksMap[it] }
                _tracks.value = reorderedTracks
            } catch (e: Exception) {
                _error.value = "Failed to reorder favorites"
                // Reload on error
                loadData("favorites")
            }
        }
    }
    
    fun toggleLike(track: Track) {
        viewModelScope.launch {
            try {
                val isLiked = libraryRepository.isLiked(track.mediaId)
                if (isLiked) {
                    libraryRepository.unlike(track.mediaId)
                    // If we're viewing favorites, remove from list
                    if (currentType == "favorites") {
                        _tracks.update { it.filter { t -> t.mediaId != track.mediaId } }
                    }
                } else {
                    libraryRepository.like(track.mediaId)
                }
            } catch (e: Exception) {
                _error.value = "Failed to update like status"
            }
        }
    }
    
    fun clearRecentlyPlayed() {
        if (currentType != "recently_played") return
        
        viewModelScope.launch {
            try {
                libraryRepository.clearRecentlyPlayed()
                _tracks.value = emptyList()
            } catch (e: Exception) {
                _error.value = "Failed to clear recently played"
            }
        }
    }
    
    fun clearRecentlyAdded() {
        if (currentType != "recently_added") return
        
        viewModelScope.launch {
            try {
                libraryRepository.clearRecentlyAdded()
                _tracks.value = emptyList()
            } catch (e: Exception) {
                _error.value = "Failed to clear recently added"
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