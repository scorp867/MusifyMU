package com.musify.mu.util

import android.app.ActivityManager
import android.content.Context

/**
 * Utility functions for device capabilities and memory management
 */

/**
 * Checks if the current device is considered low memory
 * @param context Android context
 * @return true if device has low memory, false otherwise
 */
fun isLowMemoryDevice(context: Context): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return activityManager.isLowRamDevice
}

