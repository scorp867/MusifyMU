package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.media.AlbumInfo
import com.musify.mu.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {
    
    private val _artistTracks = MutableStateFlow<List<Track>>(emptyList())
    val artistTracks: StateFlow<List<Track>> = _artistTracks.asStateFlow()
    
    private val _artistAlbums = MutableStateFlow<List<AlbumInfo>>(emptyList())
    val artistAlbums: StateFlow<List<AlbumInfo>> = _artistAlbums.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    fun loadArtistData(artistName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val allTracks = libraryRepository.dataManager.getAllTracks()
                
                // Get all tracks by artist
                val tracks = allTracks
                    .filter { it.artist == artistName }
                    .sortedByDescending { it.dateAddedSec }
                
                _artistTracks.value = tracks
                
                // Get unique albums by artist
                val albums = tracks
                    .filter { it.albumId != null }
                    .groupBy { it.albumId }
                    .map { (albumId, albumTracks) ->
                        val firstTrack = albumTracks.first()
                        AlbumInfo(
                            albumId = albumId!!,
                            albumName = firstTrack.album,
                            artistName = firstTrack.artist,
                            trackCount = albumTracks.size,
                            artUri = null
                        )
                    }
                    .sortedByDescending { album ->
                        tracks.find { it.albumId == album.albumId }?.year ?: 0
                    }
                
                _artistAlbums.value = albums
            } catch (e: Exception) {
                _error.value = "Failed to load artist data"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
}