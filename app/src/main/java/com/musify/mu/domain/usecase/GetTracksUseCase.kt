package com.musify.mu.domain.usecase

import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.domain.model.TrackWithPlaybackInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetTracksUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository
) {
    
    /**
     * Get all tracks with real-time updates
     */
    fun getAllTracks(): Flow<List<Track>> {
        return libraryRepository.dataManager.tracks
    }
    
    /**
     * Get tracks with playback information
     */
    fun getTracksWithPlaybackInfo(): Flow<List<TrackWithPlaybackInfo>> {
        return combine(
            libraryRepository.dataManager.tracks,
            libraryRepository.loadingState
        ) { tracks, loadingState ->
            tracks.map { track ->
                TrackWithPlaybackInfo(
                    track = track,
                    isLoading = loadingState is com.musify.mu.data.media.LoadingState.Loading,
                    hasArtwork = !track.artUri.isNullOrBlank()
                )
            }
        }
    }
    
    /**
     * Search tracks
     */
    suspend fun searchTracks(query: String): List<Track> {
        return libraryRepository.search(query)
    }
    
    /**
     * Get track by media ID
     */
    suspend fun getTrackByMediaId(mediaId: String): Track? {
        return libraryRepository.getTrackByMediaId(mediaId)
    }
}