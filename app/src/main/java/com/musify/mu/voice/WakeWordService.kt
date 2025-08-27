package com.musify.mu.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
// Porcupine removed in Vosk-only mode
import com.musify.mu.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import org.vosk.Model
import org.vosk.Recognizer
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.collect
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.Manifest
import com.musify.mu.AppForegroundState

class WakeWordService : Service() {
    companion object {
        private const val NOTIF_CHANNEL_ID = "wake_word_channel"
        private const val NOTIF_ID = 6102
        private const val ACTION_START = "com.musify.mu.voice.action.START"
        private const val ACTION_STOP = "com.musify.mu.voice.action.STOP"
        private const val COMMAND_WINDOW_MS = 3500L

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var headphoneDetector: HeadphoneDetector? = null
    private var commandController: CommandController? = null
    private lateinit var audioManager: AudioManager

    // Engines and audio
    private var audioRecord: AudioRecord? = null
    private var audioLoopJob: Job? = null
    private var webRtcCapture: WebRtcCapture? = null
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskWakeRecognizer: Recognizer? = null
    private var isInCommandWindow = false
    private var commandWindowEndAt = 0L
    private var headphoneMonitorJob: Job? = null
    private var isForegroundStarted: Boolean = false

    @Volatile private var confidenceThreshold: Float = 0.7f
    @Volatile private var wakeConfidenceThreshold: Float = 0.8f

    // Direct control fallback when VCM is unavailable
    @Volatile private var mediaController: MediaController? = null

    // No AccessKey needed for Vosk-only mode

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()


        // Prepare Vosk model in background early
        serviceScope.launch(Dispatchers.IO) {
            try { org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.INFO) } catch (_: Exception) { }
            try { voskModel = VoskModelProvider.ensureModel(this@WakeWordService) } catch (e: Exception) {
                android.util.Log.w("WakeWordService", "Failed to ensure VOSK model early: ${e.message}")
            }
        }

        // Warm up media controller fallback in background
        serviceScope.launch(Dispatchers.Main) { ensureController() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                if (isForegroundStarted) {
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
                    isForegroundStarted = false
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Android 14: Do not start a microphone FGS unless RECORD_AUDIO is granted
                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    showToast("Microphone permission required for wakeword")
                    android.util.Log.w("WakeWordService", "Aborting start: RECORD_AUDIO not granted")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // On Android 14+, microphone FGS can only start while app is in foreground (or with exemptions)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !AppForegroundState.isInForeground) {
                    android.util.Log.w("WakeWordService", "Aborting start: App not in foreground for microphone FGS on Android 14+")
                    showToast("Open Musify to start wakeword listening")
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (!isForegroundStarted) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(
                                NOTIF_ID,
                                buildNotification(),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            )
                        } else {
                            startForeground(NOTIF_ID, buildNotification())
                        }
                        isForegroundStarted = true
                    } catch (t: Throwable) {
                        android.util.Log.e("WakeWordService", "startForeground failed: ${t.message}")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                startListening()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        if (isForegroundStarted) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            isForegroundStarted = false
        }
        serviceScope.cancel()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Gym Mode Wake Word")
            .setContentText("Listening for wake word…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(NOTIF_CHANNEL_ID, "Wake Word", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startListening() {
        if (audioLoopJob != null) return

        if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            showToast("Microphone permission required for wakeword")
            android.util.Log.w("WakeWordService", "Missing RECORD_AUDIO permission")
            return
        }

        // Lazily create headphone detector and command controller
        val detector = headphoneDetector ?: HeadphoneDetector(this).also { headphoneDetector = it }
        val controller = commandController ?: CommandController(this, detector).also { commandController = it }

