package com.musify.mu

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.musify.mu.data.media.MediaScanWorker

class MusifyApp : Application() {
    // Global application scope for background coroutines
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        
        // Initialize WorkManager and schedule background media scanning
        // This ensures music discovery works on all devices without blocking the UI
        MediaScanWorker.schedule(this)
        
        // Here you can initialize any other global dependencies or SDKs if needed
        // Example: preload database, set up logging, crash reporting, etc.
    }
}
