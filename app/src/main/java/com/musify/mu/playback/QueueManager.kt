package com.musify.mu.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class QueueManager(private val player: ExoPlayer) {

    fun setQueue(
        items: List<MediaItem>,
        startIndex: Int = 0,
        play: Boolean = true,
        startPosMs: Long = 0L
    ) {
        // Validate startIndex
        val validStartIndex = startIndex.coerceIn(0, items.size - 1)
        
        // Set media items without start position first to avoid IllegalSeekPositionException
        player.setMediaItems(items, validStartIndex, 0L)
        player.prepare()
        
        // If we need to seek to a specific position, do it after preparation
        if (startPosMs > 0L) {
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        // Only seek if the position is valid
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
    }

    fun addToEnd(items: List<MediaItem>) {
        player.addMediaItems(items)
    }

    fun playNext(items: List<MediaItem>) {
        val insertIndex = (player.currentMediaItemIndex + 1)
            .coerceAtMost(player.mediaItemCount)
        player.addMediaItems(insertIndex, items)
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