        if (!detector.hasHeadsetMicrophone()) {
            android.widget.Toast.makeText(this, "Headset with microphone required", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Ensure exclusive routing once (guard Bluetooth permission on S+)
        try {
            val isBt = detector.getPreferredAudioSource() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isBt || hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
                detector.forceAudioRoutingToHeadset()
            } else {
                android.util.Log.w("WakeWordService", "BLUETOOTH_CONNECT not granted - skipping forced BT routing")
                showToast("Bluetooth permission missing - default mic routing")
            }
        } catch (se: SecurityException) {
            android.util.Log.w("WakeWordService", "Audio routing denied by SecurityException")
            showToast("Audio routing permission denied")
        }

        try {
            // Start WebRTC capture with software AEC/NS/AGC. It emits processed 16k mono frames.
            webRtcCapture = WebRtcCapture(
                context = this,
                onFrame = { frame ->
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            if (!isInCommandWindow) {
                                val wake = voskWakeRecognizer
                                if (wake != null) {
                                    val bytes = shortsToBytesLE(frame, frame.size)
                                    val accepted = wake.acceptWaveForm(bytes, bytes.size)
                                    if (accepted) {
                                        val pair = parseVoskResult(wake.result)
                                        if (pair != null) {
                                            val text = pair.first
                                            val conf = pair.second
                                            if (isWakePhrase(text) && conf >= wakeConfidenceThreshold) {
                                                android.util.Log.d("WakeWordService", "Wakeword detected by Vosk (conf=${"%.2f".format(conf)})")
                                                showToast("Wakeword detected")
                                                openCommandWindow()
                                                try { wake.reset() } catch (_: Exception) {}
                                            }
                                        }
                                    } else {
                                        val partialJson = wake.partialResult
                                        val partialText = parseText(partialJson) ?: ""
                                        if (isWakePhrase(partialText)) {
                                            android.util.Log.d("WakeWordService", "Wakeword partial detected by Vosk")
                                            showToast("Wakeword detected")
                                            openCommandWindow()
                                            try { wake.reset() } catch (_: Exception) {}
                                        }
                                    }
                                }
                            } else {
                                processCommandFrame(frame, frame.size)
                                if (SystemClock.elapsedRealtime() >= commandWindowEndAt) {
                                    android.util.Log.d("WakeWordService", "Command window timeout reached")
                                    showToast("Command window ended")
                                    finalizeCommandWindow()
                                }
                            }
                        } catch (t: Throwable) {
                            android.util.Log.w("WakeWordService", "Frame handling failed: ${t.message}")
                        }
                    }
                },
                onError = { msg -> android.util.Log.e("WakeWordService", msg) }
            )
            webRtcCapture?.start()

            // Monitor headphone connectivity and stop if disconnected
            headphoneMonitorJob?.cancel()
            headphoneMonitorJob = serviceScope.launch(Dispatchers.Main) {
                detector.isHeadphonesConnected.collect { connected ->
                    if (!connected) {
                        android.util.Log.w("WakeWordService", "Headphones disconnected - stopping wakeword listening")
                        showToast("Headphones disconnected - stopping")
                        try { detector.restoreDefaultAudioRouting() } catch (_: Exception) {}
                        stopListening()
                        stopSelf()
                    }
                }
            }

