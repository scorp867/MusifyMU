package com.musify.mu.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import android.os.Bundle
import android.app.NotificationChannel
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.CommandButton
import com.google.common.collect.ImmutableList
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import com.musify.mu.R
import com.musify.mu.ui.MainActivity
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.LyricsStateStore
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.data.db.entities.Track
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
				// Persist cache in app-specific external storage so it survives process death and is user-accessible
				val baseDir = context.getExternalFilesDir(null) ?: context.cacheDir
				val cacheDir = File(baseDir, "media3_cache").apply { mkdirs() }
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
	private var componentsInitialized = false
	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var lastLoadedLyricsId: String? = null
	private var pendingStopJob: kotlinx.coroutines.Job? = null

	private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
	private var hasValidMedia = false
	private var currentMediaIdCache: String? = null
	private var receiverRegistered = false
	private var lifecycleObserverRegistered = false
	private var audioFocusManagerInitialized = false

	/**
	 * Broadcast receiver to catch system events that might indicate app termination
	 */
	private val terminationReceiver = object : android.content.BroadcastReceiver() {
		override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
			// Handle termination events
			val packageName = intent?.data?.schemeSpecificPart
			val isOurPackage = packageName == context?.packageName

			if (isOurPackage) {
				when (intent?.action) {
					android.content.Intent.ACTION_PACKAGE_RESTARTED,
					android.content.Intent.ACTION_PACKAGE_REMOVED,
					android.content.Intent.ACTION_PACKAGE_REPLACED -> {
						android.util.Log.d("PlayerService", "Received termination signal for our package: ${intent.action}")
						cleanupAllNotifications()
					}
				}
			}
		}
	}

	/**
	 * Process lifecycle observer - handles background transitions and termination
	 */
	private val processLifecycleObserver = object : DefaultLifecycleObserver {
		override fun onStop(owner: LifecycleOwner) {
			android.util.Log.d("PlayerService", "App going to background")
			// Don't interfere with normal Media3 notification management
			// Let Media3 handle background notifications properly
		}

		override fun onDestroy(owner: LifecycleOwner) {
			android.util.Log.d("PlayerService", "Process lifecycle onDestroy - app is terminating")
			// Only clean up notifications when the process is actually being destroyed
			cleanupAllNotifications()
		}

		override fun onStart(owner: LifecycleOwner) {
			android.util.Log.d("PlayerService", "App returning to foreground")
			// App is back, no cleanup needed
		}
	}

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
						// Media3 will manage the notification lifecycle via provider
						if (isPlaying) {
							audioFocusManager.request()
						} else {
							audioFocusManager.abandon()
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
								stopForeground(Service.STOP_FOREGROUND_DETACH)
							}
						}
					}
					if (isPlaying) cancelPendingStop()
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
						// Media3 provider will refresh the notification automatically
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

		try {
			// Register broadcast receiver for termination events
			val filter = android.content.IntentFilter().apply {
				addAction(android.content.Intent.ACTION_PACKAGE_RESTARTED)
				addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
				addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
				addDataScheme("package")
			}
			registerReceiver(terminationReceiver, filter)
			receiverRegistered = true
			android.util.Log.d("PlayerService", "Termination broadcast receiver registered")
		} catch (e: Exception) {
			android.util.Log.e("PlayerService", "Error registering broadcast receiver", e)
			receiverRegistered = false
		}

		try {
			// Register process lifecycle observer
			ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
			lifecycleObserverRegistered = true
			android.util.Log.d("PlayerService", "Process lifecycle observer registered")
		} catch (e: Exception) {
			android.util.Log.e("PlayerService", "Error registering lifecycle observer", e)
			lifecycleObserverRegistered = false
		}

		try {
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
						stopForeground(Service.STOP_FOREGROUND_DETACH)
					}
					android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
						player.pause()
						stopForeground(Service.STOP_FOREGROUND_DETACH)
					}
					android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
						player.volume = 0.3f
					}
				}
			}
			audioFocusManagerInitialized = true
			componentsInitialized = true
		} catch (e: Exception) {
			android.util.Log.e("PlayerService", "Error during service initialization", e)
			stopSelf()
			return
		}

		val likeCommand = SessionCommand("com.musify.mu.ACTION_LIKE", Bundle.EMPTY)
		val likeButton = androidx.media3.session.CommandButton.Builder()
			.setDisplayName("Like")
			.setIconResId(R.drawable.ic_favorite_border_24)
			.setSessionCommand(likeCommand)
			.build()

		val callback = object : MediaLibraryService.MediaLibrarySession.Callback {
			override fun onConnect(
					 session: MediaSession,
					 controller: MediaSession.ControllerInfo
				): MediaSession.ConnectionResult {
				val base = super.onConnect(session, controller)
				val availableSessionCommands = base.availableSessionCommands
					.buildUpon()
					.add(likeCommand)
					.build()
				return MediaSession.ConnectionResult.accept(
					availableSessionCommands,
					base.availablePlayerCommands
				)
			}

			override fun onCustomCommand(
					 session: MediaSession,
					 controller: MediaSession.ControllerInfo,
					 customCommand: SessionCommand,
					 args: Bundle
				): ListenableFuture<SessionResult> {
				if (customCommand.customAction == likeCommand.customAction) {
					serviceScope.launch(Dispatchers.IO) {
						try {
							val id = session.player.currentMediaItem?.mediaId
							if (id != null) {
								val liked = repo.isLiked(id)
								if (liked) {
									repo.unlike(id)
								} else {
									repo.like(id)
								}
							}
						} catch (_: Exception) { }
					}
					return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
				}
				return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
			}
			override fun onPlaybackResumption(
				session: MediaSession,
				controller: MediaSession.ControllerInfo
			): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
				val future = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
				serviceScope.launch(Dispatchers.IO) {
					try {
						// If there is an existing queue, return it with current position
						val currentItems: MutableList<MediaItem> = mutableListOf()
						val startIndex: Int
						val startPositionMs: Long
						if (_player != null && player.mediaItemCount > 0) {
							for (i in 0 until player.mediaItemCount) {
								currentItems.add(player.getMediaItemAt(i))
							}
							startIndex = player.currentMediaItemIndex.coerceAtLeast(0)
							startPositionMs = player.currentPosition
							future.set(MediaSession.MediaItemsWithStartPosition(currentItems, startIndex, startPositionMs))
							return@launch
						}

						// Otherwise, resume most recent
						val recent = try { repo.recentlyPlayed(1) } catch (_: Exception) { emptyList() }
						if (recent.isNotEmpty()) {
							val item = recent.first().toMediaItem()
							future.set(MediaSession.MediaItemsWithStartPosition(mutableListOf(item), 0, 0L))
						} else {
							// No content to resume
							future.set(MediaSession.MediaItemsWithStartPosition(mutableListOf(), 0, 0L))
						}
					} catch (_: Exception) {
						// On error, provide empty result to indicate no resumption content
						future.set(MediaSession.MediaItemsWithStartPosition(mutableListOf(), 0, 0L))
					}
				}
				return future
			}
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
				cancelPendingStop()

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
			.setCustomLayout(ImmutableList.of(likeButton))
			.build()

		// Set custom MediaNotification provider (Media3 lifecycle retained)
		setMediaNotificationProvider(MusifyNotificationProvider(this))

		// Configure the service for media playback
		setListener(object : Listener {
			override fun onForegroundServiceStartNotAllowedException() {
				android.util.Log.e("PlayerService", "Foreground service start not allowed")
			}
		})

		// Player and QueueManager will be created lazily when needed (e.g., on play)
	}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
		return mediaLibrarySession
	}

	override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		return START_NOT_STICKY
	}

	private fun cancelPendingStop() {
		pendingStopJob?.cancel()
		pendingStopJob = null
	}

	/**
	 * Comprehensive notification cleanup that handles all Media3 notification scenarios
	 */
	private fun cleanupAllNotifications() {
		try {
			// Stop foreground service properly - this will dismiss the notification
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				stopForeground(STOP_FOREGROUND_REMOVE)
			} else {
				stopForeground(true)
			}
			android.util.Log.d("PlayerService", "Foreground service stopped")
		} catch (e: Exception) {
			android.util.Log.e("PlayerService", "Error stopping foreground", e)
		}

		// Clear any remaining notifications that Media3 might have left
		try {
			val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			// Cancel all notifications to be thorough
			notificationManager.cancelAll()
			android.util.Log.d("PlayerService", "All notifications cleared")
		} catch (e: Exception) {
			android.util.Log.e("PlayerService", "Error clearing notifications", e)
		}
	}



	override fun onTaskRemoved(rootIntent: Intent?) {
		super.onTaskRemoved(rootIntent)
		android.util.Log.d("PlayerService", "App swiped away from recents - stopping service")

		try {
			if (_player?.isPlaying == true) {
				// If playing: pause and remove notification
				_player?.pause()
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					stopForeground(STOP_FOREGROUND_REMOVE)
				} else {
					stopForeground(true)
				}
				onDestroy()
			} else {
				// If paused: just remove notification
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					stopForeground(STOP_FOREGROUND_REMOVE)
				} else {
					stopForeground(true)
				}
			}
		} catch (_: Exception) {}

		// Cleanup notification artifacts left by the system
		pendingStopJob?.cancel()
		pendingStopJob = null
	}

	override fun onDestroy() {
		android.util.Log.d("PlayerService", "Service onDestroy called")

		// Unregister broadcast receiver - only if it was registered
		if (receiverRegistered) {
			try {
				unregisterReceiver(terminationReceiver)
				android.util.Log.d("PlayerService", "Termination broadcast receiver unregistered")
			} catch (e: Exception) {
				android.util.Log.d("PlayerService", "Broadcast receiver was not registered or already unregistered")
			}
		}

		// Unregister process lifecycle observer - only if it was registered
		if (lifecycleObserverRegistered) {
			try {
				ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
				android.util.Log.d("PlayerService", "Process lifecycle observer unregistered")
			} catch (e: Exception) {
				android.util.Log.d("PlayerService", "Process lifecycle observer was not registered or already unregistered")
			}
		}

		// Always clean up notifications in onDestroy as well
		cleanupAllNotifications()

		// Abandon audio focus manager - only if it was initialized
		if (audioFocusManagerInitialized) {
			try {
				audioFocusManager.abandon()
			} catch (e: Exception) {
				android.util.Log.d("PlayerService", "Audio focus manager was already abandoned")
			}
		}

		try {
			stopForegroundService()
		} catch (e: Exception) {
			android.util.Log.d("PlayerService", "Foreground service was already stopped")
		}

		try {
			mediaLibrarySession?.release()
			mediaLibrarySession = null
		} catch (e: Exception) {
			android.util.Log.d("PlayerService", "Media session was already released")
		}

		try {
			_player?.release()
			_player = null
		} catch (e: Exception) {
			android.util.Log.d("PlayerService", "Player was already released")
		}

		_queue = null
		serviceScope.cancel()


		// Clear state on destroy - only if components were initialized
		if (componentsInitialized) {
			try {
				serviceScope.launch(Dispatchers.IO) {
					try {
						stateStore.clear()
					} catch (e: Exception) {
						android.util.Log.w("PlayerService", "Failed to clear state on destroy", e)
					}
				}
			} catch (e: Exception) {
				android.util.Log.d("PlayerService", "State store was not initialized")
			}
		}

		// Release Media3 cache
		try {
			mediaCache?.release()
			mediaCache = null
		} catch (e: Exception) {
			android.util.Log.d("PlayerService", "Media cache was already released")
		}

		super.onDestroy()
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
