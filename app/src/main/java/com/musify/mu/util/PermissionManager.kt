package com.musify.mu.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Permission manager for handling runtime permissions
 * Handles different Android versions and media permissions
 */
object PermissionManager {
    
    const val REQUEST_CODE_MEDIA_PERMISSIONS = 1001
    
    // Required permissions based on Android version
    fun getRequiredMediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires READ_MEDIA_AUDIO
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            // Older versions use READ_EXTERNAL_STORAGE
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }
    
    fun checkMediaPermissions(context: Context): Boolean {
        val permissions = getRequiredMediaPermissions()
        android.util.Log.d("PermissionManager", "Checking permissions: ${permissions.toList()}")
        
        val results = permissions.map { permission ->
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            android.util.Log.d("PermissionManager", "Permission $permission: ${if (granted) "GRANTED" else "DENIED"}")
            permission to granted
        }
        
        val allGranted = results.all { it.second }
        android.util.Log.d("PermissionManager", "Overall permission status: ${if (allGranted) "ALL GRANTED" else "SOME DENIED"}")
        
        return allGranted
    }
    
    fun requestMediaPermissions(activity: Activity) {
        val permissions = getRequiredMediaPermissions()
        ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE_MEDIA_PERMISSIONS)
    }
    
    fun shouldShowRationale(activity: Activity): Boolean {
        return getRequiredMediaPermissions().any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }
    
    fun getDeniedPermissions(context: Context): List<String> {
        return getRequiredMediaPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * Composable for checking and requesting permissions
 */
@Composable
fun RequestPermissionsEffect(
    permissions: Array<String> = PermissionManager.getRequiredMediaPermissions(),
    onPermissionsResult: (granted: Boolean, deniedPermissions: List<String>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = PermissionManager.checkMediaPermissions(context)
                val denied = if (!granted) PermissionManager.getDeniedPermissions(context) else emptyList()
                onPermissionsResult(granted, denied)
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}