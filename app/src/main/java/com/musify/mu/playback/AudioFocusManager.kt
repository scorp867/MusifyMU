package com.musify.mu.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

class AudioFocusManager(context: Context, private val onChange: (Int) -> Unit) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener(onChange)
        .setWillPauseWhenDucked(true)
        .build()

    fun request(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
