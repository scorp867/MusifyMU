package com.musify.mu.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
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
	private lateinit var headphoneDetector: HeadphoneDetector
	private lateinit var commandController: CommandController
	private lateinit var audioManager: AudioManager

	// Engines and audio
	private var porcupine: Porcupine? = null
	private var audioRecord: AudioRecord? = null
	private var audioLoopJob: Job? = null
	private var voskModel: Model? = null
	private var voskRecognizer: Recognizer? = null
	private var isInCommandWindow = false
	private var commandWindowEndAt = 0L

	@Volatile private var confidenceThreshold: Float = 0.9f

	// Injected via runtime; do not persist secrets
	private val accessKey: String by lazy {
		"qcLu6oLmNq9fkqv5tbWqoIt23/qhJFFUerWZGsg0fim99/npnxhxdg=="
	}

	override fun onCreate() {
		super.onCreate()
		headphoneDetector = HeadphoneDetector(this)
		commandController = CommandController(this, headphoneDetector)
		audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
		createNotificationChannel()

		// Prepare Vosk model in background early
		serviceScope.launch(Dispatchers.IO) {
			try { org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARN) } catch (_: Exception) { }
			try { voskModel = VoskModelProvider.ensureModel(this@WakeWordService) } catch (e: Exception) {
				android.util.Log.w("WakeWordService", "Failed to ensure VOSK model early: ${e.message}")
			}
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_STOP -> {
				stopListening()
				stopSelf()
				return START_NOT_STICKY
			}
			else -> {
				startForeground(NOTIF_ID, buildNotification())
				startListening()
				return START_STICKY
			}
		}
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onDestroy() {
		super.onDestroy()
		stopListening()
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

	private fun startListening() {
		if (audioLoopJob != null) return

		if (!headphoneDetector.hasHeadsetMicrophone()) {
			android.widget.Toast.makeText(this, "Headset with microphone required", android.widget.Toast.LENGTH_LONG).show()
			return
		}
		// Ensure exclusive routing once
		headphoneDetector.forceAudioRoutingToHeadset()

		try {
			// Prepare Porcupine engine
			val resName = "hey_musify"
			val resId = resources.getIdentifier(resName, "raw", packageName)
			if (resId == 0) throw PorcupineException("Wake word resource not found: $resName")
			val keywordFilePath = ensureKeywordFile(resId, "$resName.ppn")
			porcupine = Porcupine.Builder()
				.setAccessKey(accessKey)
				.setKeywordPath(keywordFilePath)
				.setSensitivity(0.6f)
				.build(applicationContext)

			val sampleRate = porcupine!!.sampleRate
			val frameLength = porcupine!!.frameLength
			val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
			val bufferSize = max(minBuf, frameLength * 2 * 4)
			audioRecord = AudioRecord(
				MediaRecorder.AudioSource.MIC,
				sampleRate,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				bufferSize
			)
			audioRecord?.startRecording()

			audioLoopJob = serviceScope.launch(Dispatchers.Default) {
				val frame = ShortArray(frameLength)
				while (isActive) {
					val n = audioRecord?.read(frame, 0, frameLength) ?: -1
					if (n <= 0) continue

					if (!isInCommandWindow) {
						val keywordIndex = try { porcupine?.process(frame) ?: -1 } catch (e: Exception) { -1 }
						if (keywordIndex >= 0) {
							openCommandWindow()
						}
					} else {
						processCommandFrame(frame, n)
						if (SystemClock.elapsedRealtime() >= commandWindowEndAt) {
							finalizeCommandWindow()
						}
					}
				}
			}
			android.util.Log.d("WakeWordService", "Listening started (single AudioRecord)")
		} catch (e: Exception) {
			android.util.Log.e("WakeWordService", "Failed to start listening", e)
			stopListening()
		}
	}

	private fun stopListening() {
		try { audioLoopJob?.cancel() } catch (_: Exception) {}
		audioLoopJob = null
		try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
		audioRecord = null
		try { porcupine?.delete() } catch (_: Exception) {}
		porcupine = null
		try { voskRecognizer?.close() } catch (_: Exception) {}
		voskRecognizer = null
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

	private fun openCommandWindow() {
		isInCommandWindow = true
		commandWindowEndAt = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS
		serviceScope.launch(Dispatchers.Main) {
			ensureVoskRecognizer()
		}
	}

	private fun finalizeCommandWindow() {
		try {
			val json = voskRecognizer?.finalResult
			val text = parseText(json)
			if (!text.isNullOrBlank()) processCommand(text)
		} catch (_: Exception) { }
		try { voskRecognizer?.close() } catch (_: Exception) {}
		voskRecognizer = null
		isInCommandWindow = false
	}

	private fun processCommandFrame(frame: ShortArray, n: Int) {
		val rec = voskRecognizer ?: return
		try {
			val bytes = shortsToBytesLE(frame, n)
			val accepted = rec.acceptWaveForm(bytes, bytes.size)
			if (accepted) {
				val json = rec.result
				val text = parseText(json)
				if (!text.isNullOrBlank()) {
					processCommand(text)
					// Close early and return to wakeword
					try { rec.reset() } catch (_: Exception) {}
					isInCommandWindow = false
				}
			}
		} catch (e: Exception) {
			android.util.Log.w("WakeWordService", "Vosk accept frame failed: ${e.message}")
		}
	}

	private fun processCommand(text: String) {
		try {
			val vcm = VoiceControlManager.getInstance(this)
			if (vcm != null) {
				try { vcm.processVoiceCommand(text) } catch (_: Exception) { }
			} else {
				android.util.Log.w("WakeWordService", "VoiceControlManager not available")
			}
		} catch (e: Exception) {
			android.util.Log.e("WakeWordService", "Failed to process command: ${e.message}")
		}
	}

	private fun parseText(json: String?): String? {
		if (json.isNullOrBlank()) return null
		return try {
			val obj = org.json.JSONObject(json)
			obj.optString("text").trim().lowercase()
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
		val outFile = File(filesDir, outName)
		return try {
			if (!outFile.exists()) {
				resources.openRawResource(resId).use { input ->
					FileOutputStream(outFile).use { output ->
						val buffer = ByteArray(8 * 1024)
						while (true) {
							val read = input.read(buffer)
							if (read <= 0) break
							output.write(buffer, 0, read)
						}
						output.flush()
					}
				}
			}
			outFile.absolutePath
		} catch (e: Exception) {
			throw PorcupineException("Failed to prepare keyword file: ${e.message}")
		}
	}
}