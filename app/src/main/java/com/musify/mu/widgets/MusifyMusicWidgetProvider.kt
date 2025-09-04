package com.musify.mu.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.musify.mu.R
import com.musify.mu.playback.PlayerService
import com.musify.mu.ui.MainActivity
import com.musify.mu.util.PaletteUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher

@UnstableApi
class MusifyMusicWidgetProvider : AppWidgetProvider() {

	// Coroutine scope for background tasks
	private val widgetScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private data class PlaybackSnapshot(
		val hasQueue: Boolean,
		val isPlaying: Boolean,
		val title: CharSequence?,
		val artist: CharSequence?,
		val mediaId: String?,
		val artworkBytes: ByteArray?,
		val artworkUri: Uri?
	)

	override fun onUpdate(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetIds: IntArray
	) {
		if (mediaController == null) {
			getMediaController(context)
		}

		for (appWidgetId in appWidgetIds) {
			widgetScope.launch {
				updateAppWidget(context, appWidgetManager, appWidgetId)
			}
		}
	}

	private suspend fun readSnapshot(controller: MediaController): PlaybackSnapshot {
		val dispatcher = Handler(controller.applicationLooper).asCoroutineDispatcher()
		return withContext(dispatcher) {
			val hasQueue = controller.mediaItemCount > 0
			val item = controller.currentMediaItem
			val md = item?.mediaMetadata
			PlaybackSnapshot(
				hasQueue = hasQueue,
				isPlaying = controller.isPlaying,
				title = md?.title,
				artist = md?.artist,
				mediaId = item?.mediaId,
				artworkBytes = md?.artworkData,
				artworkUri = md?.artworkUri
			)
		}
	}

	private suspend fun updateAppWidget(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetId: Int
	) {
		val views = RemoteViews(context.packageName, R.layout.widget_music_player_enhanced)

		// Set pending intents for buttons
		views.setOnClickPendingIntent(R.id.widget_main_layout, getOpenAppIntent(context))
		views.setOnClickPendingIntent(R.id.widget_play_pause_button, getPlaybackActionIntent(context, ACTION_PLAY_PAUSE))
		views.setOnClickPendingIntent(R.id.widget_next_button, getPlaybackActionIntent(context, ACTION_NEXT))
		views.setOnClickPendingIntent(R.id.widget_previous_button, getPlaybackActionIntent(context, ACTION_PREVIOUS))

		val controller = mediaController
		if (controller == null) {
			// Not connected yet
			views.setTextViewText(R.id.widget_track_title, "No music playing")
			views.setTextViewText(R.id.widget_artist_name, "Musify MU")
			views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_widget_album_placeholder)
			views.setImageViewResource(R.id.widget_background_image, R.drawable.widget_background)
			views.setImageViewResource(R.id.widget_play_pause_button, R.drawable.ic_widget_play)
			appWidgetManager.updateAppWidget(appWidgetId, views)
			return
		}

		val snapshot = runCatching { readSnapshot(controller) }.getOrElse {
			Log.w("MusifyMusicWidget", "Failed to read controller snapshot", it)
			PlaybackSnapshot(false, false, null, null, null, null, null)
		}

