package com.musify.mu.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineException
import com.musify.mu.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WakeWordService : Service() {

	companion object {
		private const val NOTIF_CHANNEL_ID = "wake_word_channel"
		private const val NOTIF_ID = 6102
		private const val ACTION_START = "com.musify.mu.voice.action.START"
		private const val ACTION_STOP = "com.musify.mu.voice.action.STOP"

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
	private var porcupineManager: PorcupineManager? = null
	private lateinit var headphoneDetector: HeadphoneDetector
	private lateinit var commandController: CommandController
	private lateinit var audioManager: AudioManager

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
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_STOP -> {
				stopWakeWord()
				stopSelf()
				return START_NOT_STICKY
			}
			else -> {
				startForeground(NOTIF_ID, buildNotification())
				startWakeWord()
				return START_STICKY
			}
		}
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onDestroy() {
		super.onDestroy()
		stopWakeWord()
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

	private fun startWakeWord() {
		if (porcupineManager != null) return

		if (!headphoneDetector.hasHeadsetMicrophone()) {
			android.widget.Toast.makeText(this, "Headset with microphone required", android.widget.Toast.LENGTH_LONG).show()
			return
		}

		try {
			// Ensure exclusive routing to headset mic during wake word listening
			headphoneDetector.forceAudioRoutingToHeadset()

			// Resolve resource name path for Porcupine
			val resName = "hey_musify"
			val resId = resources.getIdentifier(resName, "raw", packageName)
			if (resId == 0) throw PorcupineException("Wake word resource not found: $resName")
			val keywordPath = "res:raw/$resName"

			porcupineManager = PorcupineManager.Builder()
				.setAccessKey(accessKey)
				.setKeywordPath(keywordPath)
				.setSensitivity(0.6f)
				.build(applicationContext, PorcupineManagerCallback { _ ->
					// Wake word detected -> open 4s command window
					handleWakeWordDetected()
				})
			porcupineManager?.start()
			android.util.Log.d("WakeWordService", "Porcupine started")
		} catch (e: PorcupineException) {
			android.util.Log.e("WakeWordService", "Failed to start Porcupine", e)
		}
	}

	private var commandWindowJob: Job? = null

	private fun handleWakeWordDetected() {
		// Pause wake word while command window active
		try { porcupineManager?.stop() } catch (_: Exception) {}

		commandWindowJob?.cancel()
		commandWindowJob = serviceScope.launch(Dispatchers.Main) {
			try {
				// 3.5s window to capture the command via Vosk with restricted grammar
				commandController.listenForCommandWindow(3500L).collectLatest { text ->
					android.util.Log.d("WakeWordService", "Command heard: $text")
					processCommand(text)
				}
			} catch (e: Exception) {
				android.util.Log.w("WakeWordService", "Command window error: ${e.message}")
			} finally {
				// Resume wake word listening
				try { porcupineManager?.start() } catch (_: Exception) {}
			}
		}
	}

	private fun processCommand(text: String) {
		// Forward to global VoiceControlManager if exists
		try {
			val vcm = VoiceControlManager.getInstance(this)
			if (vcm != null) {
				try {
					vcm.processVoiceCommand(text)
				} catch (_: Exception) { }
			} else {
				android.util.Log.w("WakeWordService", "VoiceControlManager not available")
			}
		} catch (e: Exception) {
			android.util.Log.e("WakeWordService", "Failed to process command: ${e.message}")
		}
	}

	private fun stopWakeWord() {
		try { porcupineManager?.stop() } catch (_: Exception) {}
		try { porcupineManager?.delete() } catch (_: Exception) {}
		porcupineManager = null
	}
}