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
                    .maxSizePercent(0.20) // Use 20% of available memory for better performance
                    .strongReferencesEnabled(true) // Keep strong references for better performance
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_artwork_cache"))
                    .maxSizeBytes(200 * 1024 * 1024) // 200MB disk cache for Paging 3
                    .build()
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build()
            }
            .respectCacheHeaders(false) // Ignore HTTP cache headers for local files
            .allowHardware(true) // Enable hardware acceleration for better performance
            .crossfade(true) // Enable smooth transitions
            .bitmapPoolPercentage(0.25) // Allocate 25% of memory to bitmap pool
            .placeholder(android.R.color.transparent) // Set transparent placeholder
            .error(com.musify.mu.R.drawable.ic_launcher_foreground) // Set error drawable
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
            .allowHardware(false)
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
