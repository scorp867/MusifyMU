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
    private val player: Player
) {
    
    private val headphoneDetector = HeadphoneDetector(context)
    private val commandController = CommandController(context, headphoneDetector)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listeningJob: Job? = null
    private var isGymModeActive = false
    
    // Callback for UI updates
    var onGymModeChanged: ((Boolean) -> Unit)? = null
    
    fun isGymModeEnabled(): Boolean = isGymModeActive
    
    fun canEnableGymMode(): Boolean = headphoneDetector.isHeadphonesConnected.value
    
    fun toggleGymMode() {
        if (!canEnableGymMode()) {
            android.util.Log.w("VoiceControlManager", "Cannot enable gym mode: no headphones connected")
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
        
        listeningJob = scope.launch {
            android.util.Log.d("VoiceControlManager", "Starting voice listening...")
            
            // Show toast that voice listening is starting
            android.widget.Toast.makeText(
                context,
                "Voice listening started - say a command!",
                android.widget.Toast.LENGTH_LONG
            ).show()
            
            // Force audio routing to headset microphone immediately when gym mode starts
            val preferredAudioSource = headphoneDetector.getPreferredAudioSource()
            android.util.Log.d("VoiceControlManager", "Gym Mode: Forcing audio source to: $preferredAudioSource")
            
            while (isActive && isGymModeActive) {
                try {
                    // Check if headphones are still connected
                    if (!headphoneDetector.isHeadphonesConnected.value) {
                        android.util.Log.w("VoiceControlManager", "Headphones disconnected, stopping gym mode")
                        withContext(Dispatchers.Main) {
                            isGymModeActive = false
                            onGymModeChanged?.invoke(false)
                            
                            // Show toast that gym mode was disabled due to headphone disconnect
                            android.widget.Toast.makeText(
                                context,
                                "Gym Mode disabled - headphones disconnected",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        break
                    }
                    
                                         // Start listening for commands
                     try {
                         commandController.listen().collectLatest { recognizedText ->
                             if (isActive && isGymModeActive) {
                                 processVoiceCommand(recognizedText)
                             }
                         }
                     } catch (e: Exception) {
                         android.util.Log.w("VoiceControlManager", "Error in voice listening cycle", e)
                         delay(1000) // Wait before retrying
                     }
                     
                     // Small delay before next listening cycle
                     delay(500)
                    
                } catch (e: Exception) {
                    android.util.Log.e("VoiceControlManager", "Error in voice listening", e)
                    delay(1000) // Wait before retrying
                }
            }
        }
    }
    
    private fun stopVoiceListening() {
        listeningJob?.cancel()
        listeningJob = null
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
                    // This could be used to toggle gym mode via voice
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
        stopVoiceListening()
        // Restore default audio routing before cleanup
        if (isGymModeActive) {
            headphoneDetector.restoreDefaultAudioRouting()
        }
        scope.cancel()
        headphoneDetector.cleanup()
    }
    
    // Observe headphone connection changes
    fun observeHeadphoneConnection(): kotlinx.coroutines.flow.StateFlow<Boolean> {
        return headphoneDetector.isHeadphonesConnected
    }
}
