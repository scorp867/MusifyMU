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
        player.setMediaItems(items, startIndex, startPosMs)
        player.prepare()
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