            // Prepare wake recognizer upfront
            serviceScope.launch(Dispatchers.Main) { ensureWakeRecognizer() }
            showToast("Wakeword listening started")
            android.util.Log.d("WakeWordService", "Listening started (WebRTC AudioProcessing)")
        } catch (e: Exception) {
            android.util.Log.e("WakeWordService", "Failed to start listening", e)
            showToast("Failed to start listening: ${e.message}")
            stopListening()
        }
    }

    private fun stopListening() {
        try { audioLoopJob?.cancel() } catch (_: Exception) {}
        audioLoopJob = null
        try { headphoneMonitorJob?.cancel() } catch (_: Exception) {}
        headphoneMonitorJob = null
        try { webRtcCapture?.stop() } catch (_: Exception) {}
        webRtcCapture = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { voskRecognizer?.close() } catch (_: Exception) {}
        voskRecognizer = null
        try { voskWakeRecognizer?.close() } catch (_: Exception) {}
        voskWakeRecognizer = null
        try { headphoneDetector?.restoreDefaultAudioRouting() } catch (_: Exception) {}
        try { headphoneDetector?.cleanup() } catch (_: Exception) {}
        headphoneDetector = null
        commandController = null
        // Audio effects are tied to session; they are released with AudioRecord. Nothing further needed.
    }

    private suspend fun ensureVoskRecognizer(): Recognizer? {
        return withContext(Dispatchers.IO) {
            try {
                val model = voskModel ?: VoskModelProvider.ensureModel(this@WakeWordService).also { voskModel = it }
                val grammar = buildGrammar()
                val rec = Recognizer(model, 16000.0f, grammar)
                voskRecognizer = rec
                rec
            } catch (e: Exception) {
                android.util.Log.w("WakeWordService", "Failed to create Vosk recognizer: ${e.message}")
                null
            }
        }
    }

    private suspend fun ensureWakeRecognizer(): Recognizer? {
        return withContext(Dispatchers.IO) {
            try {
                val model = voskModel ?: VoskModelProvider.ensureModel(this@WakeWordService).also { voskModel = it }
                val wakeGrammar = buildWakeGrammar()
                val rec = Recognizer(model, 16000.0f, wakeGrammar)
                voskWakeRecognizer = rec
                rec
            } catch (e: Exception) {
                android.util.Log.w("WakeWordService", "Failed to create wake recognizer: ${e.message}")
                null
            }
        }
    }

    private fun buildWakeGrammar(): String {
        val wakePhrases = listOf(
            "hey musify",
            "hey music fy",
            "hey music fi",
            "hey music five",
            "hey muzify",
            "hey musefy"
        )
        return wakePhrases.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    private suspend fun ensureController(): MediaController? {
        if (mediaController != null) return mediaController
        return try {
            val token = SessionToken(this, ComponentName(this, com.musify.mu.playback.PlayerService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            val controller = future.await()
            mediaController = controller
            controller
        } catch (e: Exception) {
            android.util.Log.w("WakeWordService", "Failed to create MediaController: ${e.message}")
            null
        }
    }

    private fun openCommandWindow() {
        isInCommandWindow = true
        commandWindowEndAt = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS
        showToast("Listening for command…")
        android.util.Log.d("WakeWordService", "Opening command window for ${COMMAND_WINDOW_MS}ms")
        serviceScope.launch(Dispatchers.Main) {
            ensureVoskRecognizer()
            if (voskRecognizer != null) {
                showToast("Vosk ready")
                android.util.Log.d("WakeWordService", "Vosk recognizer ready")
            } else {
                showToast("Vosk init failed")
                android.util.Log.w("WakeWordService", "Vosk recognizer failed to init")
            }
        }
    }

    private fun finalizeCommandWindow() {
        try {
            val json = voskRecognizer?.finalResult
            val pair = parseVoskResult(json)
            if (pair != null) {
                val (text, conf) = pair
                android.util.Log.d("WakeWordService", "Final Vosk result: conf=$conf text=$text")
                if (conf >= confidenceThreshold) {
                    showToast("Heard: $text")
                    processCommand(text)
                } else {
                    showToast("Ignored (low conf ${"%.2f".format(conf)} < ${"%.2f".format(confidenceThreshold)})")
                }
            } else {
                android.util.Log.d("WakeWordService", "No final command recognized")
            }
        } catch (_: Exception) { }
        try { voskRecognizer?.close() } catch (_: Exception) {}
        voskRecognizer = null
        isInCommandWindow = false
        showToast("Wakeword listening…")
        // Ensure wake recognizer is available for the next cycle
        serviceScope.launch(Dispatchers.Main) { ensureWakeRecognizer() }
    }

    private fun processCommandFrame(frame: ShortArray, n: Int) {
        val rec = voskRecognizer ?: return
        try {
            val bytes = shortsToBytesLE(frame, n)
            val accepted = rec.acceptWaveForm(bytes, bytes.size)
            if (accepted) {
                val json = rec.result
                val pair = parseVoskResult(json)
                if (pair != null) {
                    val (text, conf) = pair
                    android.util.Log.d("WakeWordService", "Interim Vosk result: conf=$conf text=$text")
                    if (conf >= confidenceThreshold) {
                        showToast("Heard: $text")
                        processCommand(text)
                        // Prepare for next utterance but keep command window active until timeout
                        try { rec.reset() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WakeWordService", "Vosk accept frame failed: ${e.message}")
        }
    }


    private fun processCommand(text: String) {
        try {
            val vcm = VoiceControlManager.getInstance(this)
            val cmd = commandController?.interpretCommand(text)
            if (cmd == null) {
                showToast("Unrecognized: $text")
                android.util.Log.d("WakeWordService", "Unrecognized command: $text")
                return
            }
            if (vcm != null) {
                serviceScope.launch(Dispatchers.Main) {
                    try {
                        vcm.processVoiceCommand(text)
                        showToast("Executed: ${cmd.name}")
                        android.util.Log.d("WakeWordService", "VCM executed: ${cmd.name}")
                    } catch (e: Exception) {
                        android.util.Log.e("WakeWordService", "VCM execution failed: ${e.message}")
                        showToast("VCM failed: ${e.message}")
                    }
                }
            } else {
                // Direct fallback
                serviceScope.launch(Dispatchers.Main) {
                    val controller = ensureController()
                    if (controller == null) {
                        android.util.Log.w("WakeWordService", "Controller unavailable for direct execution")
                        showToast("Controller unavailable")
                        return@launch
                    }
                    when (cmd) {
                        CommandController.Command.PLAY -> if (!controller.isPlaying) controller.play()
                        CommandController.Command.PAUSE -> if (controller.isPlaying) controller.pause()
                        CommandController.Command.NEXT -> if (controller.hasNextMediaItem()) controller.seekToNext()
                        CommandController.Command.PREV -> if (controller.hasPreviousMediaItem()) controller.seekToPrevious()
                        CommandController.Command.SHUFFLE_ON -> controller.shuffleModeEnabled = true
                        CommandController.Command.SHUFFLE_OFF -> controller.shuffleModeEnabled = false
                        CommandController.Command.REPEAT_ONE -> controller.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                        CommandController.Command.REPEAT_ALL -> controller.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                        CommandController.Command.REPEAT_OFF -> controller.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                        CommandController.Command.VOLUME_UP -> {
                            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (current + 1).coerceAtMost(max), 0)
                        }
                        CommandController.Command.VOLUME_DOWN -> {
                            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (current - 1).coerceAtLeast(0), 0)
                        }
                        CommandController.Command.MUTE -> audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                        CommandController.Command.TOGGLE_GYM_MODE -> { /* no-op here */ }
                        else -> {}
                    }
                    showToast("Executed: ${cmd.name}")
                    android.util.Log.d("WakeWordService", "Direct executed: ${cmd.name}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WakeWordService", "Failed to process command: ${e.message}")
            showToast("Command error: ${e.message}")
        }
    }

    private fun parseText(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = org.json.JSONObject(json)
            obj.optString("text").trim().lowercase()
        } catch (_: Exception) { null }
    }

    private fun parseVoskResult(json: String?): Pair<String, Float>? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = org.json.JSONObject(json)
            val text = obj.optString("text").trim()
            if (text.isEmpty()) return null
            var confidence = Float.NaN
            val alts = obj.optJSONArray("alternatives")
            if (alts != null && alts.length() > 0) {
                val first = alts.optJSONObject(0)
                if (first != null) {
                    val c = first.optDouble("confidence", Double.NaN)
                    if (!c.isNaN()) confidence = c.toFloat()
                }
            }
            if (confidence.isNaN()) {
                val results = obj.optJSONArray("result")
                if (results != null && results.length() > 0) {
                    var sum = 0.0
                    var count = 0
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i)
                        if (item != null) {
                            val c = item.optDouble("conf", Double.NaN)
                            if (!c.isNaN()) { sum += c; count++ }
                        }
                    }
                    if (count > 0) confidence = (sum / count).toFloat()
                }
            }
            val finalConfidence = if (confidence.isNaN()) 1.0f else confidence
            Pair(text.lowercase(), finalConfidence)
        } catch (_: Exception) { null }
    }

    private fun shortsToBytesLE(src: ShortArray, len: Int): ByteArray {
        val out = ByteArray(len * 2)
        var i = 0
        var j = 0
        while (i < len) {
            val v = src[i].toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v ushr 8) and 0xFF).toByte()
            i++
        }
        return out
    }

    private fun buildGrammar(): String {
        return CommandController.Command.values()
            .flatMap { it.phrases }
            .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    private fun ensureKeywordFile(resId: Int, outName: String): String {
        // Unused in Vosk-only mode; keep for compatibility
        return ""
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isWakePhrase(text: String): Boolean {
        val norm = text.trim().lowercase().replace(" ", "")
        return norm.contains("heymusify") || norm.contains("heymusicfy") || norm.contains("heymusicfi") || norm.contains("heymusicfive") || norm.contains("heymuzify") || norm.contains("heymusefy")
    }
}