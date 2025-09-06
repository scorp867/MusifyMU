package com.musify.mu.domain.usecase

import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.playback.QueueManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackStateStore: PlaybackStateStore,
    private val queueStateStore: QueueStateStore,
    private val queueManager: QueueManager
) {
    
    /**
     * Record track as played
     */
    suspend fun recordTrackPlayed(mediaId: String) {
        libraryRepository.recordPlayed(mediaId)
    }
    
    /**
     * Save current playback state
     */
    suspend fun savePlaybackState(
        mediaIds: List<String>,
        currentIndex: Int,
        positionMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean,
        isPlaying: Boolean
    ) {
        playbackStateStore.save(
            ids = mediaIds,
            index = currentIndex,
            posMs = positionMs,
            repeat = repeatMode,
            shuffle = shuffleEnabled,
            play = isPlaying
        )
    }
    
    /**
     * Load saved playback state
     */
    suspend fun loadPlaybackState(): PlaybackStateStore.State? {
        return playbackStateStore.load()
    }
    
    /**
     * Save queue state
     */
    suspend fun saveQueueState(
        playNextItems: List<String>,
        userQueueItems: List<String>,
        currentMainIndex: Int
    ) {
        queueStateStore.saveQueueState(
            playNextItems = playNextItems,
            userQueueItems = userQueueItems,
            currentMainIndex = currentMainIndex
        )
    }
    
    /**
     * Load queue state
     */
    suspend fun loadQueueState(): QueueStateStore.QueueState? {
        return queueStateStore.loadQueueState()
    }
    
    /**
     * Clear playback state
     */
    suspend fun clearPlaybackState() {
        playbackStateStore.clear()
        queueStateStore.clearQueueState()
    }
    
    /**
     * Get queue state flow
     */
    fun getQueueStateFlow(): StateFlow<QueueManager.QueueState> {
        return queueManager.queueStateFlow
    }
}