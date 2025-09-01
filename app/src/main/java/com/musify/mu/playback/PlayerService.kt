package com.musify.mu.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import com.musify.mu.ui.MainActivity
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.LyricsStateStore
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.data.db.entities.Track
import com.musify.mu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Temporary workaround - define extension function here
fun Track.toMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setGenre(genre)
                .setReleaseYear(year)
                .setTrackNumber(track)
                .setAlbumArtist(albumArtist)
                .build()
        )
        .build()
}

@UnstableApi
class PlayerService : MediaLibraryService() {

    companion object {
        private var mediaCache: SimpleCache? = null
        private val cacheMutex = Mutex()

        /**
         * Get or create Media3 cache with optimized settings for music streaming
         */
        suspend fun getOrCreateMediaCache(context: Context): SimpleCache = cacheMutex.withLock {
            mediaCache ?: run {
                val cacheDir = File(context.cacheDir, "media3_cache")
                val cacheEvictor = LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024L) // 500MB cache
                @Suppress("DEPRECATION")
                SimpleCache(cacheDir, cacheEvictor).also { mediaCache = it }
            }
        }

        /**
         * Create optimized ExoPlayer with Media3 caching
         */
        suspend fun createOptimizedExoPlayer(context: Context): ExoPlayer {
            val cache = getOrCreateMediaCache(context)

            // Create cache data source factory
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
                .setCacheWriteDataSinkFactory(null) // Disable writing to cache for now

            // Create media source factory with caching
            val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

            return ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        }
    }

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
        // If we already have a properly initialized player and queue, return
        if (_player != null && _queue != null && _player == mediaLibrarySession?.player) return

        android.util.Log.d("PlayerService", "Initializing player and queue")

        // Only create a new player if we don't have one
        val newPlayer = _player ?: runBlocking { createOptimizedExoPlayer(this@PlayerService) }.also { _player = it }

        // Only create QueueManager if we don't have one, to prevent duplicates
        if (_queue == null) {
            _queue = QueueManager(newPlayer, queueStateStore)
            QueueManagerProvider.setInstance(queue)

            // Attach listeners only once when QueueManager is first created
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

                override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                    super.onMediaMetadataChanged(metadata)

                    // Capture artwork bytes unavailable to MediaMetadataRetriever
                    val bytes = metadata.artworkData
                    val mediaId = player.currentMediaItem?.mediaId
                    if (bytes != null && mediaId != null) {
                        serviceScope.launch(Dispatchers.IO) {
                            val cached = com.musify.mu.util.OnDemandArtworkLoader.storeArtworkBytes(mediaId, bytes)
                            if (cached != null) {
                                try { repo.updateTrackArt(mediaId, cached) } catch (_: Exception) {}
                                com.musify.mu.util.OnDemandArtworkLoader.cacheUri(mediaId, cached)
                            }
                        }
                    } else if (mediaId != null && metadata.artworkUri != null) {
                        // Use Media3 artworkUri if present (e.g., notification extracted)
                        try {
                            val uriStr = metadata.artworkUri.toString()
                            serviceScope.launch(Dispatchers.IO) {
                                try { repo.updateTrackArt(mediaId, uriStr) } catch (_: Exception) {}
                                com.musify.mu.util.OnDemandArtworkLoader.cacheUri(mediaId, uriStr)
                            }
                        } catch (_: Exception) {}
                    }
                }
            })
        } else {
            // If we already have a QueueManager, just update the player reference
            android.util.Log.d("PlayerService", "Reusing existing QueueManager with new player")
        }

        // Update the session with the current player
        mediaLibrarySession?.player = newPlayer
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

        mediaLibrarySession = MediaLibraryService.MediaLibrarySession.Builder(this, placeholderPlayer, callback)
            .setId("MusifyMU_Session")
            .setShowPlayButtonIfPlaybackIsSuppressed(false)
            .setSessionActivity(createPlayerActivityIntent())
            .build()

        // Configure the service for media playback
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                android.util.Log.e("PlayerService", "Foreground service start not allowed")
            }
        })

        // Player and QueueManager will be created in ensurePlayerInitialized() when needed
        // This prevents duplicate creation and ensures proper initialization order

        // We no longer show our own persistent notification; Android system may show media controls if the session is active.

        // Do not restore last session state at launch to avoid I/O and buffering before user intent.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("PlayerService", "App swiped away from recents - stopping service")
        android.util.Log.d("PlayerService", "Media session available: ${mediaLibrarySession != null}")
        android.util.Log.d("PlayerService", "Player playing: ${_player?.isPlaying}")
        android.util.Log.d("PlayerService", "Player has media items: ${_player?.mediaItemCount}")

        // Stop playback immediately
        try {
            _player?.stop()
            _player?.clearMediaItems()
            android.util.Log.d("PlayerService", "Player stopped and cleared")
        } catch (e: Exception) {
            android.util.Log.e("PlayerService", "Error stopping player", e)
        }

        // Release the media session immediately to clear notification
        try {
            mediaLibrarySession?.release()
            mediaLibrarySession = null
            android.util.Log.d("PlayerService", "Media session released")
        } catch (e: Exception) {
            android.util.Log.e("PlayerService", "Error releasing media session", e)
        }

        // Stop foreground service and clear notifications immediately
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            android.util.Log.d("PlayerService", "Foreground service stopped")
        } catch (e: Exception) {
            android.util.Log.e("PlayerService", "Error stopping foreground", e)
        }

        // Clear any remaining notifications
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()
            android.util.Log.d("PlayerService", "All notifications cleared")
        } catch (e: Exception) {
            android.util.Log.e("PlayerService", "Error clearing notifications", e)
        }

        // Clean up resources
        try {
            _player?.release()
            _player = null
            _queue = null
            serviceScope.cancel()
            android.util.Log.d("PlayerService", "Resources cleaned up")
        } catch (e: Exception) {
            android.util.Log.e("PlayerService", "Error cleaning up resources", e)
        }

        // Stop the service completely
        try {
            stopSelf()
            android.util.Log.d("PlayerService", "Service stopped")
        } catch (e: Exception) {
            android.util.Log.e("PlayerService", "Error stopping service", e)
        }
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

        // Release Media3 cache
        try {
            mediaCache?.release()
            mediaCache = null
        } catch (e: Exception) {
            android.util.Log.w("PlayerService", "Failed to release Media3 cache", e)
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

                val items = validTracks.map { track: Track -> track.toMediaItem() }
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



}
