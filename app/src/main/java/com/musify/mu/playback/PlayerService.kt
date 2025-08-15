package com.musify.mu.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musify.mu.ui.MainActivity
import com.musify.mu.R
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.LyricsStateStore
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

class PlayerService : MediaLibraryService() {

    private var _player: ExoPlayer? = null
    private val player: ExoPlayer get() = _player ?: throw IllegalStateException("Player not initialized")
    private var _queue: QueueManager? = null
    private val queue: QueueManager get() = _queue ?: throw IllegalStateException("Queue not initialized")
    private lateinit var repo: LibraryRepository
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var queueStateStore: QueueStateStore
    private lateinit var lyricsStateStore: LyricsStateStore
    private lateinit var audioFocusManager: AudioFocusManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastLoadedLyricsId: String? = null

    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
    private var hasValidMedia = false

    private fun ensurePlayerInitialized() {
        if (_player != null && _queue != null && _player == mediaLibrarySession?.player) return
        android.util.Log.d("PlayerService", "Initializing player and queue")
        val newPlayer = _player ?: ExoPlayer.Builder(this).build().also { _player = it }
        if (_queue == null) _queue = QueueManager(newPlayer, queueStateStore)
        QueueManagerProvider.setInstance(queue)
        
        // Update the session with the new player
        mediaLibrarySession?.player = newPlayer

        // Attach listeners once
        newPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (hasValidMedia) {
                    if (isPlaying) audioFocusManager.request() else audioFocusManager.abandon()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // We'll load lyrics in onMediaItemTransition instead
                // This avoids duplicate loading
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { item ->
                    queue.onTrackChanged(item.mediaId)
                    serviceScope.launch(Dispatchers.IO) {
                        // Record track as played
                        repo.recordPlayed(item.mediaId)
                        queueStateStore.decrementOnAdvance()
                        
                        // Load lyrics only if it's a different track
                        if (lastLoadedLyricsId != item.mediaId) {
                            lastLoadedLyricsId = item.mediaId
                            android.util.Log.d("PlayerService", "onMediaItemTransition: Loading lyrics for ${item.mediaId}")
                            try { 
                                lyricsStateStore.loadLyrics(item.mediaId)
                            } catch (e: Exception) {
                                android.util.Log.e("PlayerService", "Failed to load lyrics on transition", e)
                            }
                        } else {
                            android.util.Log.d("PlayerService", "Skipping lyrics load - already loaded for ${item.mediaId}")
                        }
                    }
                }
            }
        })
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        repo = LibraryRepository.get(this)
        stateStore = PlaybackStateStore(this)
        queueStateStore = QueueStateStore(this)
        lyricsStateStore = LyricsStateStore.getInstance(this)
        // Lazy: player and queue are created on first playback request
        
        // Add player listener for track changes and queue updates
        // Attach listeners only when player exists
        // They will be registered in ensurePlayerInitialized()
        /*
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                mediaItem?.let { item ->
                    // Notify QueueManager of track changes for history tracking
                    queue.onTrackChanged(item.mediaId)
                    
                    // Load lyrics for the new track in background
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            android.util.Log.d("PlayerService", "Loading lyrics for track: ${item.mediaId}")
                            lyricsStateStore.loadLyrics(item.mediaId)
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerService", "Failed to load lyrics for ${item.mediaId}", e)
                        }
                    }
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Update playback state store when playback state changes
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> {
                        // Ensure lyrics are loaded for current track when player becomes ready
                        player.currentMediaItem?.let { item ->
                            serviceScope.launch(Dispatchers.IO) {
                                try {
                                    lyricsStateStore.loadLyrics(item.mediaId)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        // Playback ended, could trigger auto-play next or recommendations
                    }
                }
            }
        })
        */
        
        // Initialize audio focus manager
        audioFocusManager = AudioFocusManager(this) { focusChange ->
            when (focusChange) {
                android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                    player.volume = 1.0f
                    if (!player.isPlaying && player.playWhenReady) {
                        player.play()
                    }
                }
                android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                    player.pause()
                }
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    player.pause()
                }
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    player.volume = 0.3f
                }
            }
        }

        val callback = object : MediaLibraryService.MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val md = MediaMetadata.Builder().setTitle("Musify MU Library").build()
                val result = LibraryResult.ofItem(
                    MediaItem.Builder().setMediaId("root").setMediaMetadata(md).build(),
                    params
                )
                return Futures.immediateFuture(result)
            }

            override fun onGetItem(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val future = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<MediaItem>>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val item = repo.getTrackByMediaId(mediaId)?.toMediaItem()
                        val result = if (item != null) LibraryResult.ofItem(item, null)
                        else LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                        future.set(result)
                    } catch (e: Exception) {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO))
                    }
                }
                return future
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                // Ensure player is initialized when media items are added
                ensurePlayerInitialized()
                android.util.Log.d("PlayerService", "onAddMediaItems called with ${mediaItems.size} items")
                
                val future = com.google.common.util.concurrent.SettableFuture.create<MutableList<MediaItem>>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val resolved = mediaItems.map { it.mediaId }
                            .mapNotNull { id -> repo.getTrackByMediaId(id)?.toMediaItem() }
                            .toMutableList()
                        future.set(resolved)
                    } catch (e: Exception) {
                        future.set(mutableListOf())
                    }
                }
                return future
            }
        }

        // Build session with a placeholder player that will be replaced on first playback
        val placeholderPlayer = ExoPlayer.Builder(this).build()
        _queue = QueueManager(placeholderPlayer, queueStateStore)
        QueueManagerProvider.setInstance(queue)
        
        mediaLibrarySession = MediaLibraryService.MediaLibrarySession.Builder(this, placeholderPlayer, callback)
            .setId("MusifyMU_Session")
            .setShowPlayButtonIfPlaybackIsSuppressed(false)
            .setSessionActivity(createPlayerActivityIntent())
            .build()

        // Attach minimal listeners to the placeholder player as well, so we log/track before lazy init
        placeholderPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { item ->
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            // Record as played and attempt lyrics load once
                            repo.recordPlayed(item.mediaId)
                            if (lastLoadedLyricsId != item.mediaId) {
                                lastLoadedLyricsId = item.mediaId
                                android.util.Log.d("PlayerService", "[placeholder] onMediaItemTransition: Loading lyrics for ${item.mediaId}")
                                lyricsStateStore.loadLyrics(item.mediaId)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerService", "[placeholder] Failed in transition handler", e)
                        }
                    }
                }
            }
        })

        /*
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (hasValidMedia) {
                    if (isPlaying) {
                        audioFocusManager.request()
                    } else {
                        audioFocusManager.abandon()
                    }
                }
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Record the track as played when transitioning to it
                mediaItem?.let { item ->
                    serviceScope.launch(Dispatchers.IO) {
                        repo.recordPlayed(item.mediaId)
                        // If we advanced to next item, decrement play-next count if needed
                        queueStateStore.decrementOnAdvance()
                        
                        // Load lyrics for the new track
                        try {
                            android.util.Log.d("PlayerService", "Loading lyrics for new track: ${item.mediaId}")
                            lyricsStateStore.loadLyrics(item.mediaId)
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerService", "Failed to load lyrics", e)
                        }
                    }
                }
            }
            
            override fun onEvents(player: Player, events: Player.Events) {
                // Save playback state
                serviceScope.launch {
                    val ids = queue.getQueueSnapshot().map { it.id }
                    val index = player.currentMediaItemIndex
                    val pos = player.currentPosition
                    val repeat = player.repeatMode
                    val shuffle = player.shuffleModeEnabled
                    val play = player.playWhenReady
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        stateStore.save(ids, index, pos, repeat, shuffle, play)
                    }
                }
                
                // Check if we have valid media
                hasValidMedia = player.mediaItemCount > 0
                
                // Handle service lifecycle
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    if (player.mediaItemCount == 0) {
                        // No media items, stop service
                        stopForegroundService()
                        stopSelf()
                    }
                }
            }
        })
        */

        // We no longer show our own persistent notification; Android system may show media controls if the session is active.

        // Do not restore last session state at launch to avoid I/O and buffering before user intent.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Stop the service when app is swiped away from recents
        android.util.Log.d("PlayerService", "App swiped away from recents - stopping service")
        
        // Only operate on player if it's initialized
        _player?.let { p ->
            // Stop playback immediately
            p.stop()
            p.clearMediaItems()
        }
        
        // Clear queue state in coroutine
        serviceScope.launch {
            try {
                _queue?.clearQueue(keepCurrent = false)
            } catch (e: Exception) {
                android.util.Log.e("PlayerService", "Error clearing queue on task removed", e)
            }
        }
        
        // Abandon audio focus
        audioFocusManager.abandon()
        
        // Stop foreground service and clear notifications
        stopForegroundService()
        
        // Stop the service completely
        serviceScope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        audioFocusManager.abandon()
        stopForegroundService()
        mediaLibrarySession?.release()
        _player?.release()
        _player = null
        _queue = null
        serviceScope.cancel()
        
        // Clear state on destroy
        serviceScope.launch(Dispatchers.IO) {
            try {
                stateStore.clear()
            } catch (e: Exception) {
                android.util.Log.w("PlayerService", "Failed to clear state on destroy", e)
            }
        }
        
        super.onDestroy()
    }

    // Public helper for other components to start playback for a list of media IDs
    fun playMediaIds(ids: List<String>, startIndex: Int = 0, startPos: Long = 0L) {
        serviceScope.launch {
            try {
                val tracks = repo.getAllTracks().filter { ids.contains(it.mediaId) }
                
                // Validate that media files exist
                val validTracks = tracks.filter { track ->
                    try {
                        val uri = Uri.parse(track.mediaId)
                        val inputStream = this@PlayerService.contentResolver.openInputStream(uri)
                        inputStream?.close()
                        true
                    } catch (e: Exception) {
                        android.util.Log.w("PlayerService", "Media file not found: ${track.mediaId}")
                        false
                    }
                }
                
                val items = validTracks.map { it.toMediaItem() }
                if (items.isNotEmpty()) {
                    val validStartIndex = startIndex.coerceIn(0, items.size - 1)
                    val validStartPos = if (startPos > 0L) startPos else 0L
                    serviceScope.launch { queue.setQueue(items, validStartIndex, play = true, startPosMs = validStartPos) }
                    hasValidMedia = true
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerService", "Failed to play media IDs", e)
            }
        }
    }
    
    private fun createPlayerActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "player")
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private fun stopForegroundService() {
        // Stop foreground service and remove notifications
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            // Also clear any system media notifications
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()
            android.util.Log.d("PlayerService", "Stopped foreground service and cleared notifications")
        } catch (e: Exception) {
            android.util.Log.e("PlayerService", "Error stopping foreground service", e)
        }
    }
    

    
    private fun loadArtworkBitmap(artworkUri: String): Bitmap? {
        return try {
            val uri = Uri.parse(artworkUri)
            when {
                uri.scheme == "content" -> {
                    // Handle content:// URIs (MediaStore)
                    contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                uri.scheme == "file" -> {
                    // Handle file:// URIs
                    BitmapFactory.decodeFile(uri.path)
                }
                else -> {
                    // Try to decode as file path
                    BitmapFactory.decodeFile(artworkUri)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PlayerService", "Failed to load artwork: $artworkUri", e)
            null
        }
    }
    
    private fun createNotificationChannel() { /* no-op */ }
    
    companion object {
        private const val CHANNEL_ID = "PLAYBACK_CHANNEL"
        private const val NOTIFICATION_ID = 1001
    }
}
