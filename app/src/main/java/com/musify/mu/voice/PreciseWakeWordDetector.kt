package com.musify.mu.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService

class PreciseWakeWordDetector(
    private val context: Context,
    private val headphoneDetector: HeadphoneDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    @Volatile
    private var isRunning: Boolean = false

    // Cooldown to avoid multiple triggers from the same utterance
    private var lastTriggerTimeMs: Long = 0
    private val triggerCooldownMs: Long = 1500

    var onWakeWordDetected: (() -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                val model = voskModel ?: VoskModelProvider.ensureModel(context).also { voskModel = it }

                // Grammar restricted to wake phrase only
                val grammar = "[\"hey musify\"]"
                recognizer = Recognizer(model, 16000.0f, grammar)
                speechService = SpeechService(recognizer, 16000.0f)

                speechService?.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) { }
                    override fun onResult(hypothesis: String?) {
                        maybeTrigger(hypothesis)
                    }
                    override fun onFinalResult(hypothesis: String?) {
                        maybeTrigger(hypothesis)
                    }
                    override fun onError(e: Exception?) { }
                    override fun onTimeout() { }
                })
            } catch (e: Exception) {
                android.util.Log.w("PreciseWakeWordDetector", "Failed to start wake-word detector", e)
                stop()
            }
        }
    }

    private fun maybeTrigger(hypothesis: String?) {
        if (!isRunning) return
        try {
            val text = tryExtractText(hypothesis)
            if (text.equals("hey musify", ignoreCase = true)) {
                val now = System.currentTimeMillis()
                if (now - lastTriggerTimeMs > triggerCooldownMs) {
                    lastTriggerTimeMs = now
                    // Stop to release microphone for command window
                    stop()
                    onWakeWordDetected?.invoke()
                }
            }
        } catch (_: Exception) { }
    }

    private fun tryExtractText(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = org.json.JSONObject(json)
            val text = obj.optString("text").trim()
            if (text.isNotEmpty()) text else null
        } catch (_: Exception) {
            null
        }
    }

    fun stop() {
        isRunning = false
        try { speechService?.stop() } catch (_: Exception) { }
        try { recognizer?.close() } catch (_: Exception) { }
        speechService = null
        recognizer = null
    }

    fun cleanup() {
        stop()
        scope.cancel()
    }
}