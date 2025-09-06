package com.musify.mu.domain.usecase

import com.musify.mu.data.db.entities.Playlist
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.media.LoadingState
import com.musify.mu.data.repo.LibraryRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryManagementUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository
) {
    
    /**
     * Get loading state
     */
    fun getLoadingState(): StateFlow<LoadingState> {
        return libraryRepository.loadingState
    }
    
    /**
     * Ensure data manager is initialized
     */
    suspend fun ensureDataManagerInitialized() {
        libraryRepository.dataManager.ensureInitialized()
    }
    
    /**
     * Force refresh library
     */
    fun forceRefreshLibrary() {
        libraryRepository.dataManager.forceRefresh()
    }
    
    // Playlist management
    suspend fun getPlaylists(): List<Playlist> {
        return libraryRepository.playlists()
    }
    
    suspend fun createPlaylist(name: String, imageUri: String? = null): Long {
        return libraryRepository.createPlaylist(name, imageUri)
    }
    
    suspend fun deletePlaylist(id: Long) {
        libraryRepository.deletePlaylist(id)
    }
    
    suspend fun renamePlaylist(id: Long, name: String) {
        libraryRepository.renamePlaylist(id, name)
    }
    
    suspend fun addToPlaylist(playlistId: Long, mediaIds: List<String>) {
        libraryRepository.addToPlaylist(playlistId, mediaIds)
    }
    
    suspend fun removeFromPlaylist(playlistId: Long, mediaId: String) {
        libraryRepository.removeFromPlaylist(playlistId, mediaId)
    }
    
    suspend fun getPlaylistTracks(playlistId: Long): List<Track> {
        return libraryRepository.playlistTracks(playlistId)
    }
    
    suspend fun reorderPlaylist(playlistId: Long, orderedMediaIds: List<String>) {
        libraryRepository.reorderPlaylist(playlistId, orderedMediaIds)
    }
    
    // Favorites management
    suspend fun likeTrack(mediaId: String) {
        libraryRepository.like(mediaId)
    }
    
    suspend fun unlikeTrack(mediaId: String) {
        libraryRepository.unlike(mediaId)
    }
    
    suspend fun isTrackLiked(mediaId: String): Boolean {
        return libraryRepository.isLiked(mediaId)
    }
    
    suspend fun getFavorites(): List<Track> {
        return libraryRepository.favorites()
    }
    
    // Recently played/added
    suspend fun getRecentlyPlayed(limit: Int = 20): List<Track> {
        return libraryRepository.recentlyPlayed(limit)
    }
    
    suspend fun getRecentlyAdded(limit: Int = 20): List<Track> {
        return libraryRepository.recentlyAdded(limit)
    }
    
    suspend fun clearRecentlyPlayed() {
        libraryRepository.clearRecentlyPlayed()
    }
    
    suspend fun clearRecentlyAdded() {
        libraryRepository.clearRecentlyAdded()
    }
    
    // Search
    fun getSearchHistory(max: Int = 20): List<String> {
        return libraryRepository.getSearchHistory(max)
    }
    
    fun addSearchHistory(query: String, max: Int = 20) {
        libraryRepository.addSearchHistory(query, max)
    }
    
    fun clearSearchHistory() {
        libraryRepository.clearSearchHistory()
    }
    
    // Metadata management
    suspend fun updateTrackMetadata(track: Track) {
        libraryRepository.updateTrackMetadata(track)
    }
    
    suspend fun updateTrackArtwork(mediaId: String, artUri: String?) {
        libraryRepository.updateTrackArt(mediaId, artUri)
    }
}