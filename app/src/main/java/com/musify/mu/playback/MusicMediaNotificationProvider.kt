package com.musify.mu.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.CommandButton

class MusicMediaNotificationProvider(private val context: Context) : MediaNotification.Provider {

    companion object {
        const val CHANNEL_ID = "musify_playback"
        const val NOTIFICATION_ID = 4201
    }

    private val notificationManager by lazy { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: com.google.common.collect.ImmutableList<CommandButton>?,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        ensureChannel()

        val player = mediaSession.player
        val md = player.mediaMetadata

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(md.title ?: "Musify MU")
            .setContentText(md.artist ?: md.albumTitle ?: "")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mediaSession.sessionActivity)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(player.isPlaying)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(mediaSession))

        // Add actions provided by Media3 custom layout if present
        customLayout?.forEach { button ->
            val compat = actionFactory.createMediaAction(mediaSession, button)
            if (compat != null) builder.addAction(compat)
        }

        val notification: Notification = builder.build()
        return MediaNotification(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(ch)
            }
        }
    }
}

