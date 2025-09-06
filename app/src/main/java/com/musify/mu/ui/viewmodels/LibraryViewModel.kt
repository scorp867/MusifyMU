package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.media.LoadingState
import com.musify.mu.data.db.entities.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    val loadingState: StateFlow<LoadingState> = libraryRepository.loadingState
    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()

    init {
        viewModelScope.launch {
            libraryRepository.dataManager.tracks.collect { tracks ->
                _allTracks.value = tracks
            }
        }
    }

    fun searchTracks(query: String): List<Track> {
        return libraryRepository.search(query)
    }

    fun getTrackByMediaId(mediaId: String): Track? {
        return libraryRepository.getTrackByMediaId(mediaId)
    }

    fun recordPlayed(mediaId: String) {
        viewModelScope.launch {
            libraryRepository.recordPlayed(mediaId)
        }
    }

    suspend fun like(mediaId: String) {
        libraryRepository.like(mediaId)
    }

    suspend fun unlike(mediaId: String) {
        libraryRepository.unlike(mediaId)
    }

    suspend fun isLiked(mediaId: String): Boolean {
        return libraryRepository.isLiked(mediaId)
    }

    fun ensureDataManagerInitialized() {
        viewModelScope.launch {
            libraryRepository.dataManager.ensureInitialized()
        }
    }

    val dataManagerTracks = libraryRepository.dataManager.tracks

    fun prefetchArtwork(uris: List<String>) {
        viewModelScope.launch {
            libraryRepository.dataManager.prefetchArtwork(uris)
        }
    }
}
