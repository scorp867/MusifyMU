package com.musify.mu.util

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    val AUDIO_PERMISSIONS: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    val MIC_PERMISSION = Manifest.permission.RECORD_AUDIO

    fun hasAudioPermission(activity: Activity): Boolean {
        return AUDIO_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasMicPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(activity, MIC_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    fun requestAudioPermission(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(AUDIO_PERMISSIONS)
    }

    fun requestMicPermission(launcher: ActivityResultLauncher<String>) {
        launcher.launch(MIC_PERMISSION)
    }

    fun shouldShowAudioRationale(activity: Activity): Boolean {
        return AUDIO_PERMISSIONS.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    }

    fun shouldShowMicRationale(activity: Activity): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, MIC_PERMISSION)
    }
}