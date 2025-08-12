package com.musify.mu.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.musify.mu.data.repo.QueueStateStore

class QueueManager(private val player: ExoPlayer, private val queueState: QueueStateStore? = null) {

    fun setQueue(
        items: List<MediaItem>,
        startIndex: Int = 0,
        play: Boolean = true,
        startPosMs: Long = 0L
    ) {
        val validStartIndex = startIndex.coerceIn(0, items.size - 1)
        player.setMediaItems(items, validStartIndex, 0L)
        player.prepare()
        if (startPosMs > 0L) {
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        val duration = player.duration
                        if (duration != androidx.media3.common.C.TIME_UNSET && startPosMs < duration) {
                            player.seekTo(startPosMs)
                        }
                        player.removeListener(this)
                    }
                }
            })
        }
        if (play) player.play()
        // Reset play-next region when setting a brand-new queue
        queueState?.let { qs ->
            CoroutineScope(Dispatchers.IO).launch { qs.setPlayNextCount(0) }
        }
    }

    fun addToEnd(items: List<MediaItem>) {
        player.addMediaItems(items)
    }

    fun playNext(items: List<MediaItem>) {
        val insertIndex = (player.currentMediaItemIndex + 1)
            .coerceAtMost(player.mediaItemCount)
        player.addMediaItems(insertIndex, items)
        queueState?.let { qs ->
            CoroutineScope(Dispatchers.IO).launch {
                val current = qs.getPlayNextCount()
                qs.setPlayNextCount(current + items.size)
            }
        }
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        val item = player.getMediaItemAt(from)
        player.removeMediaItem(from)
        player.addMediaItem(to, item)
    }

    fun removeAt(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.removeMediaItem(index)
            // If removing inside play-next region, adjust count accordingly
            queueState?.let { qs ->
                CoroutineScope(Dispatchers.IO).launch {
                    val current = qs.getPlayNextCount()
                    val playNextStart = (player.currentMediaItemIndex + 1).coerceAtLeast(0)
                    if (index in playNextStart until (playNextStart + current)) {
                        qs.setPlayNextCount((current - 1).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    fun setRepeat(mode: Int) {
        player.repeatMode = mode
    }

    fun setShuffle(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    fun snapshotIds(): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
}
