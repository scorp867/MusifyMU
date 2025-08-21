package com.musify.mu.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

class CommandController(
    private val context: Context,
    private val headphoneDetector: HeadphoneDetector
) {

    private var recognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun listen(): Flow<String> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            close()
            return@callbackFlow
        }
        
        // Force audio routing to headset microphone when available
        val preferredAudioSource = headphoneDetector.getPreferredAudioSource()
        android.util.Log.d("CommandController", "Preferred audio source: $preferredAudioSource")
        
        when (preferredAudioSource) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                // Force Bluetooth SCO audio for headset microphone
                try {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    android.util.Log.d("CommandController", "Forced Bluetooth SCO audio routing")
                } catch (e: Exception) {
                    android.util.Log.w("CommandController", "Failed to start Bluetooth SCO", e)
                }
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                // Ensure wired headset is preferred
                android.util.Log.d("CommandController", "Using wired headset microphone")
            }
            AudioDeviceInfo.TYPE_USB_HEADSET -> {
                // Ensure USB headset is preferred
                android.util.Log.d("CommandController", "Using USB headset microphone")
            }
            else -> {
                android.util.Log.w("CommandController", "No headset microphone available, using device microphone")
            }
        }
        
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { 
                    val recognizedText = it.lowercase(Locale.getDefault())
                    android.util.Log.d("CommandController", "Recognized: $recognizedText")
                    trySend(recognizedText) 
                }
            }
            
            override fun onReadyForSpeech(params: Bundle) {
                android.util.Log.d("CommandController", "Ready for speech")
            }
            
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    else -> "Unknown error: $error"
                }
                android.util.Log.w("CommandController", "Speech recognition error: $errorMessage")
            }
            
            override fun onBeginningOfSpeech() {
                android.util.Log.d("CommandController", "Beginning of speech")
            }
            
            override fun onBufferReceived(buffer: ByteArray) {}
            
            override fun onEndOfSpeech() {
                android.util.Log.d("CommandController", "End of speech")
            }
            
            override fun onEvent(eventType: Int, params: Bundle) {}
            
            override fun onPartialResults(partialResults: Bundle) {}
            
            override fun onRmsChanged(rmsdB: Float) {}
        }
        
        recognizer?.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            
            // Force headset microphone usage if available
            if (headphoneDetector.hasHeadsetMicrophone()) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        
        recognizer?.startListening(intent)

        awaitClose {
            recognizer?.stopListening()
            recognizer?.destroy()
            
            // Clean up audio routing - restore to default state
            val preferredAudioSource = headphoneDetector.getPreferredAudioSource()
            when (preferredAudioSource) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    try {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        android.util.Log.d("CommandController", "Restored default audio routing")
                    } catch (e: Exception) {
                        android.util.Log.w("CommandController", "Failed to stop Bluetooth SCO", e)
                    }
                }
                else -> {
                    // For wired/USB headsets, just log - no special cleanup needed
                    android.util.Log.d("CommandController", "Cleaned up audio routing")
                }
            }
        }
    }

    fun interpretCommand(text: String): Command? {
        val cleanText = text.trim().lowercase(Locale.getDefault())
        
        return when {
            // Play commands
            listOf("play", "resume", "start", "go").any { cleanText.contains(it) } -> Command.PLAY
            
            // Pause commands
            listOf("pause", "stop", "halt", "freeze").any { cleanText.contains(it) } -> Command.PAUSE
            
            // Next track commands
            listOf("next", "skip", "forward", "advance").any { cleanText.contains(it) } -> Command.NEXT
            
            // Previous track commands
            listOf("previous", "back", "rewind", "last").any { cleanText.contains(it) } -> Command.PREV
            
            // Shuffle commands
            cleanText.contains("shuffle") && (cleanText.contains("on") || cleanText.contains("enable")) -> Command.SHUFFLE_ON
            cleanText.contains("shuffle") && (cleanText.contains("off") || cleanText.contains("disable")) -> Command.SHUFFLE_OFF
            
            // Repeat commands
            cleanText.contains("repeat") && cleanText.contains("one") -> Command.REPEAT_ONE
            cleanText.contains("repeat") && cleanText.contains("all") -> Command.REPEAT_ALL
            cleanText.contains("repeat") && (cleanText.contains("off") || cleanText.contains("disable")) -> Command.REPEAT_OFF
            
            // Volume commands
            cleanText.contains("volume") && (cleanText.contains("up") || cleanText.contains("increase")) -> Command.VOLUME_UP
            cleanText.contains("volume") && (cleanText.contains("down") || cleanText.contains("decrease")) -> Command.VOLUME_DOWN
            cleanText.contains("mute") -> Command.MUTE
            
            // Gym mode specific commands
            cleanText.contains("gym") && cleanText.contains("mode") -> Command.TOGGLE_GYM_MODE
            
            else -> null
        }
    }

    enum class Command {
        PLAY, PAUSE, NEXT, PREV,
        SHUFFLE_ON, SHUFFLE_OFF,
        REPEAT_ONE, REPEAT_ALL, REPEAT_OFF,
        VOLUME_UP, VOLUME_DOWN, MUTE,
        TOGGLE_GYM_MODE
    }
}
