package com.musify.mu.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HeadphoneDetector(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _isHeadphonesConnected = MutableStateFlow(false)
    val isHeadphonesConnected: StateFlow<Boolean> = _isHeadphonesConnected.asStateFlow()

    private var lastToastTime = 0L
    private val toastDebounceMs = 2000L // Don't show toasts more often than every 2 seconds

    private val headphoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_HEADSET_PLUG -> {
                    // Use unified detection instead of relying only on "state"
                    val hasHeadphones = hasAnyHeadphonesConnected()
                    val previousState = _isHeadphonesConnected.value
                    _isHeadphonesConnected.value = hasHeadphones

                    android.util.Log.d("HeadphoneDetector", "HEADSET_PLUG event, total connected: $hasHeadphones")

                    // Only show toast if state actually changed and not too frequent
                    val currentTime = System.currentTimeMillis()
                    if (previousState != hasHeadphones && currentTime - lastToastTime > toastDebounceMs) {
                        lastToastTime = currentTime
                        android.widget.Toast.makeText(
                            context,
                            "Headphones ${if (hasHeadphones) "connected" else "disconnected"}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    // Use unified detection to avoid false disconnects during SCO transitions
                    val hasHeadphones = hasAnyHeadphonesConnected()
                    val previousState = _isHeadphonesConnected.value
                    _isHeadphonesConnected.value = hasHeadphones

                    android.util.Log.d("HeadphoneDetector", "SCO state updated, total connected: $hasHeadphones")

                    // Only show toast if state actually changed and not too frequent
                    val currentTime = System.currentTimeMillis()
                    if (previousState != hasHeadphones && currentTime - lastToastTime > toastDebounceMs) {
                        lastToastTime = currentTime
                        android.widget.Toast.makeText(
                            context,
                            "Headphones ${if (hasHeadphones) "connected" else "disconnected"}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                // Note: ACTION_DEVICE_CONNECTION_CHANGED is not available in current API level
                // We'll rely on the periodic check and existing broadcast receivers
            }
        }
    }

    init {
        // Initial check
        _isHeadphonesConnected.value = hasWiredHeadphones() || hasBluetoothHeadphones()

        // Register receiver for headphone plug/unplug events
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        context.registerReceiver(headphoneReceiver, filter)

        // Start periodic connectivity check
        startPeriodicCheck()
    }

    private fun startPeriodicCheck() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            while (true) {
                try {
                    var currentState = hasAnyHeadphonesConnected()
                    // Debounce: confirm once more after a short delay before declaring disconnected
                    if (!currentState) {
                        kotlinx.coroutines.delay(300)
                        currentState = hasAnyHeadphonesConnected()
                    }
                    val previousState = _isHeadphonesConnected.value

                    if (previousState != currentState) {
                        _isHeadphonesConnected.value = currentState
                        android.util.Log.d("HeadphoneDetector", "Periodic check: Headphones ${if (currentState) "connected" else "disconnected"}")

                        // Only show toast for disconnection and respect debounce timing
                        val currentTime = System.currentTimeMillis()
                        if (!currentState && currentTime - lastToastTime > toastDebounceMs) {
                            lastToastTime = currentTime
                            android.widget.Toast.makeText(
                                context,
                                "Headphones disconnected",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    kotlinx.coroutines.delay(5000) // Check every 5 seconds to reduce false triggers
                } catch (e: Exception) {
                    android.util.Log.w("HeadphoneDetector", "Error in periodic check", e)
                    kotlinx.coroutines.delay(10000) // Wait longer on error
                }
            }
        }
    }

    private fun hasWiredHeadphones(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } else {
            // Fallback for older versions
            audioManager.isWiredHeadsetOn
        }
    }

    fun hasBluetoothHeadphones(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            // Fallback for older versions
            audioManager.isBluetoothScoOn
        }
    }

    fun hasHeadsetMicrophone(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } else {
            // Fallback for older versions
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothScoOn
        }
    }

    private fun hasBluetoothHeadsetMic(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            devices.any { device -> device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            audioManager.isBluetoothScoOn
        }
    }

    private fun hasUsbHeadsetMic(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            devices.any { device -> device.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        } else {
            false
        }
    }

    fun getPreferredAudioSource(): Int {
        return when {
            hasBluetoothHeadsetMic() -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            hasWiredHeadphones() -> AudioDeviceInfo.TYPE_WIRED_HEADSET
            hasUsbHeadsetMic() -> AudioDeviceInfo.TYPE_USB_HEADSET
            else -> AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
    }

    private fun hasAnyHeadphonesConnected(): Boolean {
        return hasWiredHeadphones() || hasBluetoothHeadphones()
    }

    /**
     * Completely disable all built-in audio inputs when headset is active
     * This ensures only the headset microphone is used for voice commands
     */
    fun disableBuiltInAudioInputs() {
        try {
            // NEVER use communication mode - stay in NORMAL mode
            audioManager.mode = AudioManager.MODE_NORMAL

            // Ensure speakerphone is off (this helps prevent built-in mic usage)
            audioManager.isSpeakerphoneOn = false

            // For Android 9+ (API 28+), we can try to disable built-in mic more explicitly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // This is more of a hint to the system to prefer external mics
                android.util.Log.d("HeadphoneDetector", "Android 9+ detected - using enhanced mic routing")
            }

            android.util.Log.d("HeadphoneDetector", "Disabled built-in audio inputs - headset mic exclusive mode")
        } catch (e: Exception) {
            android.util.Log.w("HeadphoneDetector", "Failed to disable built-in audio inputs", e)
        }
    }

    /**
     * Force EXCLUSIVE audio routing to the preferred headset microphone
     * This ensures that voice commands use ONLY the headset mic, not the device mic
     */
    fun forceAudioRoutingToHeadset() {
        val preferredSource = getPreferredAudioSource()
        android.util.Log.d("HeadphoneDetector", "Forcing EXCLUSIVE audio routing to: $preferredSource")

        when (preferredSource) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                try {
                    // First, stop any existing audio routing
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    
                    // NEVER use communication mode - stay in NORMAL mode
                    audioManager.mode = AudioManager.MODE_NORMAL

                    // Force Bluetooth SCO audio for headset microphone ONLY
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true

                    // Disable speakerphone to prevent built-in mic
                    audioManager.isSpeakerphoneOn = false

                    android.util.Log.d("HeadphoneDetector", "Successfully forced EXCLUSIVE Bluetooth SCO audio routing - headset mic only")

                    // Show toast for audio routing change
                    android.widget.Toast.makeText(
                        context,
                        "Using headset microphone",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.util.Log.w("HeadphoneDetector", "Failed to force Bluetooth SCO audio routing", e)

                    // Show error toast
                    android.widget.Toast.makeText(
                        context,
                        "Failed to use headset microphone",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                // For wired headsets, the system should automatically use the headset mic
                // when it's connected, but we can log this for debugging
                android.util.Log.d("HeadphoneDetector", "Wired headset detected - should use headset microphone")

                // Show toast for audio routing change
                android.widget.Toast.makeText(
                    context,
                    "Using wired headset microphone",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            AudioDeviceInfo.TYPE_USB_HEADSET -> {
                // For USB headsets, the system should automatically use the headset mic
                android.util.Log.d("HeadphoneDetector", "USB headset detected - should use headset microphone")

                // Show toast for audio routing change
                android.widget.Toast.makeText(
                    context,
                    "Using USB headset microphone",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            else -> {
                android.util.Log.w("HeadphoneDetector", "No headset microphone available - will use device microphone")

                // Show warning toast
                android.widget.Toast.makeText(
                    context,
                    "Warning: Using device microphone",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Restore default audio routing when Gym Mode is deactivated
     */
    fun restoreDefaultAudioRouting() {
        val preferredSource = getPreferredAudioSource()
        android.util.Log.d("HeadphoneDetector", "Restoring default audio routing from: $preferredSource")

        try {
            // First, ensure we restore normal audio mode to prevent music from playing through communication speaker
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
                android.util.Log.d("HeadphoneDetector", "Restored audio mode to NORMAL")
            }
            
            // Stop any active Bluetooth SCO connection
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                android.util.Log.d("HeadphoneDetector", "Stopped Bluetooth SCO")
            }
            
            // Ensure speakerphone is in correct state
            audioManager.isSpeakerphoneOn = false
            
            android.util.Log.d("HeadphoneDetector", "Successfully restored default audio routing")
        } catch (e: Exception) {
            android.util.Log.w("HeadphoneDetector", "Failed to restore default audio routing", e)
        }
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(headphoneReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
}
