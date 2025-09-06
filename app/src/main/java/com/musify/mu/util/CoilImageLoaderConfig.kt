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
                    .maxSizePercent(0.25) // Increased to 25% for better performance
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_artwork_cache"))
                    .maxSizeBytes(200 * 1024 * 1024) // Increased to 200MB for larger cache
                    .build()
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS) // Faster timeout for better UX
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(true) // Disable hardware acceleration to prevent artwork flickering
            .crossfade(false)
            .networkObserverEnabled(false) // Disable network observer for local files
            // Performance optimizations
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565) // Use RGB_565 for better memory efficiency
            .allowRgb565(true)
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
                    .directory(context.cacheDir.resolve("coil_artwork_cache"))
                    .maxSizeBytes(50 * 1024 * 1024) // 50MB disk cache
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
