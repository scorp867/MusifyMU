package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.media.LoadingState
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.db.entities.Playlist
import com.musify.mu.data.db.entities.FavoritesOrder
import com.musify.mu.data.media.AlbumInfo
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

    // --- Repository wrappers for MVVM usage from UI layers ---

    fun getAllTracks(): List<Track> {
        return libraryRepository.getAllTracks()
    }

    suspend fun getPlaylists(): List<Playlist> {
        return libraryRepository.playlists()
    }

    suspend fun createPlaylist(name: String, imageUri: String? = null): Long {
        return libraryRepository.createPlaylist(name, imageUri)
    }

    suspend fun playlistTracks(playlistId: Long): List<Track> {
        return libraryRepository.playlistTracks(playlistId)
    }

    suspend fun reorderPlaylist(playlistId: Long, orderedMediaIds: List<String>) {
        libraryRepository.reorderPlaylist(playlistId, orderedMediaIds)
    }

    suspend fun removeFromPlaylist(playlistId: Long, mediaId: String) {
        libraryRepository.removeFromPlaylist(playlistId, mediaId)
    }

    suspend fun addToPlaylist(playlistId: Long, mediaIds: List<String>) {
        libraryRepository.addToPlaylist(playlistId, mediaIds)
    }

    suspend fun recentlyPlayed(limit: Int = 200): List<Track> {
        return libraryRepository.recentlyPlayed(limit)
    }

    suspend fun recentlyAdded(limit: Int = 200): List<Track> {
        return libraryRepository.recentlyAdded(limit)
    }

    suspend fun favorites(): List<Track> {
        return libraryRepository.favorites()
    }

    suspend fun saveFavoritesOrder(order: List<FavoritesOrder>) {
        libraryRepository.saveFavoritesOrder(order)
    }

    suspend fun clearRecentlyPlayed() {
        libraryRepository.clearRecentlyPlayed()
    }

    suspend fun clearRecentlyAdded() {
        libraryRepository.clearRecentlyAdded()
    }

    suspend fun updateTrackMetadata(track: Track) {
        libraryRepository.updateTrackMetadata(track)
    }

    fun getUniqueAlbums(): List<AlbumInfo> {
        return libraryRepository.dataManager.getUniqueAlbums()
    }

    // --- Search history wrappers ---
    fun getSearchHistory(max: Int = 20): List<String> {
        return libraryRepository.getSearchHistory(max)
    }

    fun addSearchHistory(query: String, max: Int = 20) {
        libraryRepository.addSearchHistory(query, max)
    }

    fun clearSearchHistory() {
        libraryRepository.clearSearchHistory()
    }
}
