package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch


@HiltViewModel
class PlaybackViewModel @Inject constructor(
	private val playbackStateStore: PlaybackStateStore,
	private val libraryRepository: LibraryRepository
) : ViewModel() {

	fun resolveTrack(mediaItem: MediaItem?): Track? {
		return mediaItem?.let { item ->
			libraryRepository.getTrackByMediaId(item.mediaId) ?: item.toTrack()
		}
	}

	suspend fun getPreviewTrack(): Track? {
		return withContext(Dispatchers.IO) {
			try {
				val state = playbackStateStore.load()
				val trackId = state?.mediaIds?.getOrNull(state.index)
				trackId?.let { libraryRepository.getTrackByMediaId(it) }
			} catch (_: Exception) {
				null
			}
		}
	}

	fun restoreQueueAndPlay(
		mediaController: MediaController?,
		onPlay: (List<Track>, Int) -> Unit
	) {
		viewModelScope.launch(Dispatchers.IO) {
			try {
				val state = playbackStateStore.load()
				if (state != null && state.mediaIds.isNotEmpty()) {
					val tracks = state.mediaIds.mapNotNull { id ->
						libraryRepository.getTrackByMediaId(id)
					}
					if (tracks.isNotEmpty()) {
						val safeIndex = state.index.coerceIn(0, tracks.size - 1)
						withContext(Dispatchers.Main) { onPlay(tracks, safeIndex) }
					}
				}
			} catch (_: Exception) { }
		}
	}
}

private fun MediaItem.toTrack(): Track {
	val md: MediaMetadata = mediaMetadata
	return Track(
		mediaId = mediaId,
		title = md.title?.toString() ?: "",
		artist = md.artist?.toString() ?: "",
		album = md.albumTitle?.toString() ?: "",
		durationMs = 0L,
		artUri = md.artworkUri?.toString(),
		albumId = null
	)
}
