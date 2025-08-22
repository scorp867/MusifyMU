package com.musify.mu.voice

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceControlManager(
    private val context: Context,
    private var player: Player
) {

    private val headphoneDetector = HeadphoneDetector(context)
    private val commandController = CommandController(context, headphoneDetector)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listeningJob: Job? = null
    private var headsetMonitorJob: Job? = null
    private var isGymModeActive = false

    // Wake-word detector (Precise placeholder using Vosk grammar)
    private val wakeWordDetector = PreciseWakeWordDetector(context, headphoneDetector)

    // Callback for UI updates
    var onGymModeChanged: ((Boolean) -> Unit)? = null

    companion object {
        @Volatile
        private var instance: VoiceControlManager? = null

        fun getInstance(context: Context): VoiceControlManager? = instance

        fun createInstance(context: Context, player: Player): VoiceControlManager {
            return instance ?: synchronized(this) {
                instance ?: VoiceControlManager(context, player).also { instance = it }
            }
        }
    }
    
    fun isGymModeEnabled(): Boolean = isGymModeActive

    fun canEnableGymMode(): Boolean = headphoneDetector.isHeadphonesConnected.value && headphoneDetector.hasHeadsetMicrophone()

    // Getter for headphone detector
    fun getHeadphoneDetector(): HeadphoneDetector = headphoneDetector

    // Method to update player reference
    fun updatePlayer(newPlayer: Player) {
        if (player != newPlayer) {
            android.util.Log.d("VoiceControlManager", "Updating player reference")
            player = newPlayer
        }
    }
    
    fun toggleGymMode() {
        if (!canEnableGymMode()) {
            android.util.Log.w("VoiceControlManager", "Cannot enable gym mode: no headphones connected")
            android.widget.Toast.makeText(
                context,
                "Headphones required for voice commands",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        // Additional check: ensure headset has a microphone
        if (!headphoneDetector.hasHeadsetMicrophone()) {
            android.util.Log.w("VoiceControlManager", "Cannot enable gym mode: headphones have no microphone")
            android.widget.Toast.makeText(
                context,
                "Headset with microphone required for voice commands",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        
        val newState = !isGymModeActive
        isGymModeActive = newState
        
        if (newState) {
            // Force audio routing to headset microphone immediately
            headphoneDetector.forceAudioRoutingToHeadset()
            startVoiceListening()
        } else {
            stopVoiceListening()
            // Restore default audio routing when gym mode is disabled
            headphoneDetector.restoreDefaultAudioRouting()
        }
        
        onGymModeChanged?.invoke(newState)
        android.util.Log.d("VoiceControlManager", "Gym mode ${if (newState) "enabled" else "disabled"}")
        
        // Show toast message for gym mode change
        android.widget.Toast.makeText(
            context,
            "Gym Mode ${if (newState) "ON" else "OFF"}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    private fun startVoiceListening() {
        if (listeningJob?.isActive == true) return

        // Monitor headset connectivity and auto-disable
        headsetMonitorJob?.cancel()
        headsetMonitorJob = scope.launch {
            headphoneDetector.isHeadphonesConnected.collectLatest { connected ->
                if (!connected && isGymModeActive) {
                    android.util.Log.w("VoiceControlManager", "Headphones disconnected, stopping gym mode")
                    isGymModeActive = false
                    onGymModeChanged?.invoke(false)
                    stopVoiceListening()
                    headphoneDetector.restoreDefaultAudioRouting()
                    android.widget.Toast.makeText(
                        context,
                        "Gym Mode disabled - headphones disconnected",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        
        listeningJob = scope.launch {
            android.util.Log.d("VoiceControlManager", "Starting wake-word listener...")

            // Hook wake-word callback
            wakeWordDetector.onWakeWordDetected = {
                scope.launch {
                    android.util.Log.d("VoiceControlManager", "Wake word detected - opening command window")
                    // Run a 4-second command window
                    runCommandWindow(windowMs = 4000L)
                    // Resume wake-word detector if still active
                    if (isGymModeActive) {
                        try { wakeWordDetector.start() } catch (_: Exception) { }
                    }
                }
            }

            // Start continuous wake-word listening
            wakeWordDetector.start()
        }
    }

    private suspend fun runCommandWindow(windowMs: Long) {
        // Vosk needs exclusive mic: ensure wake detector is stopped
        try { wakeWordDetector.stop() } catch (_: Exception) { }
        withContext(Dispatchers.IO) {
            try {
                commandController.listenForCommandWindow(windowMs).collectLatest { recognizedText ->
                    if (isGymModeActive) {
                        android.util.Log.d("VoiceControlManager", "Voice command recognized: '$recognizedText'")
                        withContext(Dispatchers.Main) { processVoiceCommand(recognizedText) }
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context,
                                "Voice: '$recognizedText'",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("VoiceControlManager", "Command window ended with error: ${e.localizedMessage}")
            }
        }
    }
    
    private fun stopVoiceListening() {
        listeningJob?.cancel()
        listeningJob = null
        headsetMonitorJob?.cancel()
        headsetMonitorJob = null
        try { wakeWordDetector.stop() } catch (_: Exception) { }
        android.util.Log.d("VoiceControlManager", "Voice listening stopped")
        
        // Show toast that voice listening has stopped
        android.widget.Toast.makeText(
            context,
            "Voice listening stopped",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    private fun processVoiceCommand(recognizedText: String) {
        val command = commandController.interpretCommand(recognizedText)
        
        command?.let { cmd ->
            android.util.Log.d("VoiceControlManager", "Processing command: $cmd from '$recognizedText'")
            
            // Show toast message for recognized command
            android.widget.Toast.makeText(
                context,
                "Voice command: $cmd",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            
            when (cmd) {
                CommandController.Command.PLAY -> {
                    if (!player.isPlaying) {
                        player.play()
                        android.util.Log.d("VoiceControlManager", "Voice command: Play")
                    }
                }
                
                CommandController.Command.PAUSE -> {
                    if (player.isPlaying) {
                        player.pause()
                        android.util.Log.d("VoiceControlManager", "Voice command: Pause")
                    }
                }
                
                CommandController.Command.NEXT -> {
                    if (player.hasNextMediaItem()) {
                        player.seekToNext()
                        android.util.Log.d("VoiceControlManager", "Voice command: Next")
                    }
                }
                
                CommandController.Command.PREV -> {
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPrevious()
                        android.util.Log.d("VoiceControlManager", "Voice command: Previous")
                    }
                }
                
                CommandController.Command.SHUFFLE_ON -> {
                    player.shuffleModeEnabled = true
                    android.util.Log.d("VoiceControlManager", "Voice command: Shuffle On")
                }
                
                CommandController.Command.SHUFFLE_OFF -> {
                    player.shuffleModeEnabled = false
                    android.util.Log.d("VoiceControlManager", "Voice command: Shuffle Off")
                }
                
                CommandController.Command.REPEAT_ONE -> {
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    android.util.Log.d("VoiceControlManager", "Voice command: Repeat One")
                }
                
                CommandController.Command.REPEAT_ALL -> {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    android.util.Log.d("VoiceControlManager", "Voice command: Repeat All")
                }
                
                CommandController.Command.REPEAT_OFF -> {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    android.util.Log.d("VoiceControlManager", "Voice command: Repeat Off")
                }
                
                CommandController.Command.VOLUME_UP -> {
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val newVolume = (currentVolume + 1).coerceAtMost(maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    android.util.Log.d("VoiceControlManager", "Voice command: Volume Up")
                }
                
                CommandController.Command.VOLUME_DOWN -> {
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val newVolume = (currentVolume - 1).coerceAtLeast(0)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    android.util.Log.d("VoiceControlManager", "Voice command: Volume Down")
                }
                
                CommandController.Command.MUTE -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    android.util.Log.d("VoiceControlManager", "Voice command: Mute")
                }
                
                CommandController.Command.TOGGLE_GYM_MODE -> {
                    android.util.Log.d("VoiceControlManager", "Voice command: Toggle Gym Mode")
                }
            }
        } ?: run {
            // Show toast for unrecognized command
            android.widget.Toast.makeText(
                context,
                "Unrecognized: '$recognizedText'",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    fun cleanup() {
        android.util.Log.d("VoiceControlManager", "Cleaning up VoiceControlManager")
        stopVoiceListening()
        // Restore default audio routing before cleanup
        if (isGymModeActive) {
            headphoneDetector.restoreDefaultAudioRouting()
        }
        scope.cancel()
        headphoneDetector.cleanup()
        wakeWordDetector.cleanup()
        instance = null // Clear singleton instance
    }

    fun cleanupOnAppDestroy() {
        android.util.Log.d("VoiceControlManager", "App destroying - cleaning up")
        cleanup()
    }
    
    // Observe headphone connection changes
    fun observeHeadphoneConnection(): kotlinx.coroutines.flow.StateFlow<Boolean> {
        return headphoneDetector.isHeadphonesConnected
    }
}
