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
    public val libraryRepository: LibraryRepository
) : ViewModel() {

    val loadingState: StateFlow<LoadingState> = libraryRepository.loadingState
    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()
    
    // Throttling for artwork prefetching to prevent excessive calls
    private var lastPrefetchTime = 0L
    private val prefetchThrottleMs = 5000L // 5 seconds
    private var lastPrefetchedUris = emptyList<String>()

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
        val now = System.currentTimeMillis()
        
        // Throttle rapid calls and avoid duplicate prefetching
        if (now - lastPrefetchTime < prefetchThrottleMs || uris == lastPrefetchedUris) {
            return
        }
        
        lastPrefetchTime = now
        lastPrefetchedUris = uris
        
        viewModelScope.launch {
            try {
                libraryRepository.dataManager.prefetchArtwork(uris)
            } catch (_: Exception) { }
        }
    }

    // Convenience snapshot for filtering without new DB calls
    fun getAllTracksSnapshot(): List<Track> = _allTracks.value

    // Playlist APIs
    suspend fun playlists(): List<com.musify.mu.data.db.entities.Playlist> = libraryRepository.playlists()
    suspend fun createPlaylist(name: String, imageUri: String? = null): Long = libraryRepository.createPlaylist(name, imageUri)
    suspend fun addToPlaylist(playlistId: Long, mediaIds: List<String>) = libraryRepository.addToPlaylist(playlistId, mediaIds)
    suspend fun removeFromPlaylist(playlistId: Long, mediaId: String) = libraryRepository.removeFromPlaylist(playlistId, mediaId)
    suspend fun playlistTracks(playlistId: Long): List<Track> = libraryRepository.playlistTracks(playlistId)
    suspend fun reorderPlaylist(playlistId: Long, orderedMediaIds: List<String>) = libraryRepository.reorderPlaylist(playlistId, orderedMediaIds)

    // Favorites
    suspend fun favorites(): List<Track> = libraryRepository.favorites()
    suspend fun saveFavoritesOrder(order: List<com.musify.mu.data.db.entities.FavoritesOrder>) = libraryRepository.saveFavoritesOrder(order)

    // Recently added / played
    suspend fun recentlyAdded(limit: Int = 20): List<Track> = libraryRepository.recentlyAdded(limit)
    suspend fun recentlyPlayed(limit: Int = 20): List<Track> = libraryRepository.recentlyPlayed(limit)
    suspend fun clearRecentlyPlayed() = libraryRepository.clearRecentlyPlayed()
    suspend fun clearRecentlyAdded() = libraryRepository.clearRecentlyAdded()

    // Search history
    fun getSearchHistory(max: Int = 20): List<String> = libraryRepository.getSearchHistory(max)
    fun addSearchHistory(query: String, max: Int = 20) = libraryRepository.addSearchHistory(query, max)
    fun clearSearchHistory() = libraryRepository.clearSearchHistory()

    // Metadata updates
    suspend fun updateTrackMetadata(track: Track) = libraryRepository.updateTrackMetadata(track)
}
