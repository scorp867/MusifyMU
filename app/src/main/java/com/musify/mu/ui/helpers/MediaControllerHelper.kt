package com.musify.mu.ui.helpers

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.playback.PlayerService
import com.musify.mu.util.toTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remember and manage MediaController lifecycle in Compose
 */
@Composable
fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    
    DisposableEffect(context) {
        var controllerFuture: ListenableFuture<MediaController>? = null
        
        val job = CoroutineScope(Dispatchers.Main).launch {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, PlayerService::class.java)
            )
            // Small retry loop to handle racey service/session availability
            var attempts = 0
            var connected = false
            var lastError: Exception? = null
            while (attempts < 3 && !connected) {
                try {
                    controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                    controller = controllerFuture.await()
                    connected = (controller != null)
                } catch (e: Exception) {
                    lastError = e
                    try { controllerFuture?.cancel(true) } catch (_: Exception) {}
                    // Backoff
                    kotlinx.coroutines.delay(400L * (attempts + 1))
                }
                attempts++
            }
            if (!connected && lastError != null) {
                android.util.Log.e("MediaControllerHelper", "Failed to connect to MediaController", lastError)
            }
        }
        
        onDispose {
            job.cancel()
            controller?.release()
            controller = null
            try {
                controllerFuture?.cancel(true)
            } catch (_: Exception) {}
        }
    }
    
    return controller
}

/**
 * Helper function to resolve MediaItem to Track with repository lookup
 */
fun resolveTrack(
    mediaItem: MediaItem?,
    repository: LibraryRepository
): Track? {
    return mediaItem?.let { item ->
        val repoTrack = repository.getTrackByMediaId(item.mediaId)
        if (repoTrack != null) {
            android.util.Log.d("MediaControllerHelper", "Found track in repository: ${repoTrack.title}, artUri: ${repoTrack.artUri}")
            repoTrack
        } else {
            android.util.Log.d("MediaControllerHelper", "Track not found in repository, using MediaItem: ${item.mediaId}")
            item.toTrack()
        }
    }
}

/**
 * Helper class to restore playback state
 */
class PlaybackRestorer(
    private val context: Context,
    private val repository: LibraryRepository,
    private val stateStore: PlaybackStateStore
) {
    suspend fun restoreLastPlaybackState(mediaController: MediaController?): Track? {
        return withContext(Dispatchers.IO) {
            try {
                val state = stateStore.load()
                val trackId = state?.mediaIds?.getOrNull(state.index ?: 0)
                trackId?.let { repository.getTrackByMediaId(it) }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackRestorer", "Failed to restore playback state", e)
                null
            }
        }
    }
    
    suspend fun restoreQueueAndPlay(
        mediaController: MediaController?,
        onPlay: (List<Track>, Int) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val state = stateStore.load()
                if (state != null && !state.mediaIds.isNullOrEmpty()) {
                    val tracks = state.mediaIds.mapNotNull { id ->
                        repository.getTrackByMediaId(id)
                    }
                    if (tracks.isNotEmpty()) {
                        val safeIndex = state.index?.coerceIn(0, tracks.size - 1) ?: 0
                        withContext(Dispatchers.Main) {
                            onPlay(tracks, safeIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackRestorer", "Failed to restore queue", e)
            }
        }
    }
}

/**
 * Composable wrapper for MediaController listener with proper lifecycle
 */
@Composable
fun MediaControllerListener(
    controller: MediaController?,
    onMediaItemTransition: ((MediaItem?, Int) -> Unit)? = null,
    onIsPlayingChanged: ((Boolean) -> Unit)? = null,
    onPlaybackStateChanged: ((Int) -> Unit)? = null,
    onTimelineChanged: ((Timeline, Int) -> Unit)? = null
) {
    DisposableEffect(controller) {
        if (controller == null) {
            return@DisposableEffect onDispose { }
        }

        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onMediaItemTransition?.invoke(mediaItem, reason)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onIsPlayingChanged?.invoke(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                onPlaybackStateChanged?.invoke(playbackState)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                onTimelineChanged?.invoke(timeline, reason)
            }
        }

        controller.addListener(listener)

        onDispose {
            controller.removeListener(listener)
        }
    }
}
