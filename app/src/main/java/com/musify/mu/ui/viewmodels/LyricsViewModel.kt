package com.musify.mu.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.repo.LyricsStateStore
import com.musify.mu.data.repo.LyricsRepository
import com.musify.mu.lyrics.LrcLine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val lyricsStateStore: LyricsStateStore,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    val currentLyrics: StateFlow<LyricsStateStore.LyricsState?> = lyricsStateStore.currentLyrics

    fun loadLyrics(mediaId: String) {
        viewModelScope.launch {
            lyricsStateStore.loadLyrics(mediaId)
        }
    }

    fun clearCache(mediaId: String) {
        lyricsStateStore.clearCache(mediaId)
    }

    fun updateLyrics(mediaId: String, text: String?, lrcLines: List<LrcLine> = emptyList(), isLrc: Boolean = false) {
        viewModelScope.launch {
            lyricsStateStore.updateLyrics(mediaId, text, lrcLines, isLrc)
        }
    }

    suspend fun attachLrc(mediaId: String, uri: Uri) {
        lyricsRepository.attachLrc(mediaId, uri)
    }

    suspend fun attachText(mediaId: String, text: String) {
        lyricsRepository.attachText(mediaId, text)
    }
}
