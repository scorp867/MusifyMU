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
import kotlinx.coroutines.withContext

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
		// Singleton instance to prevent multiple service instances
		@Volatile
		private var instance: PlayerService? = null

		fun getInstance(): PlayerService? = instance

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
	private var componentsInitialized = false
	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var lastLoadedLyricsId: String? = null
	private var pendingStopJob: kotlinx.coroutines.Job? = null
	private var mediaButtonStopJob: kotlinx.coroutines.Job? = null

	private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
	private var hasValidMedia = false
	private var currentMediaIdCache: String? = null
	private var audioFocusManagerInitialized = false



	private fun ensurePlayerInitialized() {
		// Check if this is the correct service instance
		if (instance !== this) {
			android.util.Log.w("PlayerService", "ensurePlayerInitialized called on invalid service instance")
			return
		}

		// If we already have a properly initialized player and queue with matching session, return
		if (_player != null && _queue != null && _player === mediaLibrarySession?.player) {
			android.util.Log.d("PlayerService", "Player already initialized and session matches")
			return
		}

		android.util.Log.d("PlayerService", "Initializing player and queue")

		// Only create a new player if we don't have one
		val newPlayer = if (_player == null) {
			runBlocking { createOptimizedExoPlayer(this@PlayerService) }.also { _player = it }
		} else {
			_player!! // Use existing player
		}

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
							// When playback starts, Media3 should replace our temporary notification
							android.util.Log.d("PlayerService", "Playback started - Media3 notification should be active")
						} else {
							audioFocusManager.abandon()
							// Let Media3 handle notification state - don't interfere
							android.util.Log.d("PlayerService", "Playback paused - Media3 manages notification state")
						}
					} else if (!isPlaying) {
						// If not playing and no valid media, we might need to stop the service
						android.util.Log.d("PlayerService", "No valid media and not playing - service may need cleanup")
					}
					if (isPlaying) {
						cancelPendingStop()
						// Cancel media button stop job since playback started
						mediaButtonStopJob?.cancel()
						mediaButtonStopJob = null
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
							val cached = com.musify.mu.util.SpotifyStyleArtworkLoader.storeArtworkBytes(mediaId, bytes)
							if (cached != null) {
								try { repo.updateTrackArt(mediaId, cached) } catch (_: Exception) {}
								// Artwork is already cached by storeArtworkBytes
							}
						}
					} else if (mediaId != null && metadata.artworkUri != null) {
						// Use Media3 artworkUri if present (e.g., notification extracted)
						try {
							val uriStr = metadata.artworkUri.toString()
							serviceScope.launch(Dispatchers.IO) {
								try { repo.updateTrackArt(mediaId, uriStr) } catch (_: Exception) {}
								// URI is already cached by SpotifyStyleArtworkLoader
							}
						} catch (_: Exception) {}
					}
				}
			})
			android.util.Log.d("PlayerService", "Created new QueueManager")
		} else {
			android.util.Log.d("PlayerService", "Reusing existing QueueManager")
		}

		// Update the session with the current player only if it's different
		if (mediaLibrarySession?.player !== newPlayer) {
			android.util.Log.d("PlayerService", "Updating media session player")
		mediaLibrarySession?.player = newPlayer
		}
	}

	@OptIn(UnstableApi::class)

	override fun onDestroy() {
		android.util.Log.d("PlayerService", "Service onDestroy called")

		// Abandon audio focus manager - only if it was initialized
		if (audioFocusManagerInitialized) {
			try {
				audioFocusManager.abandon()
			} catch (e: Exception) {
				android.util.Log.d("PlayerService", "Audio focus manager was already abandoned")
			}
		}

		// Media3 handles foreground service cleanup automatically

		// Note: Media3 notification provider is managed automatically
		// We don't need to manually clear it - Media3 handles this

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

		// Clear singleton instance
		instance = null
		android.util.Log.d("PlayerService", "Service instance cleared")

		super.onDestroy()
	}


	override fun onCreate() {
		super.onCreate()

		// Prevent multiple instances
		if (instance != null) {
			android.util.Log.w("PlayerService", "Service instance already exists, stopping duplicate")
			stopSelf()
			return
		}
		instance = this
		android.util.Log.d("PlayerService", "Service instance created")

		// Don't start any foreground service initially - let Media3 handle it when playback starts
		// This ensures no notifications appear until actual playback begins

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
					serviceScope.launch(Dispatchers.Main) {
						try {
							val id = session.player.currentMediaItem?.mediaId
							if (id != null) {
								withContext(Dispatchers.IO) {
								val liked = repo.isLiked(id)
								if (liked) {
									repo.unlike(id)
								} else {
									repo.like(id)
									}
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
				serviceScope.launch(Dispatchers.Main) {
					try {
						// Ensure player is initialized for headset button handling
						ensurePlayerInitialized()

						// All player operations must be on main thread
						val currentItems: MutableList<MediaItem> = mutableListOf()
						val startIndex: Int
						val startPositionMs: Long

						// Check if there is an existing queue with player access on main thread
						if (_player != null && _player!!.mediaItemCount > 0) {
							for (i in 0 until _player!!.mediaItemCount) {
								currentItems.add(_player!!.getMediaItemAt(i))
							}
							startIndex = _player!!.currentMediaItemIndex.coerceAtLeast(0)
							startPositionMs = _player!!.currentPosition
							future.set(MediaSession.MediaItemsWithStartPosition(currentItems, startIndex, startPositionMs))
							android.util.Log.d("PlayerService", "Resuming with existing queue: ${currentItems.size} items")
							return@launch
						}

						// Switch to IO dispatcher for database operations
						withContext(Dispatchers.IO) {
							try {
								// Try to restore from saved state first
								val savedState = stateStore.load()
								val queueState = queueStateStore.loadQueueState()

								android.util.Log.d("PlayerService", "Loaded saved state: ${savedState?.mediaIds?.size ?: 0} tracks")
								android.util.Log.d("PlayerService", "Loaded queue state: play-next=${queueState?.playNextItems?.size ?: 0}, user-queue=${queueState?.userQueueItems?.size ?: 0}")

								if (savedState != null && savedState.mediaIds.isNotEmpty()) {
									val tracks = savedState.mediaIds.mapNotNull { id ->
										try { repo.getTrackByMediaId(id) } catch (_: Exception) { null }
									}.filterNotNull()

									android.util.Log.d("PlayerService", "Found ${tracks.size} valid tracks from saved state")
									if (tracks.isNotEmpty()) {
										val items = tracks.map { it.toMediaItem() }
										val safeIndex = savedState.index?.coerceIn(0, items.size - 1) ?: 0

										// Set the main queue items
										future.set(MediaSession.MediaItemsWithStartPosition(items, safeIndex, savedState.posMs ?: 0L))
										android.util.Log.d("PlayerService", "Resuming from saved state: ${items.size} items at position ${savedState.posMs}")

										// Restore queue state (play-next and user queue) after a short delay to let the session initialize
										serviceScope.launch {
											kotlinx.coroutines.delay(1000) // Wait for queue to be ready
											try {
												if (queueState != null && _queue != null) {
													// Restore play-next items
													if (queueState.playNextItems.isNotEmpty()) {
														val playNextTracks = queueState.playNextItems.mapNotNull { id ->
															try { repo.getTrackByMediaId(id) } catch (_: Exception) { null }
														}.filterNotNull()

														if (playNextTracks.isNotEmpty()) {
															runBlocking(Dispatchers.Main) {
																try {
																	_queue?.playNext(playNextTracks.map { it.toMediaItem() }.toMutableList())
																	android.util.Log.d("PlayerService", "Restored ${playNextTracks.size} play-next items")
																} catch (e: Exception) {
																	android.util.Log.w("PlayerService", "Failed to restore play-next items", e)
																}
															}
														}
													}

													// Restore user queue items
													if (queueState.userQueueItems.isNotEmpty()) {
														val userQueueTracks = queueState.userQueueItems.mapNotNull { id ->
															try { repo.getTrackByMediaId(id) } catch (_: Exception) { null }
														}.filterNotNull()

														if (userQueueTracks.isNotEmpty()) {
															runBlocking(Dispatchers.Main) {
																try {
																	_queue?.addToUserQueue(userQueueTracks.map { it.toMediaItem() }.toMutableList())
																	android.util.Log.d("PlayerService", "Restored ${userQueueTracks.size} user-queue items")
																} catch (e: Exception) {
																	android.util.Log.w("PlayerService", "Failed to restore user-queue items", e)
																}
															}
														}
													}
												}
											} catch (e: Exception) {
												android.util.Log.w("PlayerService", "Failed to restore queue state", e)
											}
										}

										return@withContext
									}
								}

								// Otherwise, try to resume most recent played track
								val recent = repo.recentlyPlayed(1)
								android.util.Log.d("PlayerService", "Checked recently played: ${recent.size} tracks")
								if (recent.isNotEmpty()) {
									val item = recent.first().toMediaItem()
									future.set(MediaSession.MediaItemsWithStartPosition(mutableListOf(item), 0, 0L))
									android.util.Log.d("PlayerService", "Resuming with most recent track: ${item.mediaId}")
									return@withContext
								}

								// If no recent tracks, try to get any track from the library as fallback
								try {
									val allTracks = repo.getAllTracks()
									android.util.Log.d("PlayerService", "Library contains ${allTracks?.size ?: 0} total tracks")
									val anyTrack = allTracks?.firstOrNull()
									if (anyTrack != null) {
										val item = anyTrack.toMediaItem()
										future.set(MediaSession.MediaItemsWithStartPosition(mutableListOf(item), 0, 0L))
										android.util.Log.d("PlayerService", "Resuming with first available track: ${item.mediaId}")
										return@withContext
									}
								} catch (e: Exception) {
									android.util.Log.w("PlayerService", "Failed to get fallback track", e)
								}

							// No content to resume
							future.set(MediaSession.MediaItemsWithStartPosition(mutableListOf(), 0, 0L))
								android.util.Log.d("PlayerService", "No content available for resumption")
							} catch (e: Exception) {
								android.util.Log.e("PlayerService", "Error loading saved state", e)
								future.set(MediaSession.MediaItemsWithStartPosition(mutableListOf(), 0, 0L))
							}
						}
					} catch (e: Exception) {
						android.util.Log.e("PlayerService", "Error during playback resumption", e)
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
				// Cancel media button stop job since we now have media
				mediaButtonStopJob?.cancel()
				mediaButtonStopJob = null

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

		// Create optimized player immediately instead of using placeholder
		val initialPlayer = runBlocking { createOptimizedExoPlayer(this@PlayerService) }
		_player = initialPlayer

		mediaLibrarySession = MediaLibraryService.MediaLibrarySession.Builder(this, initialPlayer, callback)
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

					// Initialize player and queue components immediately
			ensurePlayerInitialized()

			// If we have valid media, the Media3 notification provider should handle notifications
			// If not, keep the temporary notification until media is available or service is stopped
	}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
		return mediaLibrarySession
	}

	override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		// Handle media button intents that might start the service
		intent?.let { incomingIntent ->
			if (incomingIntent.action == android.content.Intent.ACTION_MEDIA_BUTTON) {
				android.util.Log.d("PlayerService", "Service started by media button - initializing player")
				// Ensure player is initialized when started by media button
				ensurePlayerInitialized()

				// Log the key code if available
				val keyEvent = incomingIntent.getParcelableExtra<android.view.KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
				if (keyEvent != null) {
					android.util.Log.d("PlayerService", "Media button key code: ${keyEvent.keyCode}")
				}

				// Schedule a check to stop service if no media becomes available
				mediaButtonStopJob = serviceScope.launch(Dispatchers.Main) {
					kotlinx.coroutines.delay(3000) // Wait 3 seconds for initialization
					try {
						if (_player != null && _player!!.mediaItemCount == 0 && !_player!!.isPlaying) {
							android.util.Log.d("PlayerService", "No media available after media button press - stopping service")
							stopSelf()
						}
					} catch (e: Exception) {
						android.util.Log.w("PlayerService", "Error checking player state for service cleanup", e)
					}
				}
			}
		}

		return START_NOT_STICKY
	}


	private fun cancelPendingStop() {
		pendingStopJob?.cancel()
		pendingStopJob = null
	}




	override fun onTaskRemoved(rootIntent: Intent?) {
		super.onTaskRemoved(rootIntent)
		android.util.Log.d("PlayerService", "App swiped away from recents - complete shutdown")

		try {
			// Immediately pause playback and stop any foreground notifications
			_player?.pause()
			stopForeground(STOP_FOREGROUND_REMOVE)
			android.util.Log.d("PlayerService", "Playback stopped and foreground removed")

			// Save comprehensive queue and playback state
			if (componentsInitialized && _queue != null && _player != null) {
				saveCompletePlaybackState()
			}

			// Release media session immediately
			try {
				mediaLibrarySession?.release()
                mediaLibrarySession = null
				android.util.Log.d("PlayerService", "Media session released")
			} catch (e: Exception) {
				android.util.Log.w("PlayerService", "Error releasing media session", e)
			}

			// Stop service completely - no notifications should remain
                stopSelf()
			android.util.Log.d("PlayerService", "Service completely stopped - no notifications should remain")

		} catch (e: Exception) {
			android.util.Log.e("PlayerService", "Error during task removal cleanup", e)
		}

		// Cancel all pending operations
		pendingStopJob?.cancel()
		pendingStopJob = null
		mediaButtonStopJob?.cancel()
		mediaButtonStopJob = null
	}

	private fun saveCompletePlaybackState() {
		try {
			val currentPlayer = _player!!
			val currentQueue = _queue!!

			// Get all queue items including main queue, play next, and user queue
			val allQueueItems = mutableListOf<String>()

			// Add main queue items
			for (i in 0 until currentPlayer.mediaItemCount) {
				currentPlayer.getMediaItemAt(i).mediaId.let { allQueueItems.add(it) }
			}

			val currentIndex = currentPlayer.currentMediaItemIndex
			val currentPosition = currentPlayer.currentPosition
			val repeatMode = currentPlayer.repeatMode
			val shuffleEnabled = currentPlayer.shuffleModeEnabled

			// Save queue state (play next and user queue items)
			val playNextItems = currentQueue.getPlayNextQueue()
			val userQueueItems = currentQueue.getUserQueue()

			// Save everything synchronously
			runBlocking(Dispatchers.IO) {
				try {
					// Save main playback state
					if (allQueueItems.isNotEmpty()) {
						stateStore.save(
							ids = allQueueItems,
							index = currentIndex,
							posMs = currentPosition,
							repeat = repeatMode,
							shuffle = shuffleEnabled,
							play = false
						)
						android.util.Log.d("PlayerService", "Main playback state saved: ${allQueueItems.size} items")
					}

					// Save queue state separately
					queueStateStore.saveQueueState(
						playNextItems = playNextItems,
						userQueueItems = userQueueItems,
						currentMainIndex = currentIndex
					)
					android.util.Log.d("PlayerService", "Queue state saved: ${playNextItems.size} play-next, ${userQueueItems.size} user-queue")

				} catch (e: Exception) {
					android.util.Log.w("PlayerService", "Failed to save complete playback state", e)
				}
			}
		} catch (e: Exception) {
			android.util.Log.w("PlayerService", "Failed to save complete playback state", e)
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
}
