package com.musify.mu

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class MusifyApp : Application() {
    // Global application scope for background coroutines
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager manually for background tasks
        try {
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
            WorkManager.initialize(this, config)
        } catch (e: Exception) {
            // WorkManager might already be initialized, that's okay
            android.util.Log.d("MusifyApp", "WorkManager initialization: ${e.message}")
        }

        // Initialize app components
        android.util.Log.d("MusifyApp", "App initialized successfully")
    }
}
