package com.musify.mu

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class MusifyApp : Application() {
    // Global application scope for background coroutines
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Initialize app components
        // For an offline music app, we don't need background scanning
        // Music will be scanned when user opens the library screen
    }
}
