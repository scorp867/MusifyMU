package com.musify.mu.domain.usecase

import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.util.SpotifyStyleArtworkLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository
) {
    
    /**
     * Get artwork for a track with caching
     */
    suspend fun getArtworkForTrack(trackUri: String): String? = withContext(Dispatchers.IO) {
        val track = libraryRepository.getTrackByMediaId(trackUri)
        val hasEmbeddedArt = track?.hasEmbeddedArtwork
        
        return@withContext SpotifyStyleArtworkLoader.loadArtwork(trackUri, hasEmbeddedArt)
    }
    
    /**
     * Prefetch artwork for multiple tracks
     */
    suspend fun prefetchArtwork(trackUris: List<String>) = withContext(Dispatchers.IO) {
        libraryRepository.dataManager.prefetchArtwork(trackUris)
    }
    
    /**
     * Clear artwork cache
     */
    fun clearArtworkCache() {
        SpotifyStyleArtworkLoader.clearCaches()
    }
    
    /**
     * Get cache statistics
     */
    fun getArtworkCacheStats(): com.musify.mu.util.ArtworkCacheStats {
        return SpotifyStyleArtworkLoader.getCacheStats()
    }
    
    /**
     * Store artwork bytes (for media session artwork)
     */
    suspend fun storeArtworkBytes(mediaId: String, artworkBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        return@withContext SpotifyStyleArtworkLoader.storeArtworkBytes(mediaId, artworkBytes)
    }
}