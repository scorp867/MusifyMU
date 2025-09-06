package com.musify.mu.data.repo

import android.content.Context
import android.provider.MediaStore
import android.content.ContentUris
import android.net.Uri
import android.util.Log
import com.musify.mu.lyrics.LrcLine
import com.musify.mu.lyrics.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A store that manages lyrics loading and caching
 * Ensures lyrics are loaded only once per track
 */
@Singleton
class LyricsStateStore @Inject constructor(
    private val context: Context,
    private val lyricsRepo: LyricsRepository
) {
    
    data class LyricsState(
        val mediaId: String,
        val text: String? = null,
        val lrcLines: List<LrcLine> = emptyList(),
        val isLrc: Boolean = false,
        val isLoading: Boolean = false,
        val hasError: Boolean = false
    )
    
    private val _currentLyrics = MutableStateFlow<LyricsState?>(null)
    val currentLyrics: StateFlow<LyricsState?> = _currentLyrics.asStateFlow()
    
    private val lyricsCache = mutableMapOf<String, LyricsState>()
    
    /**
     * Load lyrics for a given media ID
     * This method checks cache first and only loads from storage if not cached
     */
    suspend fun loadLyrics(mediaId: String) = withContext(Dispatchers.IO) {
        try {
            Log.d("LyricsStateStore", "Loading lyrics for mediaId: $mediaId")
            
            // Check if already loading or loaded for this track
            val currentState = _currentLyrics.value
            if (currentState?.mediaId == mediaId) {
                if (currentState.isLoading) {
                    Log.d("LyricsStateStore", "Already loading lyrics for $mediaId, skipping")
                    return@withContext
                }
                if (!currentState.hasError && (currentState.text != null || currentState.lrcLines.isNotEmpty())) {
                    Log.d("LyricsStateStore", "Lyrics already loaded for current track")
                    return@withContext
                }
            }
            
            // Check cache
            lyricsCache[mediaId]?.let { cachedState ->
                if (!cachedState.hasError && (cachedState.text != null || cachedState.lrcLines.isNotEmpty())) {
                    Log.d("LyricsStateStore", "Found valid lyrics in cache for $mediaId")
                    _currentLyrics.value = cachedState
                    return@withContext
                }
            }
            
            // Set loading state
            _currentLyrics.value = LyricsState(mediaId = mediaId, isLoading = true)
            
            // Load from repository
            val lyricsMap = lyricsRepo.get(mediaId)
            
            val state = if (lyricsMap != null) {
                Log.d("LyricsStateStore", "Found lyrics map: ${lyricsMap.type}")
                
                if (lyricsMap.type == "lrc") {
                    try {
                        val uri = Uri.parse(lyricsMap.uriOrText)
                        val text = when (uri.scheme) {
                            "content" -> context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                BufferedReader(InputStreamReader(inputStream)).readText()
                            }
                            "file" -> uri.path?.let { path ->
                                try { File(path).readText() } catch (_: Exception) { null }
                            }
                            else -> {
                                // Try as file path as a fallback
                                try { File(lyricsMap.uriOrText).readText() } catch (_: Exception) { null }
                            }
                        }
                        
                        if (text != null) {
                            // Use enhanced detection to determine if this is actually LRC format
                            val isActuallyLrc = LrcParser.isLrcFormat(text)
                            val lrcLines = if (isActuallyLrc) LrcParser.parse(text) else emptyList()
                            
                            Log.d("LyricsStateStore", "Parsed ${lrcLines.size} LRC lines, isLrc: $isActuallyLrc")
                            
                            LyricsState(
                                mediaId = mediaId,
                                text = text,
                                lrcLines = lrcLines,
                                isLrc = isActuallyLrc,
                                isLoading = false
                            )
                        } else {
                            Log.e("LyricsStateStore", "Failed to read LRC file")
                            LyricsState(
                                mediaId = mediaId,
                                text = "Error reading LRC file",
                                isLoading = false,
                                hasError = true
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("LyricsStateStore", "Error loading LRC file", e)
                        LyricsState(
                            mediaId = mediaId,
                            text = "Error loading LRC file: ${e.message}",
                            isLoading = false,
                            hasError = true
                        )
                    }
                } else {
                    // Plain text lyrics
                    LyricsState(
                        mediaId = mediaId,
                        text = lyricsMap.uriOrText,
                        isLrc = false,
                        isLoading = false
                    )
                }
            } else {
                // Try auto-discovery of sidecar lyrics next to the audio file
                val discovered = tryAutoDiscoverLyrics(mediaId)
                if (discovered != null) {
                    Log.d("LyricsStateStore", "Auto-discovered lyrics for $mediaId")
                    discovered
                } else {
                    Log.d("LyricsStateStore", "No lyrics found for $mediaId")
                    LyricsState(
                        mediaId = mediaId,
                        text = null,
                        isLoading = false
                    )
                }
            }
            
            // Cache the result
            lyricsCache[mediaId] = state
            _currentLyrics.value = state
            
        } catch (e: Exception) {
            Log.e("LyricsStateStore", "Exception loading lyrics", e)
            val errorState = LyricsState(
                mediaId = mediaId,
                text = "Error: ${e.message}",
                isLoading = false,
                hasError = true
            )
            _currentLyrics.value = errorState
        }
    }
    
    /**
     * Attempts to find a sidecar .lrc or .txt file for the given audio mediaId in the same folder.
     * Uses MediaStore so it works without raw file paths on Android 10+.
     */
    private fun tryAutoDiscoverLyrics(mediaId: String): LyricsState? {
        return try {
            val audioUri = Uri.parse(mediaId)
            val audioProjection = arrayOf(
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.RELATIVE_PATH
            )
            val audioCursor = context.contentResolver.query(
                audioUri,
                audioProjection,
                null,
                null,
                null
            )
            audioCursor?.use { c ->
                if (!c.moveToFirst()) return null
                val displayName = c.getString(0) ?: return null
                val title = c.getString(1) ?: displayName.substringBeforeLast('.')
                val relativePath = c.getString(2) ?: return null
                val base = displayName.substringBeforeLast('.')
                val normalizedBase = base
                    .replace("(Lyrics)", "", ignoreCase = true)
                    .replace("[Lyrics]", "", ignoreCase = true)
                    .replace("(Official Video)", "", ignoreCase = true)
                    .replace("(Audio)", "", ignoreCase = true)
                    .trim()
                val candidates = listOf(
                    "$base.lrc",
                    "$normalizedBase.lrc",
                    "$title.lrc",
                    "$base.txt",
                    "$normalizedBase.txt",
                    "$title.txt"
                ).distinct().take(6)
                
                val filesUri = MediaStore.Files.getContentUri("external")
                val selection = buildString {
                    append("${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ")
                    append('(')
                    candidates.forEachIndexed { idx, _ ->
                        if (idx > 0) append(" OR ")
                        append("${MediaStore.Files.FileColumns.DISPLAY_NAME}=?")
                    }
                    append(')')
                }
                val args = arrayOf(relativePath) + candidates.toTypedArray()
                val fileProjection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME
                )
                context.contentResolver.query(
                    filesUri,
                    fileProjection,
                    selection,
                    args,
                    null
                )?.use { fc ->
                    if (fc.moveToFirst()) {
                        val id = fc.getLong(0)
                        val name = fc.getString(1)
                        Log.d("LyricsStateStore", "Found sidecar lyrics file in MediaStore: $name")
                        val lrcContentUri = ContentUris.withAppendedId(filesUri, id)
                        val text = context.contentResolver.openInputStream(lrcContentUri)?.use { ins ->
                            BufferedReader(InputStreamReader(ins)).readText()
                        }
                        if (!text.isNullOrBlank()) {
                            // Persist mapping so we do not need to re-discover next time
                            // Fire-and-forget in IO
                            kotlin.concurrent.thread(name = "persist-lyrics-map") {
                                try { kotlinx.coroutines.runBlocking { lyricsRepo.attachLrc(mediaId, lrcContentUri) } } catch (_: Exception) {}
                            }
                            
                            val isActuallyLrc = LrcParser.isLrcFormat(text)
                            val lrcLines = if (isActuallyLrc) LrcParser.parse(text) else emptyList()
                            
                            return LyricsState(
                                mediaId = mediaId,
                                text = text,
                                lrcLines = lrcLines,
                                isLrc = isActuallyLrc,
                                isLoading = false
                            )
                        }
                    } else {
                        // Fallback: scan by prefix match within the same folder for any .lrc
                        val likeSelection = "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
                        val likeArgs = arrayOf(relativePath, "$normalizedBase%.lrc")
                        context.contentResolver.query(
                            filesUri,
                            fileProjection,
                            likeSelection,
                            likeArgs,
                            null
                        )?.use { lc ->
                            if (lc.moveToFirst()) {
                                val id = lc.getLong(0)
                                val lrcContentUri = ContentUris.withAppendedId(filesUri, id)
                                val text = context.contentResolver.openInputStream(lrcContentUri)?.use { ins ->
                                    BufferedReader(InputStreamReader(ins)).readText()
                                }
                                if (!text.isNullOrBlank()) {
                                    kotlin.concurrent.thread(name = "persist-lyrics-map") {
                                        try { kotlinx.coroutines.runBlocking { lyricsRepo.attachLrc(mediaId, lrcContentUri) } } catch (_: Exception) {}
                                    }
                                    
                                    val isActuallyLrc = LrcParser.isLrcFormat(text)
                                    val lrcLines = if (isActuallyLrc) LrcParser.parse(text) else emptyList()
                                    
                                    return LyricsState(
                                        mediaId = mediaId,
                                        text = text,
                                        lrcLines = lrcLines,
                                        isLrc = isActuallyLrc,
                                        isLoading = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w("LyricsStateStore", "Auto-discovery failed: ${e.message}")
            null
        }
    }
    
    /**
     * Clear lyrics cache for a specific media ID
     * Used when lyrics are updated
     */
    fun clearCache(mediaId: String) {
        lyricsCache.remove(mediaId)
        if (_currentLyrics.value?.mediaId == mediaId) {
            _currentLyrics.value = null
        }
    }
    
    /**
     * Clear all cached lyrics
     */
    fun clearAllCache() {
        lyricsCache.clear()
        _currentLyrics.value = null
    }
    
    /**
     * Update lyrics for the current track
     * Used when lyrics are edited or imported
     */
    suspend fun updateLyrics(mediaId: String, text: String?, lrcLines: List<LrcLine> = emptyList(), isLrc: Boolean = false) {
        val state = LyricsState(
            mediaId = mediaId,
            text = text,
            lrcLines = lrcLines,
            isLrc = isLrc,
            isLoading = false
        )
        
        // Update cache
        lyricsCache[mediaId] = state
        
        // Update current state if it's the same track
        if (_currentLyrics.value?.mediaId == mediaId) {
            _currentLyrics.value = state
        }
    }
    
}
