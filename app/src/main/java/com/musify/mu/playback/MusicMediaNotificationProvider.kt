package com.musify.mu.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.collect.ImmutableList
import com.musify.mu.ui.MainActivity
import androidx.media3.session.CommandButton

@OptIn(UnstableApi::class)
class MusicMediaNotificationProvider(
    private val context: Context
) : MediaNotification.Provider {
    
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "musify_playback_channel"
        private const val NOTIFICATION_ID = 1001
    }
    
    private var notificationManager: NotificationManager? = null
    
    init {
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    override fun createNotification(
        session: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        val player = session.player
        val metadata = player.currentMediaItem?.mediaMetadata
        
        // Create pending intent for notification tap
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_to", "player")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(metadata?.title ?: "Unknown")
            .setContentText(metadata?.artist ?: "Unknown Artist")
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            
        // Make notification non-dismissable during playback
        if (player.isPlaying || player.playWhenReady) {
            builder.setOngoing(true)
            builder.setDeleteIntent(null) // Prevent swipe dismissal
        } else {
            builder.setOngoing(false)
            // Create delete intent to handle notification dismissal
            val deleteIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, NotificationDismissReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setDeleteIntent(deleteIntent)
        }
        
        // Add media style with session token
        val mediaStyle = MediaStyleNotificationHelper.MediaStyle(session)
        
        // Create a list to track which actions to show in compact view
        val compactViewIndices = mutableListOf<Int>()
        var actionIndex = 0
        
        // Add previous action if available
        if (player.hasPreviousMediaItem()) {
            val prevAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_previous,
                "Previous",
                actionFactory.createMediaAction(
                    session,
                    MediaNotification.ActionFactory.COMMAND_SKIP_TO_PREVIOUS
                )
            ).build()
            builder.addAction(prevAction)
            actionIndex++
        }
        
        // Add play/pause action (always show in compact view)
        val playPauseAction = if (player.isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                "Pause",
                actionFactory.createMediaAction(
                    session,
                    MediaNotification.ActionFactory.COMMAND_PAUSE
                )
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "Play",
                actionFactory.createMediaAction(
                    session,
                    MediaNotification.ActionFactory.COMMAND_PLAY
                )
            ).build()
        }
        builder.addAction(playPauseAction)
        compactViewIndices.add(actionIndex)
        actionIndex++
        
        // Add next action if available
        if (player.hasNextMediaItem()) {
            val nextAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_next,
                "Next",
                actionFactory.createMediaAction(
                    session,
                    MediaNotification.ActionFactory.COMMAND_SKIP_TO_NEXT
                )
            ).build()
            builder.addAction(nextAction)
            compactViewIndices.add(actionIndex)
            actionIndex++
        }
        
        // Set compact view indices
        if (compactViewIndices.isNotEmpty()) {
            mediaStyle.setShowActionsInCompactView(*compactViewIndices.toIntArray())
        }
        
        builder.setStyle(mediaStyle)
        
        val notification = builder.build()
        
        return MediaNotification(NOTIFICATION_ID, notification)
    }
    
    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        // Handle custom commands if needed
        return false
    }
}

// Receiver to handle notification dismissal
class NotificationDismissReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Log notification dismissal
        android.util.Log.d("MusifyNotification", "Notification dismissed by user")
    }
}