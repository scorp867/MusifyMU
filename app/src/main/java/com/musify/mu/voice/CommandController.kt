package com.musify.mu.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.Locale
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import org.json.JSONObject


class CommandController(
    private val context: Context,
    private val headphoneDetector: HeadphoneDetector
) {

    private var recognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // VOSK state
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskSpeechService: SpeechService? = null

    fun listen(): Flow<String> = callbackFlow {
        // Check permission first
        if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO)) {
            android.util.Log.e("CommandController", "RECORD_AUDIO permission not granted")
            android.widget.Toast.makeText(
                context,
                "Microphone permission required for voice commands",
                android.widget.Toast.LENGTH_LONG
            ).show()
            close()
            return@callbackFlow
        }

        // STRICT headset-only policy: refuse to start if no headset microphone is available
        val preferredAudioSource = headphoneDetector.getPreferredAudioSource()
        val hasHeadsetMicrophone = headphoneDetector.hasHeadsetMicrophone()

        android.util.Log.d("CommandController", "Preferred audio source: $preferredAudioSource, Has headset mic: $hasHeadsetMicrophone")

        // If no headset microphone is available, close immediately
        if (!hasHeadsetMicrophone) {
            android.util.Log.w("CommandController", "No headset microphone available - voice commands disabled")
            android.widget.Toast.makeText(
                context,
                "Voice commands require a headset microphone",
                android.widget.Toast.LENGTH_LONG
            ).show()
            close()
            return@callbackFlow
        }

        // Force EXCLUSIVE audio routing to headset microphone (headset is confirmed to be available)
        when (preferredAudioSource) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                // Force EXCLUSIVE Bluetooth SCO audio for headset microphone only
                try {
                    // Suppress system audio mode change sounds
                    val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)

                    // Disable all built-in audio inputs first
                    headphoneDetector.disableBuiltInAudioInputs()

                    // Stop any existing audio routing first
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false

                    // Start Bluetooth SCO with exclusive routing
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true

                    // Restore system volume after a brief delay
                    kotlinx.coroutines.GlobalScope.launch {
                        kotlinx.coroutines.delay(500)
                        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalVolume, 0)
                    }

                    android.util.Log.d("CommandController", "Forced EXCLUSIVE Bluetooth SCO audio routing - headset mic only")

                    android.widget.Toast.makeText(
                        context,
                        "Using headset microphone only",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.util.Log.w("CommandController", "Failed to start exclusive Bluetooth SCO", e)
                    android.widget.Toast.makeText(
                        context,
                        "Failed to use headset microphone exclusively",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    close()
                    return@callbackFlow
                }
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                // For wired headsets, also disable built-in inputs
                try {
                    headphoneDetector.disableBuiltInAudioInputs()
                    android.util.Log.d("CommandController", "Using wired headset microphone exclusively")

                    android.widget.Toast.makeText(
                        context,
                        "Using wired headset microphone only",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.util.Log.w("CommandController", "Failed to disable built-in inputs for wired headset", e)
                }
            }
            AudioDeviceInfo.TYPE_USB_HEADSET -> {
                // For USB headsets, also disable built-in inputs
                try {
                    headphoneDetector.disableBuiltInAudioInputs()
                    android.util.Log.d("CommandController", "Using USB headset microphone exclusively")

                    android.widget.Toast.makeText(
                        context,
                        "Using USB headset microphone only",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.util.Log.w("CommandController", "Failed to disable built-in inputs for USB headset", e)
                }
            }
            else -> {
                // This should never happen since we check hasHeadsetMicrophone() above
                android.util.Log.w("CommandController", "Unexpected: No valid headset microphone found despite check")
                android.widget.Toast.makeText(
                    context,
                    "Headset microphone required",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                close()
                return@callbackFlow
            }
        }

        // Try VOSK first; fall back to Android SpeechRecognizer if model load fails
        fun startAndroidRecognizer() {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                android.util.Log.e("CommandController", "Speech recognition not available on this device")
                android.widget.Toast.makeText(
                    context,
                    "Speech recognition not available on this device",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                close()
                return
            }

            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val listener = object : android.speech.RecognitionListener {
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
                override fun onBeginningOfSpeech() { }
                override fun onBufferReceived(buffer: ByteArray) { }
                override fun onEndOfSpeech() { }
                override fun onEvent(eventType: Int, params: Bundle) { }
                override fun onPartialResults(partialResults: Bundle) { }
                override fun onRmsChanged(rmsdB: Float) { }
            }
            recognizer?.setRecognitionListener(listener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                if (headphoneDetector.hasHeadsetMicrophone()) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    android.util.Log.d("CommandController", "Configured for headset microphone with offline preference")
                }
            }
            try {
                recognizer?.startListening(intent)
                android.util.Log.d("CommandController", "Started listening (Android SpeechRecognizer)")
            } catch (e: Exception) {
                android.util.Log.e("CommandController", "Failed to start listening", e)
                android.widget.Toast.makeText(
                    context,
                    "Failed to start voice recognition: ${e.localizedMessage}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                close()
                return
            }
        }

        fun parseVoskText(json: String?): String? {
            return try {
                if (json.isNullOrBlank()) return@try null
                val obj = JSONObject(json)
                val finalText = obj.optString("text").trim()
                if (finalText.isNotEmpty()) return@try finalText
                val partial = obj.optString("partial").trim()
                if (partial.isNotEmpty()) partial else null
            } catch (e: Exception) {
                null
            }
        }

        fun startVoskListening(withModel: Model) {
            try {
                voskRecognizer = Recognizer(withModel, 16000.0f)
                voskSpeechService = SpeechService(voskRecognizer, 16000.0f)
                voskSpeechService?.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        // Optionally emit partials for responsiveness (commented to reduce noise)
                        // parseVoskText(hypothesis)?.let { trySend(it) }
                    }
                    override fun onResult(hypothesis: String?) {
                        parseVoskText(hypothesis)?.let { text ->
                            val normalized = text.lowercase(Locale.getDefault())
                            android.util.Log.d("CommandController", "VOSK result: $normalized")
                            trySend(normalized)
                        }
                    }
                    override fun onFinalResult(hypothesis: String?) {
                        parseVoskText(hypothesis)?.let { text ->
                            val normalized = text.lowercase(Locale.getDefault())
                            android.util.Log.d("CommandController", "VOSK final: $normalized")
                            trySend(normalized)
                        }
                    }
                    override fun onError(e: Exception?) {
                        android.util.Log.w("CommandController", "VOSK error", e)
                    }
                    override fun onTimeout() {
                        android.util.Log.d("CommandController", "VOSK timeout")
                    }
                })
                android.util.Log.d("CommandController", "Started listening (VOSK continuous)")
            } catch (e: Exception) {
                android.util.Log.w("CommandController", "Failed to start VOSK listening, falling back", e)
                startAndroidRecognizer()
            }
        }

        // Load model if needed then start Vosk
        if (voskModel != null) {
            startVoskListening(voskModel!!)
        } else {
            android.util.Log.d("CommandController", "Unpacking/loading VOSK model...")
            StorageService.unpack(
                context,
                // Asset directory name if bundled, else this will trigger error callback
                "model-en-us",
                // Destination directory name inside app files dir
                "model",
                { model ->
                    android.util.Log.d("CommandController", "VOSK model ready")
                    voskModel = model
                    startVoskListening(model)
                },
                { ex ->
                    android.util.Log.w("CommandController", "VOSK model unavailable: ${ex.message}. Using Android SpeechRecognizer fallback")
                    startAndroidRecognizer()
                }
            )
        }

        awaitClose {
            try {
                voskSpeechService?.stop()
                voskRecognizer?.close()
            } catch (_: Exception) {}
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
            } catch (_: Exception) {}

            // Clean up audio routing - restore to default state
            val preferredAudioSourceClose = headphoneDetector.getPreferredAudioSource()
            when (preferredAudioSourceClose) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    try {
                        // Suppress system audio mode change sounds during cleanup
                        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
                        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)

                        // Stop Bluetooth SCO
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false

                        // Restore normal audio mode
                        audioManager.mode = AudioManager.MODE_NORMAL

                        // Restore system volume after a brief delay
                        kotlinx.coroutines.GlobalScope.launch {
                            kotlinx.coroutines.delay(300)
                            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalVolume, 0)
                        }

                        android.util.Log.d("CommandController", "Restored default audio routing - headset mic exclusive mode ended")
                    } catch (e: Exception) {
                        android.util.Log.w("CommandController", "Failed to restore default audio routing", e)
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
