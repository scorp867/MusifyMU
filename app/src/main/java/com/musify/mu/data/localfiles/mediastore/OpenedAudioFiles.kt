package com.musify.mu.data.localfiles.mediastore

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages permanently opened audio file URIs using SharedPreferences
 * Based on Spotify's com.spotify.localfiles.mediastore.OpenedAudioFiles
 * 
 * This allows the app to remember files that were previously opened
 * via SAF (Storage Access Framework) or other means, even across app restarts.
 */
class OpenedAudioFiles private constructor(
    private val storage: OpenedAudioFilesStorage
) {
    companion object {
        private const val TAG = "OpenedAudioFiles"
        
        @Volatile
        private var INSTANCE: OpenedAudioFiles? = null
        
        fun getInstance(context: Context): OpenedAudioFiles {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OpenedAudioFiles(
                    OpenedAudioFilesStorageImpl(context)
                ).also { INSTANCE = it }
            }
        }
    }
    
    private val mutex = Mutex()
    
    // Permanent files stored in SharedPreferences
    private var permanentFiles: Set<Uri> = emptySet()
    
    // Temporary files for the current session only
    private val temporaryFiles = ConcurrentHashMap.newKeySet<Uri>()
    
    private var isInitialized = false
    
    /**
     * Initialize by loading permanent files from storage
     */
    suspend fun initialize() = mutex.withLock {
        if (isInitialized) return
        
        try {
            permanentFiles = storage.load()
            Log.d(TAG, "Loaded ${permanentFiles.size} permanent audio files")
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading permanent audio files", e)
            permanentFiles = emptySet()
        }
    }
    
    /**
     * Add a URI to the permanent collection
     * This will persist across app restarts
     */
    suspend fun addPermanentFile(uri: Uri) = mutex.withLock {
        val newSet = permanentFiles + uri
        permanentFiles = newSet
        storage.save(newSet)
        Log.d(TAG, "Added permanent file: $uri (total: ${newSet.size})")
    }
    
    /**
     * Add multiple URIs to the permanent collection
     */
    suspend fun addPermanentFiles(uris: Collection<Uri>) = mutex.withLock {
        val newSet = permanentFiles + uris
        permanentFiles = newSet
        storage.save(newSet)
        Log.d(TAG, "Added ${uris.size} permanent files (total: ${newSet.size})")
    }
    
    /**
     * Remove a URI from the permanent collection
     */
    suspend fun removePermanentFile(uri: Uri) = mutex.withLock {
        val newSet = permanentFiles - uri
        permanentFiles = newSet
        storage.save(newSet)
        Log.d(TAG, "Removed permanent file: $uri (total: ${newSet.size})")
    }
    
    /**
     * Add a URI to the temporary collection
     * This will NOT persist across app restarts
     */
    fun addTemporaryFile(uri: Uri) {
        temporaryFiles.add(uri)
        Log.d(TAG, "Added temporary file: $uri (total: ${temporaryFiles.size})")
    }
    
    /**
     * Remove a URI from the temporary collection
     */
    fun removeTemporaryFile(uri: Uri) {
        temporaryFiles.remove(uri)
        Log.d(TAG, "Removed temporary file: $uri (total: ${temporaryFiles.size})")
    }
    
    /**
     * Clear all temporary files
     */
    fun clearTemporaryFiles() {
        val count = temporaryFiles.size
        temporaryFiles.clear()
        Log.d(TAG, "Cleared $count temporary files")
    }
    
    /**
     * Get all permanent files
     */
    fun getPermanentFiles(): Set<Uri> = permanentFiles
    
    /**
     * Get all temporary files
     */
    fun getTemporaryFiles(): Set<Uri> = temporaryFiles.toSet()
    
    /**
     * Get all files (permanent + temporary)
     */
    fun getAllFiles(): Set<Uri> = permanentFiles + temporaryFiles
    
    /**
     * Check if a URI is in the permanent collection
     */
    fun isPermanent(uri: Uri): Boolean = permanentFiles.contains(uri)
    
    /**
     * Check if a URI is in the temporary collection
     */
    fun isTemporary(uri: Uri): Boolean = temporaryFiles.contains(uri)
    
    /**
     * Check if a URI is tracked (permanent or temporary)
     */
    fun isTracked(uri: Uri): Boolean = isPermanent(uri) || isTemporary(uri)
    
    /**
     * Clear all files (permanent and temporary)
     */
    suspend fun clearAll() = mutex.withLock {
        permanentFiles = emptySet()
        temporaryFiles.clear()
        storage.save(emptySet())
        Log.d(TAG, "Cleared all opened audio files")
    }
    
    /**
     * Get statistics about opened files
     */
    fun getStats(): OpenedFilesStats {
        return OpenedFilesStats(
            permanentCount = permanentFiles.size,
            temporaryCount = temporaryFiles.size,
            totalCount = permanentFiles.size + temporaryFiles.size
        )
    }
}

/**
 * Storage interface for opened audio files
 * Based on Spotify's OpenedAudioFilesStorage interface
 */
interface OpenedAudioFilesStorage {
    suspend fun save(uris: Set<Uri>)
    suspend fun load(): Set<Uri>
}

/**
 * SharedPreferences implementation of OpenedAudioFilesStorage
 * Based on Spotify's OpenedAudioFilesStorageImpl
 */
class OpenedAudioFilesStorageImpl(
    private val context: Context
) : OpenedAudioFilesStorage {
    
    companion object {
        private const val PREFS_NAME = "localfiles_openedfiles"
        private const val KEY_OPENED_FILES = "opened_files_set"
        private const val TAG = "OpenedAudioFilesStorage"
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    override suspend fun save(uris: Set<Uri>) {
        try {
            val uriStrings = uris.map { it.toString() }.toSet()
            prefs.edit()
                .putStringSet(KEY_OPENED_FILES, uriStrings)
                .apply()
            Log.d(TAG, "Saved ${uris.size} opened file URIs to SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving opened files to SharedPreferences", e)
        }
    }
    
    override suspend fun load(): Set<Uri> {
        return try {
            val uriStrings = prefs.getStringSet(KEY_OPENED_FILES, emptySet()) ?: emptySet()
            val uris = uriStrings.mapNotNull { uriString ->
                try {
                    Uri.parse(uriString)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid URI in SharedPreferences: $uriString", e)
                    null
                }
            }.toSet()
            
            Log.d(TAG, "Loaded ${uris.size} opened file URIs from SharedPreferences")
            uris
        } catch (e: Exception) {
            Log.e(TAG, "Error loading opened files from SharedPreferences", e)
            emptySet()
        }
    }
}

/**
 * Statistics about opened files
 */
data class OpenedFilesStats(
    val permanentCount: Int,
    val temporaryCount: Int,
    val totalCount: Int
)

