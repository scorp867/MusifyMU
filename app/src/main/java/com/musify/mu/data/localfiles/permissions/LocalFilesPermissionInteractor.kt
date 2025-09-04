package com.musify.mu.data.localfiles.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Permission interactor for local files access
 * Based on Spotify's LocalFilesPermissionInteractorImpl
 * 
 * Handles the transition from READ_EXTERNAL_STORAGE to READ_MEDIA_AUDIO
 * and manages permission state for the local files feature.
 */
class LocalFilesPermissionInteractor private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocalFilesPermission"
        
        @Volatile
        private var INSTANCE: LocalFilesPermissionInteractor? = null
        
        fun getInstance(context: Context): LocalFilesPermissionInteractor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalFilesPermissionInteractor(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    private val _permissionState = MutableStateFlow(checkCurrentPermissionState())
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()
    
    /**
     * Check if we have the required permissions to scan local files
     */
    fun hasPermissions(): Boolean {
        val required = getRequiredPermissions()
        return required.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get the permissions we need to request based on Android version
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses granular media permissions
            listOf(
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            // Older Android versions use READ_EXTERNAL_STORAGE
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }
    
    /**
     * Get permissions that are currently missing
     */
    fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check current permission state
     */
    private fun checkCurrentPermissionState(): PermissionState {
        val required = getRequiredPermissions()
        val granted = required.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        
        return when {
            granted.isEmpty() -> PermissionState.Denied
            granted.size == required.size -> PermissionState.Granted
            else -> PermissionState.Partial(granted, required - granted.toSet())
        }
    }
    
    /**
     * Update permission state (call this from permission result callbacks)
     */
    fun updatePermissionState() {
        val newState = checkCurrentPermissionState()
        val oldState = _permissionState.value
        
        if (newState != oldState) {
            _permissionState.value = newState
            Log.d(TAG, "Permission state changed: $oldState -> $newState")
        }
    }
    
    /**
     * Check if we can scan local files right now
     */
    fun canScanLocalFiles(): Boolean {
        return when (val state = _permissionState.value) {
            is PermissionState.Granted -> true
            is PermissionState.Partial -> {
                // Check if we have at least READ_MEDIA_AUDIO or READ_EXTERNAL_STORAGE
                state.granted.any { permission ->
                    permission == Manifest.permission.READ_MEDIA_AUDIO ||
                    permission == Manifest.permission.READ_EXTERNAL_STORAGE
                }
            }
            is PermissionState.Denied -> false
        }
    }
    
    /**
     * Get user-friendly permission explanation
     */
    fun getPermissionExplanation(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "This app needs access to your audio files to play your local music library."
        } else {
            "This app needs storage permission to access your local music files."
        }
    }
    
    /**
     * Get specific permission explanation for settings
     */
    fun getDetailedPermissionExplanation(): String {
        val missing = getMissingPermissions()
        return when {
            missing.isEmpty() -> "All required permissions are granted."
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                "Grant 'Music and audio' permission to access your local music library."
            }
            else -> {
                "Grant 'Storage' permission to access your local music files."
            }
        }
    }
    
    /**
     * Check if we should show rationale for permission request
     */
    fun shouldShowRationale(activity: android.app.Activity): Boolean {
        return getMissingPermissions().any { permission ->
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }
    
    /**
     * Log current permission status for debugging
     */
    fun logPermissionStatus() {
        val required = getRequiredPermissions()
        val status = required.map { permission ->
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            "$permission: ${if (granted) "GRANTED" else "DENIED"}"
        }
        
        Log.d(TAG, "Permission status: ${status.joinToString(", ")}")
        Log.d(TAG, "Can scan local files: ${canScanLocalFiles()}")
    }
}

/**
 * Represents the current state of local files permissions
 */
sealed class PermissionState {
    /**
     * All required permissions are granted
     */
    object Granted : PermissionState()
    
    /**
     * No permissions are granted
     */
    object Denied : PermissionState()
    
    /**
     * Some permissions are granted, some are not
     */
    data class Partial(
        val granted: List<String>,
        val denied: List<String>
    ) : PermissionState()
}

/**
 * Helper extension for checking specific permission types
 */
fun PermissionState.hasAudioPermission(): Boolean {
    return when (this) {
        is PermissionState.Granted -> true
        is PermissionState.Partial -> {
            granted.contains(Manifest.permission.READ_MEDIA_AUDIO) ||
            granted.contains(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        is PermissionState.Denied -> false
    }
}

