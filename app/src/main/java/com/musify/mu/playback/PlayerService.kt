package com.musify.mu.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
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
    private var currentMediaIdCache: String? = null

    private fun ensurePlayerInitialized() {
        if (_player != null && _queue != null && _player == mediaLibrarySession?.player) return
        android.util.Log.d("PlayerService", "Initializing player and queue")

        val newPlayer = _player ?: ExoPlayer.Builder(this).build().also { _player = it }

        // Snapshot any existing queue to migrate to the real player-bound manager
        val prevItems = _queue?.getQueueSnapshot()?.map { it.mediaItem } ?: emptyList()
        val prevIndex = _queue?.getCurrentIndex() ?: 0

        // Always bind QueueManager to the actual player used by the session
        _queue = QueueManager(newPlayer, queueStateStore)
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { item ->
                    // Remove consumed transient items (Play Next or User Queue) on any advance (auto or manual)
                    val finishedMediaId = currentMediaIdCache
                    if (finishedMediaId != null) {
                        serviceScope.launch(Dispatchers.Main) {
                            try {
                                // First try play-next head consume (preserves multi-play-next semantics)
                                val removedPlayNext = queue.consumePlayNextHeadIfMatches(finishedMediaId)
                                if (removedPlayNext) {
                                    queueStateStore.decrementOnAdvance()
                                } else {
                                    // If not play-next, remove the first matching USER_QUEUE item for this id
                                    // This ensures User Queue items are consumed after play
                                    val removedUser = queue.removeFirstUserQueueByMediaId(finishedMediaId)
                                    if (removedUser) {
                                        // No count to adjust for user queue
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    }
                    // Update current cached id to new current
                    currentMediaIdCache = item.mediaId
                    queue.onTrackChanged(item.mediaId)
                    // If the playlist was externally changed, resync; otherwise keep our sources intact
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                        serviceScope.launch(Dispatchers.Main) {
                            try { queue.syncFromPlayer(player) } catch (_: Exception) { }
                        }
                    }
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

        // Migrate previous queue (if any) into the new manager without starting playback
        if (prevItems.isNotEmpty()) {
            serviceScope.launch(Dispatchers.Main) {
                try {
                    queue.setQueue(
                        items = prevItems.toMutableList(),
                        startIndex = prevIndex.coerceAtLeast(0),
                        play = false,
                        startPosMs = 0L,
                        context = null
                    )
                } catch (e: Exception) {
                    android.util.Log.w("PlayerService", "Failed to migrate previous queue", e)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        repo = LibraryRepository.get(this)
        stateStore = PlaybackStateStore(this)
        queueStateStore = QueueStateStore(this)
        lyricsStateStore = LyricsStateStore.getInstance(this)
        // Lazy: player and queue are created on first playback request

        // Player listeners are attached in ensurePlayerInitialized() when needed

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
                        val resolved = mutableListOf<MediaItem>()
                        for (item in mediaItems) {
                            val id = item.mediaId
                            val dbItem = repo.getTrackByMediaId(id)?.toMediaItem()
                            if (dbItem != null) {
                                resolved.add(dbItem)
                            } else {
                                // Fallback: keep original if it's a direct URI or has request metadata
                                val hasDirectUri = (item.localConfiguration?.uri != null)
                                        || (item.requestMetadata.mediaUri != null)
                                        || id.startsWith("content://")
                                        || id.startsWith("file://")
                                if (hasDirectUri) {
                                    resolved.add(item)
                                }
                            }
                        }
                        // If nothing could be resolved, return original items to let player try URIs
                        if (resolved.isEmpty()) {
                            resolved.addAll(mediaItems)
                        }
                        hasValidMedia = resolved.isNotEmpty()
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

        // Placeholder player is just for session creation, no listeners needed

        // Player event handling is done in ensurePlayerInitialized()

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

        // Stop foreground service and clear notifications
        stopForeground(STOP_FOREGROUND_REMOVE)
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
                    serviceScope.launch { queue.setQueue(items.toMutableList(), validStartIndex, play = true, startPosMs = validStartPos) }
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


    companion object {
        // Service configuration constants
    }
}
