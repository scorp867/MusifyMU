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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

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

    // Sentinel for failed extraction so we don't retry every scroll
    private const val NONE_SENTINEL = "__NONE__"

    // Strong negative cache that does not evict during the session
    private val failedKeys: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Simple in-flight guard to avoid duplicate concurrent extractions
    private val loading = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Put a uri (or sentinel) directly into memory cache */
    fun cacheUri(mediaUri: String, artUri: String?) {
        inMemoryCache.put(mediaUri, artUri ?: NONE_SENTINEL)
        if (artUri.isNullOrBlank()) {
            failedKeys.add(mediaUri)
        } else {
            failedKeys.remove(mediaUri)
        }
        // Publish update to any observers
        flowFor(mediaUri).value = artUri
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val diskDir: File by lazy {
        File(appContext.cacheDir, "on_demand_artwork").apply { if (!exists()) mkdirs() }
    }

    // Per-mediaUri flows to notify UI when artwork becomes available
    private val uriFlows = ConcurrentHashMap<String, MutableStateFlow<String?>>()

    private fun normalizedCachedValue(key: String): String? {
        val cached = inMemoryCache.get(key)
        return if (cached == null || cached == NONE_SENTINEL) null else cached
    }

    private fun flowFor(mediaUri: String): MutableStateFlow<String?> {
        return uriFlows.getOrPut(mediaUri) {
            MutableStateFlow(normalizedCachedValue(mediaUri))
        }
    }

    fun artworkFlow(mediaUri: String): StateFlow<String?> = flowFor(mediaUri).asStateFlow()

    fun getCachedUri(mediaUri: String): String? = normalizedCachedValue(mediaUri)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Prefetch artwork for a list of media URIs concurrently (max parallel = 4).
     */
    fun prefetch(mediaIds: List<String>) {
        if (!::appContext.isInitialized) return
        val unique = mediaIds.distinct().take(200)
        unique.chunked(4).forEach { chunk ->
            scope.launch {
                chunk.map { id ->
                    launch {
                        if (!failedKeys.contains(id) && inMemoryCache.get(id) == null) {
                            loadArtwork(id)
                        }
                    }
                }.joinAll()
            }
        }
    }

    suspend fun loadArtwork(mediaUri: String?): String? {
        if (mediaUri.isNullOrBlank()) return null
        if (failedKeys.contains(mediaUri)) return null
        if (loading.putIfAbsent(mediaUri, true) == true) {
            // Another extraction in progress; return what we have
            return getCachedUri(mediaUri)
        }
        // quick memory lookup
        inMemoryCache.get(mediaUri)?.let { cached ->
            loading.remove(mediaUri)
            return if (cached == NONE_SENTINEL) null else cached
        }
        return withContext(Dispatchers.IO) {
            // disk lookup
            val cacheFile = File(diskDir, mediaUri.md5() + ".jpg")
            if (cacheFile.exists()) {
                val uriString = "file://${cacheFile.absolutePath}"
                inMemoryCache.put(mediaUri, uriString)
                // Notify observers immediately
                flowFor(mediaUri).value = uriString
                loading.remove(mediaUri)
                return@withContext uriString
            }

            // extract via retriever
            val retriever = MediaMetadataRetriever()
            try {
                when {
                    mediaUri.startsWith("content://") -> retriever.setDataSource(appContext, Uri.parse(mediaUri))
                    mediaUri.startsWith("/") -> retriever.setDataSource(mediaUri)
                    else -> {
                        inMemoryCache.put(mediaUri, NONE_SENTINEL)
                        return@withContext null
                    }
                }
                val artworkBytes = retriever.embeddedPicture ?: run {
                    // Negative cache so we don't thrash trying again
                    inMemoryCache.put(mediaUri, NONE_SENTINEL)
                    failedKeys.add(mediaUri)
                    flowFor(mediaUri).value = null
                    loading.remove(mediaUri)
                    return@withContext null
                }
                val bmp = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size) ?: return@withContext null
                val resized = resizeBitmap(bmp, MAX_BITMAP_EDGE)
                cacheFile.outputStream().use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                if (resized !== bmp) bmp.recycle()
                resized.recycle()
                val uriString = "file://${cacheFile.absolutePath}"
                inMemoryCache.put(mediaUri, uriString)
                flowFor(mediaUri).value = uriString
                loading.remove(mediaUri)
                return@withContext uriString
            } catch (e: Exception) {
                // Negative cache to avoid repeated attempts during session
                inMemoryCache.put(mediaUri, NONE_SENTINEL)
                failedKeys.add(mediaUri)
                flowFor(mediaUri).value = null
                loading.remove(mediaUri)
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Store artwork bytes coming directly from ExoPlayer metadata and cache them.
     * Returns file URI string.
     */
    suspend fun storeArtworkBytes(mediaUri: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext null
        val cacheFile = File(diskDir, mediaUri.md5() + ".jpg")
        try {
            cacheFile.outputStream().use { it.write(bytes) }
            val uri = "file://${cacheFile.absolutePath}"
            inMemoryCache.put(mediaUri, uri)
            flowFor(mediaUri).value = uri
            return@withContext uri
        } catch (e: Exception) {
            android.util.Log.w("OnDemandArtworkLoader", "Failed to store artwork bytes", e)
            null
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