		if (!snapshot.hasQueue) {
			views.setTextViewText(R.id.widget_track_title, "No music playing")
			views.setTextViewText(R.id.widget_artist_name, "Musify MU")
			views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_widget_album_placeholder)
			views.setImageViewResource(R.id.widget_background_image, R.drawable.widget_background)
			views.setImageViewResource(R.id.widget_play_pause_button, R.drawable.ic_widget_play)
			appWidgetManager.updateAppWidget(appWidgetId, views)
			return
		}

		views.setTextViewText(R.id.widget_track_title, snapshot.title ?: "Unknown Title")
		views.setTextViewText(R.id.widget_artist_name, snapshot.artist ?: "Unknown Artist")
		views.setImageViewResource(
			R.id.widget_play_pause_button,
			if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
		)

		// Obtain artwork bitmap (always capped size)
		var artworkBitmap: Bitmap? = null
		if (snapshot.artworkBytes != null) {
			artworkBitmap = coilDecode(context, snapshot.artworkBytes)
		}
		if (artworkBitmap == null && !snapshot.mediaId.isNullOrBlank()) {
			artworkBitmap = loadArtworkFromOnDemand(context, snapshot.mediaId)
		}
		if (artworkBitmap == null) {
			artworkBitmap = coilDecode(context, snapshot.artworkUri)
		}

		if (artworkBitmap != null) {
			val safeArt = scaleBitmapIfNeeded(artworkBitmap, MAX_ART_SIZE_PX)
			if (safeArt !== artworkBitmap) artworkBitmap.recycle()
			views.setImageViewBitmap(R.id.widget_album_art, safeArt)
			Palette.from(safeArt).generate { palette ->
				palette?.let {
					val backgroundBitmap = PaletteUtil.createGradientBitmap(it)
					views.setImageViewBitmap(R.id.widget_background_image, backgroundBitmap)
					appWidgetManager.updateAppWidget(appWidgetId, views)
				}
			}
		} else {
			views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_widget_album_placeholder)
			views.setImageViewResource(R.id.widget_background_image, R.drawable.widget_background)
		}

		appWidgetManager.updateAppWidget(appWidgetId, views)
	}

	private fun scaleBitmapIfNeeded(src: Bitmap, maxEdge: Int): Bitmap {
		val w = src.width
		val h = src.height
		if (w <= maxEdge && h <= maxEdge) return src
		val ratio = kotlin.math.min(maxEdge.toFloat() / w, maxEdge.toFloat() / h)
		val nw = (w * ratio).toInt().coerceAtLeast(1)
		val nh = (h * ratio).toInt().coerceAtLeast(1)
		return Bitmap.createScaledBitmap(src, nw, nh, true)
	}

	private suspend fun coilDecode(context: Context, uri: Uri?): Bitmap? {
		if (uri == null) return null
		return try {
			val request = ImageRequest.Builder(context)
				.data(uri)
				.size(MAX_ART_SIZE_PX)
				.allowHardware(false)
				.build()
			val drawable = context.imageLoader.execute(request).drawable
			(drawable as? BitmapDrawable)?.bitmap ?: drawable?.toBitmap()
		} catch (e: Exception) {
			Log.w("MusifyMusicWidget", "coilDecode uri failed", e)
			null
		}
	}

	private suspend fun coilDecode(context: Context, bytes: ByteArray): Bitmap? {
		return try {
			val request = ImageRequest.Builder(context)
				.data(bytes)
				.size(MAX_ART_SIZE_PX)
				.allowHardware(false)
				.build()
			val drawable = context.imageLoader.execute(request).drawable
			(drawable as? BitmapDrawable)?.bitmap ?: drawable?.toBitmap()
		} catch (e: Exception) {
			Log.w("MusifyMusicWidget", "coilDecode bytes failed", e)
			null
		}
	}

	private suspend fun loadArtworkFromOnDemand(context: Context, mediaId: String): Bitmap? {
		return withContext(Dispatchers.IO) {
			try {
				val cached = com.musify.mu.util.SpotifyStyleArtworkLoader.getCachedArtworkUri(mediaId)
				val uriStr = cached ?: runBlocking { com.musify.mu.util.SpotifyStyleArtworkLoader.loadArtwork(mediaId) }
				if (uriStr.isNullOrBlank()) return@withContext null
				val request = ImageRequest.Builder(context)
					.data(uriStr)
					.size(MAX_ART_SIZE_PX)
					.allowHardware(false)
					.build()
				val drawable = context.imageLoader.execute(request).drawable
				(drawable as? BitmapDrawable)?.bitmap ?: drawable?.toBitmap()
			} catch (e: Exception) {
				Log.w("MusifyMusicWidget", "OnDemand artwork load failed", e)
				null
			}
		}
	}

	override fun onReceive(context: Context, intent: Intent) {
		super.onReceive(context, intent)
		val action = intent.action
		if (action?.startsWith("com.musify.mu.widget") == true) {
			performActionWithController(context) { controller ->
				when (action) {
					ACTION_PLAY_PAUSE -> if (controller.isPlaying) controller.pause() else controller.play()
					ACTION_NEXT -> controller.seekToNext()
					ACTION_PREVIOUS -> controller.seekToPrevious()
				}
				requestWidgetsUpdate(context)
			}
		}
	}

	override fun onEnabled(context: Context) {
		super.onEnabled(context)
		getMediaController(context)
	}

	override fun onDisabled(context: Context) {
		super.onDisabled(context)
		mediaController?.release()
		mediaController = null
		mediaControllerFuture = null
		widgetScope.cancel()
	}

	companion object {
		private const val ACTION_PLAY_PAUSE = "com.musify.mu.widget.PLAY_PAUSE"
		private const val ACTION_NEXT = "com.musify.mu.widget.NEXT"
		private const val ACTION_PREVIOUS = "com.musify.mu.widget.PREVIOUS"
		private const val MAX_ART_SIZE_PX = 256

		@Volatile
		private var mediaController: MediaController? = null
		private var mediaControllerFuture: ListenableFuture<MediaController>? = null

		private fun getMediaController(context: Context) {
			if (mediaControllerFuture == null) {
				val appCtx = context.applicationContext
				val sessionToken = SessionToken(appCtx, ComponentName(appCtx, PlayerService::class.java))
				mediaControllerFuture = MediaController.Builder(appCtx, sessionToken).buildAsync()
				mediaControllerFuture?.addListener({
					try {
						mediaController = mediaControllerFuture?.get()
						mediaController?.addListener(PlayerListener(appCtx))
						requestWidgetsUpdate(appCtx)
					} catch (e: Exception) {
						Log.e("MusifyMusicWidget", "Error connecting to MediaController", e)
					}
				}, MoreExecutors.directExecutor())
			}
		}

		private fun requestWidgetsUpdate(context: Context) {
			val manager = AppWidgetManager.getInstance(context)
			val ids = manager.getAppWidgetIds(ComponentName(context, MusifyMusicWidgetProvider::class.java))
			if (ids != null && ids.isNotEmpty()) {
				val updateIntent = Intent(context, MusifyMusicWidgetProvider::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
				updateIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
				context.sendBroadcast(updateIntent)
			}
		}

		private fun performActionWithController(context: Context, block: (MediaController) -> Unit) {
			val controller = mediaController
			if (controller != null) {
				Handler(controller.applicationLooper).post { block(controller) }
				return
			}
			// Try to connect and then perform action
			getMediaController(context)
			mediaControllerFuture?.addListener({
				try {
					mediaControllerFuture?.get()?.let { c ->
						Handler(c.applicationLooper).post { block(c) }
					}
				} catch (e: Exception) {
					Log.e("MusifyMusicWidget", "Failed to perform action after controller connect", e)
				}
			}, MoreExecutors.directExecutor())
		}

		private fun getPlaybackActionIntent(context: Context, action: String): PendingIntent {
			val intent = Intent(context, MusifyMusicWidgetProvider::class.java).setAction(action)
			val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
			return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
		}

		private fun getOpenAppIntent(context: Context): PendingIntent {
			val intent = Intent(context, MainActivity::class.java)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
			return PendingIntent.getActivity(context, "open_app".hashCode(), intent, flags)
		}

		private class PlayerListener(private val context: Context) : Player.Listener {
			override fun onEvents(player: Player, events: Player.Events) {
				if (events.containsAny(
						Player.EVENT_MEDIA_ITEM_TRANSITION,
						Player.EVENT_IS_PLAYING_CHANGED,
						Player.EVENT_PLAYBACK_STATE_CHANGED)
				) {
					requestWidgetsUpdate(context)
				}
			}
		}
	}
}
