package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.lyrics.LrcParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log
import java.io.File

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {
    
    companion object {
        private const val TAG = "NowPlayingViewModel"
    }
    
    // Current track
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()
    
    // Playback state
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    // Progress
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()
    
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    
    // Player modes
    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()
    
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    
    // Like status
    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()
    
    // Lyrics
    private val _lyrics = MutableStateFlow<List<LrcParser.LyricLine>?>(null)
    val lyrics: StateFlow<List<LrcParser.LyricLine>?> = _lyrics.asStateFlow()
    
    private val _currentLyricIndex = MutableStateFlow(-1)
    val currentLyricIndex: StateFlow<Int> = _currentLyricIndex.asStateFlow()
    
    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Media controller reference
    private var mediaController: MediaController? = null
    
    /**
     * Set the media controller
     */
    fun setMediaController(controller: MediaController?) {
        mediaController = controller
    }
    
    /**
     * Update current track from media item
     */
    fun updateFromMediaItem(mediaItem: MediaItem?) {
        viewModelScope.launch {
            try {
                if (mediaItem == null) {
                    _currentTrack.value = null
                    _isLiked.value = false
                    _lyrics.value = null
                    return@launch
                }
                
                // Get track from repository
                val track = libraryRepository.getTrackByMediaId(mediaItem.mediaId)
                _currentTrack.value = track
                
                // Check like status
                if (track != null) {
                    _isLiked.value = libraryRepository.isLiked(track.mediaId)
                    loadLyrics(track)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating from media item", e)
            }
        }
    }
    
    /**
     * Update playback state
     */
    fun updatePlaybackState(isPlaying: Boolean, position: Long, duration: Long) {
        _isPlaying.value = isPlaying
        _currentPosition.value = position
        _duration.value = duration
        
        if (duration > 0) {
            _progress.value = position.toFloat() / duration.toFloat()
        } else {
            _progress.value = 0f
        }
        
        // Update current lyric index
        updateCurrentLyricIndex(position)
    }
    
    /**
     * Update player modes
     */
    fun updatePlayerModes(shuffleEnabled: Boolean, repeatMode: Int) {
        _shuffleEnabled.value = shuffleEnabled
        _repeatMode.value = repeatMode
    }
    
    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        mediaController?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
        }
    }
    
    /**
     * Skip to next
     */
    fun skipToNext() {
        mediaController?.seekToNext()
    }
    
    /**
     * Skip to previous
     */
    fun skipToPrevious() {
        mediaController?.seekToPrevious()
    }
    
    /**
     * Seek to position
     */
    fun seekTo(position: Long) {
        mediaController?.seekTo(position)
    }
    
    /**
     * Toggle shuffle
     */
    fun toggleShuffle() {
        mediaController?.let { controller ->
            controller.shuffleModeEnabled = !controller.shuffleModeEnabled
            _shuffleEnabled.value = controller.shuffleModeEnabled
        }
    }
    
    /**
     * Cycle repeat mode
     */
    fun cycleRepeatMode() {
        mediaController?.let { controller ->
            val newMode = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            controller.repeatMode = newMode
            _repeatMode.value = newMode
        }
    }
    
    /**
     * Toggle like status
     */
    fun toggleLike() {
        val track = _currentTrack.value ?: return
        
        viewModelScope.launch {
            try {
                if (_isLiked.value) {
                    libraryRepository.unlike(track.mediaId)
                    _isLiked.value = false
                } else {
                    libraryRepository.like(track.mediaId)
                    _isLiked.value = true
                }
            } catch (e: Exception) {
                _error.value = "Failed to update like status"
            }
        }
    }
    
    /**
     * Load lyrics for current track
     */
    private fun loadLyrics(track: Track) {
        viewModelScope.launch {
            try {
                // Look for .lrc file with same name as audio file
                val audioFile = File(track.mediaId)
                val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
                
                if (lrcFile.exists() && lrcFile.canRead()) {
                    val lrcContent = lrcFile.readText()
                    val parsedLyrics = LrcParser.parse(lrcContent)
                    _lyrics.value = parsedLyrics
                } else {
                    _lyrics.value = null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading lyrics", e)
                _lyrics.value = null
            }
        }
    }
    
    /**
     * Update current lyric index based on position
     */
    private fun updateCurrentLyricIndex(positionMs: Long) {
        val lyricsList = _lyrics.value ?: return
        
        var index = -1
        for (i in lyricsList.indices) {
            if (lyricsList[i].timeMs <= positionMs) {
                index = i
            } else {
                break
            }
        }
        
        _currentLyricIndex.value = index
    }
    
    /**
     * Seek to lyric position
     */
    fun seekToLyric(index: Int) {
        val lyricsList = _lyrics.value ?: return
        if (index in lyricsList.indices) {
            seekTo(lyricsList[index].timeMs)
        }
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }
}