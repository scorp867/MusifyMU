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

    // Minimum required confidence for a command to be accepted
    @Volatile
    var confidenceThreshold: Float = 0.6f

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
                override fun onReadyForSpeech(params: Bundle) { }
                override fun onError(error: Int) { }
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

        fun parseVoskResult(json: String?): Pair<String, Float>? {
            return try {
                if (json.isNullOrBlank()) return@try null
                val obj = JSONObject(json)
                val text = obj.optString("text").trim()
                if (text.isEmpty()) return@try null

                var confidence = Float.NaN
                // Prefer alternatives.confidence if present
                val alts = obj.optJSONArray("alternatives")
                if (alts != null && alts.length() > 0) {
                    val first = alts.optJSONObject(0)
                    if (first != null) {
                        val c = first.optDouble("confidence", Double.NaN)
                        if (!c.isNaN()) confidence = c.toFloat()
                    }
                }
                // Fallback to average token conf
                if (confidence.isNaN()) {
                    val results = obj.optJSONArray("result")
                    if (results != null && results.length() > 0) {
                        var sum = 0.0
                        var count = 0
                        for (i in 0 until results.length()) {
                            val item = results.optJSONObject(i)
                            if (item != null) {
                                val c = item.optDouble("conf", Double.NaN)
                                if (!c.isNaN()) {
                                    sum += c
                                    count++
                                }
                            }
                        }
                        if (count > 0) confidence = (sum / count).toFloat()
                    }
                }
                // If still unknown, assume high confidence due to grammar constraints
                val finalConfidence = if (confidence.isNaN()) 1.0f else confidence
                Pair(text.lowercase(Locale.getDefault()), finalConfidence)
            } catch (_: Exception) {
                null
            }
        }

        fun startVoskListening(withModel: Model) {
            try {// Define grammar JSON (restricts recognition)
                val grammar: String = Command.values()
                    .flatMap { it.phrases } // collect all phrases
                    .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

                // Use grammar constructor instead of free-form
                voskRecognizer = Recognizer(withModel, 16000.0f, grammar)
                voskSpeechService = SpeechService(voskRecognizer, 16000.0f)
                voskSpeechService?.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) { }
                    override fun onResult(hypothesis: String?) {
                        parseVoskResult(hypothesis)?.let { (text, conf) ->
                            if (conf >= confidenceThreshold) {
                                trySend(text)
                            } else {
                                android.util.Log.d("CommandController", "Discarded low-confidence result ($conf): $text")
                            }
                        }
                    }
                    override fun onFinalResult(hypothesis: String?) {
                        parseVoskResult(hypothesis)?.let { (text, conf) ->
                            if (conf >= confidenceThreshold) {
                                trySend(text)
                            } else {
                                android.util.Log.d("CommandController", "Discarded low-confidence final ($conf): $text")
                            }
                        }
                    }
                    override fun onError(e: Exception?) { }
                    override fun onTimeout() { }
                })
                android.util.Log.d("CommandController", "Started listening (VOSK continuous)")
            } catch (e: Exception) {
                android.util.Log.w("CommandController", "Failed to start VOSK listening, falling back", e)
                startAndroidRecognizer()
            }
        }

        // Ensure model via assets first, then download fallback
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val model = VoskModelProvider.ensureModel(context)
                voskModel = model
                startVoskListening(model)

            } catch (e: Exception) {
                android.util.Log.w("CommandController", "Model ensure failed: ${e.message}")
                startAndroidRecognizer()
            }
        }

        awaitClose {
            try { voskSpeechService?.stop(); voskRecognizer?.close() } catch (_: Exception) {}
            try { recognizer?.stopListening(); recognizer?.destroy() } catch (_: Exception) {}

            // Clean up audio routing - restore to default state
            val preferredAudioSourceClose = headphoneDetector.getPreferredAudioSource()
            when (preferredAudioSourceClose) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    try {
                        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
                        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        audioManager.mode = AudioManager.MODE_NORMAL
                        kotlinx.coroutines.GlobalScope.launch {
                            kotlinx.coroutines.delay(300)
                            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalVolume, 0)
                        }
                    } catch (_: Exception) { }
                }
                else -> { }
            }
        }
    }

    fun listenForCommandWindow(timeoutMs: Long): Flow<String> = callbackFlow {
        if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO)) {
            android.util.Log.e("CommandController", "RECORD_AUDIO permission not granted")
            close()
            return@callbackFlow
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val model = voskModel ?: VoskModelProvider.ensureModel(context).also { voskModel = it }
                // Restricted grammar to known commands
                val grammar: String = Command.values().flatMap { it.phrases }.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
                voskRecognizer = Recognizer(model, 16000.0f, grammar)
                voskSpeechService = SpeechService(voskRecognizer, 16000.0f)

                // Schedule timeout to stop listening window
                val stopper = kotlinx.coroutines.GlobalScope.launch {
                    kotlinx.coroutines.delay(timeoutMs)
                    try { voskSpeechService?.stop() } catch (_: Exception) { }
                }

                voskSpeechService?.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) { }
                    override fun onResult(hypothesis: String?) {
                        parseVoskResult(hypothesis)?.let { (text, conf) ->
                            if (conf >= confidenceThreshold) trySend(text)
                        }
                    }
                    override fun onFinalResult(hypothesis: String?) {
                        parseVoskResult(hypothesis)?.let { (text, conf) ->
                            if (conf >= confidenceThreshold) trySend(text)
                        }
                        try { voskSpeechService?.stop() } catch (_: Exception) { }
                    }
                    override fun onError(e: Exception?) { try { voskSpeechService?.stop() } catch (_: Exception) { } }
                    override fun onTimeout() { try { voskSpeechService?.stop() } catch (_: Exception) { } }
                })
            } catch (e: Exception) {
                android.util.Log.w("CommandController", "Failed to start VOSK window: ${e.message}")
                close(e)
                return@launch
            }
        }

        awaitClose {
            try { voskSpeechService?.stop(); voskRecognizer?.close() } catch (_: Exception) {}
        }
    }

    fun interpretCommand(text: String): Command? {
        val cleanText = text.trim().lowercase(Locale.getDefault())
        return when {
            listOf("play", "resume", "start", "go").any { cleanText.contains(it) } -> Command.PLAY
            listOf("pause", "stop", "halt", "freeze").any { cleanText.contains(it) } -> Command.PAUSE
            listOf("next", "skip", "forward", "advance").any { cleanText.contains(it) } -> Command.NEXT
            listOf("previous", "back", "rewind", "last").any { cleanText.contains(it) } -> Command.PREV
            cleanText.contains("shuffle") && (cleanText.contains("on") || cleanText.contains("enable")) -> Command.SHUFFLE_ON
            cleanText.contains("shuffle") && (cleanText.contains("off") || cleanText.contains("disable")) -> Command.SHUFFLE_OFF
            cleanText.contains("repeat") && cleanText.contains("one") -> Command.REPEAT_ONE
            cleanText.contains("repeat") && cleanText.contains("all") -> Command.REPEAT_ALL
            cleanText.contains("repeat") && (cleanText.contains("off") || cleanText.contains("disable")) -> Command.REPEAT_OFF
            cleanText.contains("volume") && (cleanText.contains("up") || cleanText.contains("increase")) -> Command.VOLUME_UP
            cleanText.contains("volume") && (cleanText.contains("down") || cleanText.contains("decrease")) -> Command.VOLUME_DOWN
            cleanText.contains("mute") -> Command.MUTE
            cleanText.contains("gym") && cleanText.contains("mode") -> Command.TOGGLE_GYM_MODE
            else -> null
        }
    }

    enum class Command(val phrases: List<String>) {
        PLAY(listOf("play","resume","start","go")),
        PAUSE(listOf("pause","stop","halt","freeze")),
        NEXT(listOf("next","skip","forward","advance")),
        PREV(listOf("previous","back","rewind","last")),
        SHUFFLE_ON(listOf("shuffle on","shuffle enable")),
        SHUFFLE_OFF(listOf("shuffle off","shuffle disable")),
        REPEAT_ONE(listOf("repeat one")),
        REPEAT_ALL(listOf("repeat all")),
        REPEAT_OFF(listOf("repeat off")),
        VOLUME_UP(listOf("volume up","volume increase")),
        VOLUME_DOWN(listOf("volume down","volume decrease")),
        MUTE(listOf("mute")),
        TOGGLE_GYM_MODE(listOf("gym mode"))
    }
}
