package com.musify.mu

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.util.CoilConfigModule
import com.musify.mu.util.SpotifyStyleArtworkLoader
import com.musify.mu.util.isLowMemoryDevice

@HiltAndroidApp
class MusifyApp : Application(), ImageLoaderFactory {
    // Global application scope for background coroutines
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Initialize Spotify-style artwork loader with application context
        SpotifyStyleArtworkLoader.initialize(this)
        
        // Note: QueueManager will be set by MediaModule when it's created
        // Trying to inject here is too early in the lifecycle
        
        // Clear Coil's memory cache on app start to prevent flickering from stale cache
        newImageLoader().memoryCache?.clear()

        // Note: Data manager will be initialized by LibraryScreen when permissions are granted
        // This avoids conflicts and ensures proper initialization timing
        android.util.Log.d("MusifyApp", "Musify app initialized - data manager will be initialized by LibraryScreen")

        android.util.Log.d("MusifyApp", "Simple Musify app initialized successfully")

        // Track app foreground/background state and save state when going to background
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                AppForegroundState.isInForeground = true
                android.util.Log.d("MusifyApp", "App moved to foreground")
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                AppForegroundState.isInForeground = false
                android.util.Log.d("MusifyApp", "App moved to background")
            }
        })
    }

    /**
     * Create optimized Coil ImageLoader for artwork caching
     */
    override fun newImageLoader(): ImageLoader {
        return if (isLowMemoryDevice(this)) {
            android.util.Log.d("MusifyApp", "Using low-memory ImageLoader configuration")
            CoilConfigModule.provideImageLoader(this)
        } else {
            android.util.Log.d("MusifyApp", "Using optimized ImageLoader configuration")
            CoilConfigModule.provideImageLoader(this)
        }
    }
}

object AppForegroundState {
    @Volatile
    var isInForeground: Boolean = false
}
