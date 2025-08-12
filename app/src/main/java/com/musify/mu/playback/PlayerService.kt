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
                    // Check if we should stop the service entirely
                    checkIfServiceShouldStop()
                }
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Record the track as played when transitioning to it
                mediaItem?.let { item ->
                    serviceScope.launch(Dispatchers.IO) {
                        repo.recordPlayed(item.mediaId)
                    }
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

        // Try to restore last session state
        serviceScope.launch(Dispatchers.IO) {
            val state = stateStore.load()
            if (state != null && state.mediaIds.isNotEmpty()) {
                val items = state.mediaIds.mapNotNull { id -> repo.getTrackByMediaId(id)?.toMediaItem() }
                if (items.isNotEmpty()) {
                    launch(Dispatchers.Main) {
                        queue.setQueue(items, state.index, play = false, startPosMs = state.posMs)
                        player.repeatMode = state.repeat
                        player.shuffleModeEnabled = state.shuffle
                        // Only start playing if it was playing before and we have valid tracks
                        if (state.play && items.isNotEmpty()) {
                            player.play()
                        }
                    }
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
        stopSelf()
    }

    override fun onDestroy() {
        audioFocusManager.abandon()
        stopForegroundService()
        mediaLibrarySession?.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // Public helper for other components to start playback for a list of media IDs
    fun playMediaIds(ids: List<String>, startIndex: Int = 0, startPos: Long = 0L) {
        serviceScope.launch {
            val tracks = repo.getAllTracks().filter { ids.contains(it.mediaId) }
            val items = tracks.map { it.toMediaItem() }
            queue.setQueue(items, startIndex, play = true, startPosMs = startPos)
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
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(createPlayerActivityIntent())
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaLibrarySession?.sessionCompatToken))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun checkIfServiceShouldStop() {
        // Stop the service if there's no media in the queue and we're not playing
        if (player.mediaItemCount == 0 && !player.isPlaying) {
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
