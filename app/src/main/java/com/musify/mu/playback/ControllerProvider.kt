package com.musify.mu.playback

import androidx.compose.runtime.compositionLocalOf
import androidx.media3.session.MediaController

val LocalMediaController = compositionLocalOf<MediaController?> { null }

// Singleton to hold the QueueManager instance
object QueueManagerProvider {
    @Volatile
    private var instance: QueueManager? = null
    
    fun initialize(queueManager: QueueManager) {
        instance = queueManager
    }
    
    fun get(): QueueManager? = instance
    
    fun clear() {
        instance = null
    }
}


