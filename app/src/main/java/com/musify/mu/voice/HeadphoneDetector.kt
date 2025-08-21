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
    
    private val headphoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    val isConnected = state == 1
                    val hasHeadphones = isConnected || hasBluetoothHeadphones()
                    _isHeadphonesConnected.value = hasHeadphones
                    android.util.Log.d("HeadphoneDetector", "Wired headset ${if (isConnected) "connected" else "disconnected"}, total: $hasHeadphones")
                    
                    // Show toast for immediate connection change
                    android.widget.Toast.makeText(
                        context,
                        "Wired headset ${if (isConnected) "connected" else "disconnected"}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    // Bluetooth SCO (Synchronous Connection-Oriented) audio state changed
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    val isConnected = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
                    val hasHeadphones = isConnected || hasWiredHeadphones()
                    _isHeadphonesConnected.value = hasHeadphones
                    android.util.Log.d("HeadphoneDetector", "Bluetooth SCO ${if (isConnected) "connected" else "disconnected"}, total: $hasHeadphones")
                    
                    // Show toast for immediate connection change
                    android.widget.Toast.makeText(
                        context,
                        "Bluetooth headset ${if (isConnected) "connected" else "disconnected"}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
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
                    val currentState = hasWiredHeadphones() || hasBluetoothHeadphones()
                    if (_isHeadphonesConnected.value != currentState) {
                        _isHeadphonesConnected.value = currentState
                        android.util.Log.d("HeadphoneDetector", "Periodic check: Headphones ${if (currentState) "connected" else "disconnected"}")
                        
                        // Only show toast for disconnection to avoid spam
                        if (!currentState) {
                            android.widget.Toast.makeText(
                                context,
                                "Headphones disconnected",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    kotlinx.coroutines.delay(3000) // Check every 3 seconds to reduce spam
                } catch (e: Exception) {
                    android.util.Log.w("HeadphoneDetector", "Error in periodic check", e)
                    kotlinx.coroutines.delay(5000) // Wait longer on error
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
    
    fun getPreferredAudioSource(): Int {
        return when {
            hasBluetoothHeadphones() -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            hasWiredHeadphones() -> AudioDeviceInfo.TYPE_WIRED_HEADSET
            else -> AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
    }
    
    /**
     * Force audio routing to the preferred headset microphone
     * This ensures that voice commands use the headset mic, not the device mic
     */
    fun forceAudioRoutingToHeadset() {
        val preferredSource = getPreferredAudioSource()
        android.util.Log.d("HeadphoneDetector", "Forcing audio routing to: $preferredSource")
        
        when (preferredSource) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                try {
                    // Force Bluetooth SCO audio for headset microphone
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    android.util.Log.d("HeadphoneDetector", "Successfully forced Bluetooth SCO audio routing")
                    
                    // Show toast for audio routing change
                    android.widget.Toast.makeText(
                        context,
                        "Using Bluetooth headset microphone",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.util.Log.w("HeadphoneDetector", "Failed to force Bluetooth SCO audio routing", e)
                    
                    // Show error toast
                    android.widget.Toast.makeText(
                        context,
                        "Failed to use Bluetooth microphone",
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
        
        when (preferredSource) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                try {
                    // Stop Bluetooth SCO audio and restore default routing
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    android.util.Log.d("HeadphoneDetector", "Successfully restored default audio routing")
                } catch (e: Exception) {
                    android.util.Log.w("HeadphoneDetector", "Failed to restore default audio routing", e)
                }
            }
            else -> {
                // For wired/USB headsets, no special cleanup needed
                android.util.Log.d("HeadphoneDetector", "Default audio routing restored")
            }
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
