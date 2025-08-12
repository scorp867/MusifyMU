package com.musify.mu.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musify.mu.ui.MainActivity
import com.musify.mu.R
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.util.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PlayerService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var queue: QueueManager
    private lateinit var repo: LibraryRepository
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var audioFocusManager: AudioFocusManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
    private var isNotificationActive = false

    override fun onCreate() {
        super.onCreate()
        
        // Only create notification channel when needed, not on service creation
        // createNotificationChannel() - REMOVED from here
        
        repo = LibraryRepository.get(this)
        stateStore = PlaybackStateStore(this)
        player = ExoPlayer.Builder(this).build()
        queue = QueueManager(player)
        
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
                val item = runBlocking(Dispatchers.IO) {
                    repo.getTrackByMediaId(mediaId)?.toMediaItem()
                }
                val result = if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                return Futures.immediateFuture(result)
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): com.google.common.util.concurrent.ListenableFuture<MutableList<MediaItem>> {
                val resolved = runBlocking(Dispatchers.IO) {
                    mediaItems.map { it.mediaId }
                        .mapNotNull { id -> repo.getTrackByMediaId(id)?.toMediaItem() }
                        .toMutableList()
                }
                return Futures.immediateFuture(resolved)
            }
        }

        mediaLibrarySession = MediaLibraryService.MediaLibrarySession.Builder(this, player, callback)
            .setId("MusifyMU_Session")
            .setShowPlayButtonIfPlaybackIsSuppressed(false)
            .setSessionActivity(createPlayerActivityIntent())
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    audioFocusManager.request()
                    // Start foreground service with notification only when playing
                    if (!isNotificationActive) {
                        startForegroundService()
                    }
                } else {
                    audioFocusManager.abandon()
                    // Stop foreground service when not playing
                    if (isNotificationActive) {
                        stopForegroundService()
                    }
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Handle service lifecycle based on playback state
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        // Service should stop when idle
                        if (isNotificationActive) {
                            stopForegroundService()
                        }
                        checkIfServiceShouldStop()
                    }
                    Player.STATE_ENDED -> {
                        // Service should stop when playback ends
                        if (isNotificationActive) {
                            stopForegroundService()
                        }
                        checkIfServiceShouldStop()
                    }
                }
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Record the track as played when transitioning to it
                mediaItem?.let { item ->
                    serviceScope.launch(Dispatchers.IO) {
                        repo.recordPlayed(item.mediaId)
                    }
                }
                
                // Update notification with new track info
                if (isNotificationActive) {
                    updateNotification()
                }
            }
            
            override fun onEvents(player: Player, events: Player.Events) {
                // Access player on its application thread (main), persist off-thread
                serviceScope.launch {
                    val ids = queue.snapshotIds()
                    val index = player.currentMediaItemIndex
                    val pos = player.currentPosition
                    val repeat = player.repeatMode
                    val shuffle = player.shuffleModeEnabled
                    val play = player.playWhenReady
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        stateStore.save(ids, index, pos, repeat, shuffle, play)
                    }
                }
                
                // Check if queue became empty
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && player.mediaItemCount == 0) {
                    checkIfServiceShouldStop()
                }
            }
        })

        // Clear old artwork cache and refresh library
        serviceScope.launch(Dispatchers.IO) {
            try {
                repo.clearArtworkCache()
            } catch (e: Exception) {
                android.util.Log.w("PlayerService", "Failed to clear artwork cache", e)
            }
        }
        
        // Try to restore last session state
        serviceScope.launch(Dispatchers.IO) {
            try {
                val state = stateStore.load()
                if (state != null && state.mediaIds.isNotEmpty()) {
                    // Validate that media files still exist before creating MediaItems
                    val validTracks = state.mediaIds.mapNotNull { id -> 
                        repo.getTrackByMediaId(id)?.let { track ->
                            // Check if the file still exists
                            try {
                                val uri = android.net.Uri.parse(track.mediaId)
                                val inputStream = this@PlayerService.contentResolver.openInputStream(uri)
                                inputStream?.close()
                                track
                            } catch (e: Exception) {
                                android.util.Log.w("PlayerService", "Media file not found: ${track.mediaId}")
                                null
                            }
                        }
                    }
                    
                    if (validTracks.isNotEmpty()) {
                        val items = validTracks.map { it.toMediaItem() }
                        launch(Dispatchers.Main) {
                            try {
                                // Validate position before setting queue
                                val validPosMs = if (state.posMs > 0L) state.posMs else 0L
                                val validIndex = state.index.coerceIn(0, items.size - 1)
                                
                                queue.setQueue(items, validIndex, play = false, startPosMs = validPosMs)
                                player.repeatMode = state.repeat
                                player.shuffleModeEnabled = state.shuffle
                                // Only start playing if it was playing before and we have valid tracks
                                if (state.play && items.isNotEmpty()) {
                                    player.play()
                                }
                            } catch (e: Exception) {
                                // If restoration fails, just set the queue without position
                                queue.setQueue(items, 0, play = false, startPosMs = 0L)
                            }
                        }
                    } else {
                        // Clear invalid state if no valid tracks found
                        stateStore.clear()
                        // Stop the service if no valid media found
                        launch(Dispatchers.Main) {
                            stopForegroundService()
                            stopSelf()
                        }
                    }
                } else {
                    // No state to restore, stop the service
                    launch(Dispatchers.Main) {
                        stopForegroundService()
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                // Log error but don't crash the service
                android.util.Log.w("PlayerService", "Failed to restore playback state", e)
                // Stop the service on error
                launch(Dispatchers.Main) {
                    stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Stop the service when app is swiped away from recents
        player.pause()
        stopForegroundService()
        // Clear state and stop service immediately
        serviceScope.launch(Dispatchers.IO) {
            try {
                stateStore.clear()
            } catch (e: Exception) {
                android.util.Log.w("PlayerService", "Failed to clear state", e)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        audioFocusManager.abandon()
        stopForegroundService()
        mediaLibrarySession?.release()
        player.release()
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
                
                // Validate that media files exist before creating MediaItems
                val validTracks = tracks.filter { track ->
                    try {
                        val uri = android.net.Uri.parse(track.mediaId)
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
                    queue.setQueue(items, validStartIndex, play = true, startPosMs = validStartPos)
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerService", "Failed to play media IDs", e)
            }
        }
    }
    
    private fun createPlayerActivityIntent(): PendingIntent {
        val intent = Intent(this, com.musify.mu.ui.MainActivity::class.java).apply {
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
    
    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        isNotificationActive = true
    }
    
    private fun stopForegroundService() {
        if (isNotificationActive) {
            stopForeground(true)
            isNotificationActive = false
        }
    }
    
    private fun createNotification(): Notification {
        val currentItem = player.currentMediaItem
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Musify MU"
        val artist = currentItem?.mediaMetadata?.artist?.toString() ?: "Unknown Artist"
        val artworkUri = currentItem?.mediaMetadata?.artworkUri
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(createPlayerActivityIntent())
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaLibrarySession?.sessionCompatToken))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
        
        // Add artwork if available
        artworkUri?.let { uri ->
            try {
                builder.setLargeIcon(android.graphics.BitmapFactory.decodeFile(uri.path))
            } catch (e: Exception) {
                android.util.Log.w("PlayerService", "Failed to load notification artwork", e)
            }
        }
        
        return builder.build()
    }
    
    private fun updateNotification() {
        if (isNotificationActive) {
            val notification = createNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
    
    private fun checkIfServiceShouldStop() {
        // Stop the service if there's no media in the queue and we're not playing
        if (player.mediaItemCount == 0 && !player.isPlaying) {
            // Clear the state when stopping the service
            serviceScope.launch(Dispatchers.IO) {
                try {
                    stateStore.clear()
                } catch (e: Exception) {
                    android.util.Log.w("PlayerService", "Failed to clear state", e)
                }
            }
            stopForegroundService()
            stopSelf()
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    companion object {
        private const val CHANNEL_ID = "PLAYBACK_CHANNEL"
        private const val NOTIFICATION_ID = 1001
    }
}
