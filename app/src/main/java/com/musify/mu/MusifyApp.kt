package com.musify.mu

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.musify.mu.data.media.SimpleBackgroundDataManager
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.util.CoilImageLoaderConfig
import com.musify.mu.util.isLowMemoryDevice

class MusifyApp : Application(), ImageLoaderFactory {
    // Global application scope for background coroutines
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Initialize on-demand artwork loader with application context
        com.musify.mu.util.OnDemandArtworkLoader.init(this)
        
        // Clear Coil's memory cache on app start to prevent flickering from stale cache
        newImageLoader().memoryCache?.clear()

        // Note: Data manager will be initialized by LibraryScreen when permissions are granted
        // This avoids conflicts and ensures proper initialization timing
        android.util.Log.d("MusifyApp", "Musify app initialized - data manager will be initialized by LibraryScreen")

        android.util.Log.d("MusifyApp", "Simple Musify app initialized successfully")

        // Track app foreground/background state
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
        return if (isLowMemoryDevice()) {
            android.util.Log.d("MusifyApp", "Using low-memory ImageLoader configuration")
            CoilImageLoaderConfig.createLowMemoryImageLoader(this)
        } else {
            android.util.Log.d("MusifyApp", "Using optimized ImageLoader configuration")
            CoilImageLoaderConfig.createOptimizedImageLoader(this)
        }
    }
}

object AppForegroundState {
    @Volatile
    var isInForeground: Boolean = false
}
