package com.musify.mu

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class MusifyApp : Application() {
    // Global application scope for background coroutines
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Here you can initialize any global dependencies or SDKs if needed
        // Example: preload database, set up logging, crash reporting, etc.
    }
}
