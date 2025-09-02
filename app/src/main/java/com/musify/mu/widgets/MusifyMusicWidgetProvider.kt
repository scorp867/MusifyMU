package com.musify.mu.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.musify.mu.R
import com.musify.mu.playback.PlayerService
import com.musify.mu.ui.MainActivity
import kotlinx.coroutines.*
import android.graphics.Color as AndroidColor

@UnstableApi
class MusifyMusicWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_PLAY_PAUSE = "com.musify.mu.widget.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.musify.mu.widget.NEXT"
        private const val ACTION_PREVIOUS = "com.musify.mu.widget.PREVIOUS"
        private const val ACTION_OPEN_APP = "com.musify.mu.widget.OPEN_APP"

        // Widget update interval (in milliseconds)
        private const val UPDATE_INTERVAL = 1000L

        // Cache for media controller
        private var mediaControllerFuture: ListenableFuture<MediaController>? = null
        private var mediaController: MediaController? = null

        // Track current playback state
        private var currentTrack: MediaItem? = null
        private var isPlaying = false
        private var currentPosition = 0L
        private var duration = 0L

        // Update job for periodic widget updates
        private var updateJob: Job? = null

        /**
         * Get or create media controller for the widget
         */
        fun getMediaController(context: Context): MediaController? {
            if (mediaController == null) {
                try {
                    val sessionToken = SessionToken(context, ComponentName(context, PlayerService::class.java))
                    mediaControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                    mediaControllerFuture?.addListener({
                        try {
                            mediaController = mediaControllerFuture?.get()
                            setupMediaControllerListener()
                        } catch (e: Exception) {
                            android.util.Log.e("MusifyMusicWidget", "Failed to get media controller", e)
                        }
                    }, MoreExecutors.directExecutor())
                } catch (e: Exception) {
                    android.util.Log.e("MusifyMusicWidget", "Failed to create media controller", e)
                }
            }
            return mediaController
        }

        /**
         * Setup media controller listener to track playback changes
         */
        private fun setupMediaControllerListener() {
            mediaController?.let { controller ->
                controller.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                        isPlaying = isPlayingNow
                        updateAllWidgets()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentTrack = mediaItem
                        updateAllWidgets()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateAllWidgets()
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        currentPosition = newPosition.positionMs
                        duration = controller.duration
                        updateAllWidgets()
                    }
                })
            }
        }

        /**
         * Update all active widgets
         */
        private fun updateAllWidgets() {
            // Get the context from a widget provider instance if available
            // For now, we'll handle updates through the periodic update mechanism
        }

        /**
         * Start periodic widget updates
         */
        fun startWidgetUpdates(context: Context) {
            updateJob?.cancel()
            updateJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive) {
                    getMediaController(context)?.let { controller ->
                        currentPosition = controller.currentPosition
                        duration = controller.duration
                        isPlaying = controller.isPlaying
                        currentTrack = controller.currentMediaItem
                    }
                    // Update all widgets
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val componentName = ComponentName(context, MusifyMusicWidgetProvider::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, 0)
                    delay(UPDATE_INTERVAL)
                }
            }
        }

        /**
         * Stop periodic widget updates
         */
        fun stopWidgetUpdates() {
            updateJob?.cancel()
            updateJob = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_PLAY_PAUSE -> handlePlayPause(context)
            ACTION_NEXT -> handleNext(context)
            ACTION_PREVIOUS -> handlePrevious(context)
            ACTION_OPEN_APP -> handleOpenApp(context)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        android.util.Log.d("MusifyMusicWidget", "onUpdate called with ${appWidgetIds.size} widget IDs: ${appWidgetIds.joinToString()}")

        // Initialize media controller if needed
        getMediaController(context)

        // Start periodic updates
        startWidgetUpdates(context)

        // Update all widgets
        for (appWidgetId in appWidgetIds) {
            android.util.Log.d("MusifyMusicWidget", "Updating widget $appWidgetId")
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        android.util.Log.d("MusifyMusicWidget", "Widget provider enabled")
        // Start updates when first widget is enabled
        startWidgetUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Stop updates when last widget is disabled
        stopWidgetUpdates()
        mediaController?.release()
        mediaController = null
        mediaControllerFuture = null
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player_simple)
            android.util.Log.d("MusifyMusicWidget", "Creating RemoteViews for widget $appWidgetId")

        // Get current playback state
        val controller = getMediaController(context)
        val mediaItem = controller?.currentMediaItem
        val playing = controller?.isPlaying ?: false
        val position = controller?.currentPosition ?: 0L
        val totalDuration = controller?.duration ?: 0L

        // Update track information
        val metadata = mediaItem?.mediaMetadata
        val title = metadata?.title?.toString() ?: "No track playing"
        val artist = metadata?.artist?.toString() ?: ""
        val album = metadata?.albumTitle?.toString() ?: ""

            // Update text views
            val displayText = if (mediaItem != null) {
                metadata?.title?.toString() ?: "Unknown Track"
            } else {
                "No music playing"
            }
            views.setTextViewText(R.id.widget_track_title, displayText)
            android.util.Log.d("MusifyMusicWidget", "Updated widget text to: $displayText")

            // Update play/pause button
            val iconRes = if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            views.setImageViewResource(R.id.widget_play_pause_button, iconRes)
            android.util.Log.d("MusifyMusicWidget", "Set play/pause icon: ${if (playing) "pause" else "play"}")

            // Set up click handlers
            views.setOnClickPendingIntent(R.id.widget_play_pause_button, getPendingIntent(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next_button, getPendingIntent(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_previous_button, getPendingIntent(context, ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_main_layout, getPendingIntent(context, ACTION_OPEN_APP))
            android.util.Log.d("MusifyMusicWidget", "Set up click handlers")

            // Update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
            android.util.Log.d("MusifyMusicWidget", "Widget $appWidgetId updated successfully")
        } catch (e: Exception) {
            android.util.Log.e("MusifyMusicWidget", "Error updating widget $appWidgetId", e)
        }
    }

    private fun getPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, MusifyMusicWidgetProvider::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
    }

    private fun handlePlayPause(context: Context) {
        getMediaController(context)?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
        }
    }

    private fun handleNext(context: Context) {
        getMediaController(context)?.seekToNext()
    }

    private fun handlePrevious(context: Context) {
        getMediaController(context)?.seekToPrevious()
    }

    private fun handleOpenApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "player")
        }
        context.startActivity(intent)
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Create a progress bar bitmap for custom progress visualization
     */
    private fun createProgressBitmap(width: Int, height: Int, progress: Float, context: Context): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background
        val backgroundPaint = Paint().apply {
            color = AndroidColor.parseColor("#33000000")
            style = Paint.Style.FILL
        }
        val backgroundRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(backgroundRect, height / 2f, height / 2f, backgroundPaint)

        // Progress
        if (progress > 0) {
            val progressPaint = Paint().apply {
                color = AndroidColor.parseColor("#FF1DB954") // Spotify Green
                style = Paint.Style.FILL
            }
            val progressWidth = width * progress
            val progressRect = RectF(0f, 0f, progressWidth, height.toFloat())
            canvas.drawRoundRect(progressRect, height / 2f, height / 2f, progressPaint)
        }

        return bitmap
    }
}
