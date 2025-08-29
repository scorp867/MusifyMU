package com.musify.mu.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll

/**
 * Lightweight, in-memory/disk on-demand artwork loader.
 *
 * Behaviour:
 * 1. First checks in-memory cache (LRU) for previously extracted art.
 * 2. If missing, tries a small on-disk session cache (app cache dir/on_demand_artwork).
 * 3. As last resort, extracts embedded image using [MediaMetadataRetriever].
 *
 * All heavy work happens on Dispatchers.IO.
 */
object OnDemandArtworkLoader {
    private const val MAX_MEMORY_ENTRIES = 128 // should be enough for a session
    private const val MAX_BITMAP_EDGE = 512 // px, reduce memory pressure

    private lateinit var appContext: Context

    private val inMemoryCache: LruCache<String, String> = object : LruCache<String, String>(MAX_MEMORY_ENTRIES) {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val diskDir: File by lazy {
        File(appContext.cacheDir, "on_demand_artwork").apply { if (!exists()) mkdirs() }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Prefetch artwork for a list of media URIs concurrently (max parallel = 4).
     */
    fun prefetch(mediaIds: List<String>) {
        if (!::appContext.isInitialized) return
        val unique = mediaIds.distinct().take(200) // limit to avoid massive work
        unique.chunked(4).forEach { chunk ->
            scope.launch {
                chunk.map { id ->
                    launch { loadArtwork(id) }
                }.joinAll()
            }
        }
    }

    suspend fun loadArtwork(mediaUri: String?): String? {
        if (mediaUri.isNullOrBlank()) return null
        // quick memory lookup
        inMemoryCache.get(mediaUri)?.let { return it }
        return withContext(Dispatchers.IO) {
            // disk lookup
            val cacheFile = File(diskDir, mediaUri.md5() + ".jpg")
            if (cacheFile.exists()) {
                val uriString = "file://${cacheFile.absolutePath}"
                inMemoryCache.put(mediaUri, uriString)
                return@withContext uriString
            }

            // extract via retriever
            val retriever = MediaMetadataRetriever()
            try {
                when {
                    mediaUri.startsWith("content://") -> retriever.setDataSource(appContext, Uri.parse(mediaUri))
                    mediaUri.startsWith("/") -> retriever.setDataSource(mediaUri)
                    else -> return@withContext null
                }
                val artworkBytes = retriever.embeddedPicture ?: return@withContext null
                val bmp = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size) ?: return@withContext null
                val resized = resizeBitmap(bmp, MAX_BITMAP_EDGE)
                cacheFile.outputStream().use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                if (resized !== bmp) bmp.recycle()
                resized.recycle()
                val uriString = "file://${cacheFile.absolutePath}"
                inMemoryCache.put(mediaUri, uriString)
                return@withContext uriString
            } catch (e: Exception) {
                android.util.Log.w("OnDemandArtworkLoader", "Failed to extract artwork", e)
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    private fun resizeBitmap(src: Bitmap, max: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= max && h <= max) return src
        val ratio = kotlin.math.min(max.toFloat() / w, max.toFloat() / h)
        val nw = (w * ratio).toInt()
        val nh = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun String.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}