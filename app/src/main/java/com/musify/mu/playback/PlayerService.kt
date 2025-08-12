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
import androidx.media3.ui.PlayerNotificationManager

class PlayerService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var queue: QueueManager
    private lateinit var repo: LibraryRepository
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var audioFocusManager: AudioFocusManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
    private var playerNotificationManager: PlayerNotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        
        // Create notification channel for media playback
        createNotificationChannel()
        
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
                } else {
                    audioFocusManager.abandon()
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
            }
        })

        // Try to restore last session state
        serviceScope.launch(Dispatchers.IO) {
            val state = stateStore.load()
            if (state != null) {
                val items = state.mediaIds.mapNotNull { id -> repo.getTrackByMediaId(id)?.toMediaItem() }
                launch(Dispatchers.Main) {
                    queue.setQueue(items, state.index, play = state.play, startPosMs = state.posMs)
                    player.repeatMode = state.repeat
                    player.shuffleModeEnabled = state.shuffle
                }
            }
        }

        setupPlayerNotification()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Stop the service when app is swiped away from recents
        player.pause()
        stopSelf()
    }

    override fun onDestroy() {
        audioFocusManager.abandon()
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
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "PLAYBACK_CHANNEL",
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

    private fun setupPlayerNotification() {
        val session = mediaLibrarySession ?: return
        val notificationId = 1001
        val channelId = "PLAYBACK_CHANNEL"

        val builder = PlayerNotificationManager.Builder(this, notificationId, channelId)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    val md = player.currentMediaItem?.mediaMetadata
                    return md?.title ?: ""
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? {
                    return createPlayerActivityIntent()
                }

                override fun getCurrentContentText(player: Player): CharSequence? {
                    val md = player.currentMediaItem?.mediaMetadata
                    return md?.artist
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): android.graphics.Bitmap? {
                    // No async artwork loading here for simplicity; rely on media style to show session art if provided
                    return null
                }
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(
                    notificationId: Int,
                    notification: Notification,
                    ongoing: Boolean
                ) {
                    if (ongoing) {
                        startForeground(notificationId, notification)
                    } else {
                        // Not ongoing: ensure service is not in foreground but keep notification for controls
                        stopForeground(false)
                    }
                }

                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    stopForeground(true)
                    stopSelf()
                }
            })

        playerNotificationManager = builder.build().apply {
            setUseNextAction(true)
            setUseNextActionInCompactView(true)
            setUsePreviousAction(true)
            setUsePreviousActionInCompactView(true)
            setUsePlayPauseActions(true)
            setSmallIcon(R.mipmap.ic_launcher)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setMediaSessionToken(session.sessionCompatToken)
            setPlayer(player)
        }
    }
}
