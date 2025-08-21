package com.musify.mu.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

class CommandController(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    /**
     * Check if a headset with microphone is connected
     */
    fun isHeadsetMicrophoneAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            audioDevices.any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
                    else -> false
                }
            }
        } else {
            // For older Android versions, check if headset is plugged in
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn
        }
    }
    
    /**
     * Configure audio routing to prefer headset microphone
     */
    private fun configureHeadsetAudio(): Boolean {
        return try {
            if (isHeadsetMicrophoneAvailable()) {
                // Enable Bluetooth SCO if Bluetooth headset is available
                if (audioManager.isBluetoothScoAvailableOffCall) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                
                // Set audio mode to communication for better headset integration
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                
                // Route audio to headset
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // For Android 12+, the system should automatically route to the preferred device
                    val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val headsetDevice = inputDevices.find { device ->
                        when (device.type) {
                            AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                            else -> false
                        }
                    }
                    headsetDevice != null
                } else {
                    // For older versions, use speaker phone settings
                    audioManager.isSpeakerphoneOn = false
                    true
                }
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("CommandController", "Failed to configure headset audio", e)
            false
        }
    }
    
    /**
     * Reset audio configuration
     */
    private fun resetAudioConfiguration() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (e: Exception) {
            android.util.Log.e("CommandController", "Failed to reset audio configuration", e)
        }
    }

    fun listen(): Flow<String> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            close()
            return@callbackFlow
        }
        
        // Check if headset is available before starting
        if (!isHeadsetMicrophoneAvailable()) {
            android.util.Log.w("CommandController", "No headset microphone available")
            trySend("No headset microphone detected")
            close()
            return@callbackFlow
        }
        
        // Configure audio for headset
        val audioConfigured = configureHeadsetAudio()
        if (!audioConfigured) {
            android.util.Log.w("CommandController", "Failed to configure headset audio")
            trySend("Failed to configure headset microphone")
            close()
            return@callbackFlow
        }
        
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { 
                    android.util.Log.d("CommandController", "Voice command recognized: $it")
                    trySend(it.lowercase(Locale.getDefault())) 
                }
                resetAudioConfiguration()
            }
            
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech input"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error"
                }
                android.util.Log.e("CommandController", "Speech recognition error: $errorMessage")
                resetAudioConfiguration()
            }
            
            override fun onEndOfSpeech() {
                android.util.Log.d("CommandController", "End of speech")
            }
            
            override fun onReadyForSpeech(params: Bundle) {
                android.util.Log.d("CommandController", "Ready for speech")
            }
            
            override fun onBeginningOfSpeech() {
                android.util.Log.d("CommandController", "Beginning of speech")
            }
            
            override fun onBufferReceived(buffer: ByteArray) {}
            override fun onEvent(eventType: Int, params: Bundle) {}
            override fun onPartialResults(partialResults: Bundle) {}
            override fun onRmsChanged(rmsdB: Float) {}
        }
        recognizer?.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Prefer offline recognition for better privacy and performance
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        
        try {
            recognizer?.startListening(intent)
            android.util.Log.d("CommandController", "Started listening with headset microphone")
        } catch (e: Exception) {
            android.util.Log.e("CommandController", "Failed to start listening", e)
            resetAudioConfiguration()
            close()
        }

        awaitClose {
            android.util.Log.d("CommandController", "Stopping voice recognition")
            recognizer?.stopListening()
            recognizer?.destroy()
            resetAudioConfiguration()
        }
    }

    fun interpretCommand(text: String): Command? {
        return when {
            listOf("play", "resume").any { it in text } -> Command.PLAY
            listOf("pause", "stop").any { it in text } -> Command.PAUSE
            listOf("next", "skip").any { it in text } -> Command.NEXT
            listOf("previous", "back").any { it in text } -> Command.PREV
            "shuffle" in text && "on" in text -> Command.SHUFFLE_ON
            "shuffle" in text && "off" in text -> Command.SHUFFLE_OFF
            "repeat" in text && "one" in text -> Command.REPEAT_ONE
            "repeat" in text && "all" in text -> Command.REPEAT_ALL
            "repeat" in text && "off" in text -> Command.REPEAT_OFF
            else -> null
        }
    }

    enum class Command {
        PLAY, PAUSE, NEXT, PREV,
        SHUFFLE_ON, SHUFFLE_OFF,
        REPEAT_ONE, REPEAT_ALL, REPEAT_OFF
    }
}
