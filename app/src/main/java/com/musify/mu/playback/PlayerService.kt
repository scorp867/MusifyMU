package com.musify.mu.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musify.mu.ui.MainActivity
import com.musify.mu.R
import com.musify.mu.data.repo.LibraryRepository
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

    private lateinit var player: ExoPlayer
    private lateinit var queue: QueueManager
    private lateinit var repo: LibraryRepository
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var queueStateStore: QueueStateStore
    private lateinit var audioFocusManager: AudioFocusManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
    private var isNotificationActive = false
    private var isInForeground = false
    private var hasValidMedia = false
    private var currentNotificationBuilder: NotificationCompat.Builder? = null

    override fun onCreate() {
        super.onCreate()
        
        repo = LibraryRepository.get(this)
        stateStore = PlaybackStateStore(this)
        queueStateStore = QueueStateStore(this)
        player = ExoPlayer.Builder(this).build()
        queue = QueueManager(player, queueStateStore)
        
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
            ): ListenableFuture<MutableList<MediaItem>> {
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
                if (hasValidMedia) {
                    if (isPlaying) {
                        audioFocusManager.request()
                        startOrPromoteToForeground()
                    } else {
                        audioFocusManager.abandon()
                        // Do not remove the notification; keep it and just update ongoing flag
                        demoteFromForegroundKeepNotification()
                    }
                    // Update play/pause visual state in notification without recreating
                    updateNotification()
                }
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Record the track as played when transitioning to it
                mediaItem?.let { item ->
                    serviceScope.launch(Dispatchers.IO) {
                        repo.recordPlayed(item.mediaId)
                        // If we advanced to next item, decrement play-next count if needed
                        queueStateStore.decrementOnAdvance()
                    }
                }
                
                // Update notification with new track info
                if (isNotificationActive && hasValidMedia) {
                    updateNotification()
                }
            }
            
            override fun onEvents(player: Player, events: Player.Events) {
                // Save playback state
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

        // Try to restore last session state
        serviceScope.launch(Dispatchers.IO) {
            try {
                val state = stateStore.load()
                if (state != null && state.mediaIds.isNotEmpty()) {
                    // Validate that media files still exist
                    val validTracks = state.mediaIds.mapNotNull { id -> 
                        repo.getTrackByMediaId(id)?.let { track ->
                            try {
                                val uri = Uri.parse(track.mediaId)
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
                                val validPosMs = if (state.posMs > 0L) state.posMs else 0L
                                val validIndex = state.index.coerceIn(0, items.size - 1)
                                
                                queue.setQueue(items, validIndex, play = false, startPosMs = validPosMs)
                                player.repeatMode = state.repeat
                                player.shuffleModeEnabled = state.shuffle
                                hasValidMedia = true
                                
                                // Start foreground if it was playing previously, otherwise post background notification
                                if (state.play && items.isNotEmpty()) {
                                    player.play()
                                } else {
                                    // Post a non-foreground notification to allow seamless controls and avoid flicker later
                                    postOrUpdateBackgroundNotification()
                                }
                            } catch (e: Exception) {
                                // If restoration fails, just set the queue without position
                                queue.setQueue(items, 0, play = false, startPosMs = 0L)
                                hasValidMedia = true
                                postOrUpdateBackgroundNotification()
                            }
                        }
                    } else {
                        // Clear invalid state if no valid tracks found
                        stateStore.clear()
                        launch(Dispatchers.Main) {
                            stopSelf()
                        }
                    }
                } else {
                    // No state to restore, stop the service
                    launch(Dispatchers.Main) {
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PlayerService", "Failed to restore playback state", e)
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
        // Do not clear playback state to allow seamless resume on next launch
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
                    queue.setQueue(items, validStartIndex, play = true, startPosMs = validStartPos)
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
    
    private fun startOrPromoteToForeground() {
        if (!isNotificationActive) {
            createNotificationChannel()
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            isNotificationActive = true
            isInForeground = true
        } else if (!isInForeground) {
            // Already showing a background notification, promote to foreground
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            isInForeground = true
        }
    }
    
    private fun demoteFromForegroundKeepNotification() {
        if (isInForeground) {
            // Keep the notification visible to avoid flicker
            stopForeground(false)
            isInForeground = false
            isNotificationActive = true
        }
    }
    
    private fun stopForegroundService() {
        if (isNotificationActive || isInForeground) {
            stopForeground(true)
            isInForeground = false
            isNotificationActive = false
            currentNotificationBuilder = null
        }
    }
    
    private fun postOrUpdateBackgroundNotification() {
        createNotificationChannel()
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
        isNotificationActive = true
        isInForeground = false
    }
    
    private fun updateNotification() {
        if (hasValidMedia) {
            val notification = createNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            isNotificationActive = true
        }
    }
    
    private fun createNotification(): Notification {
        val currentItem = player.currentMediaItem
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Musify MU"
        val artist = currentItem?.mediaMetadata?.artist?.toString() ?: "Unknown Artist"
        val artworkUri = currentItem?.mediaMetadata?.artworkUri
        
        // Create or reuse notification builder to prevent flickering
        if (currentNotificationBuilder == null) {
            currentNotificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentIntent(createPlayerActivityIntent())
                .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaLibrarySession?.sessionCompatToken))
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }
        
        currentNotificationBuilder?.apply {
            setOngoing(player.isPlaying)
            setContentTitle(title)
            setContentText(artist)
            
            // Load and set artwork
            if (artworkUri != null) {
                try {
                    val bitmap = loadArtworkBitmap(artworkUri.toString())
                    if (bitmap != null) setLargeIcon(bitmap) else setLargeIcon(null as Bitmap?)
                } catch (e: Exception) {
                    android.util.Log.w("PlayerService", "Failed to load notification artwork", e)
                    setLargeIcon(null as Bitmap?)
                }
            } else {
                setLargeIcon(null as Bitmap?)
            }
        }
        
        return currentNotificationBuilder!!.build()
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
