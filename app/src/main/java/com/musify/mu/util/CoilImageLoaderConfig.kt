package com.musify.mu.util

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit


/**
 * Optimized Coil ImageLoader configuration for music artwork
 * with aggressive caching and performance optimizations.
 */
object CoilImageLoaderConfig {
    
    fun createOptimizedImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.20) // Increased to 20% for better performance
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.getExternalFilesDir("coil_artwork_cache") 
                        ?: context.cacheDir.resolve("coil_artwork_cache"))
                    .maxSizeBytes(300 * 1024 * 1024) // Increased to 300MB
                    .build()
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS) // Faster timeout
                    .readTimeout(12, TimeUnit.SECONDS)
                    .writeTimeout(12, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(false) // Disable hardware acceleration to prevent artwork flickering
            .crossfade(false)
            .networkObserverEnabled(false) // Disable network observer for local files
            .apply {
                if (android.util.Log.isLoggable("CoilImageLoader", android.util.Log.DEBUG)) {
                    logger(DebugLogger())
                }
            }
            .build()
    }
    
    /**
     * Create a memory-optimized ImageLoader for low-memory devices
     */
    fun createLowMemoryImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.08) // Use only 8% of available memory
                    .strongReferencesEnabled(false) // Use weak references to save memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.getExternalFilesDir("coil_artwork_cache") 
                        ?: context.cacheDir.resolve("coil_artwork_cache"))
                    .maxSizeBytes(100 * 1024 * 1024) // Increased to 100MB disk cache
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(false) // Hardware acceleration disabled to prevent flickering
            .crossfade(false) // Disable crossfade to save memory
            .build()
    }
}

/**
 * Extension function to determine if device is low on memory
 */
fun Context.isLowMemoryDevice(): Boolean {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    return activityManager.isLowRamDevice
}
