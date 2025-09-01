package com.musify.mu.data.proto

import com.musify.mu.data.db.entities.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Spotify-style QueryResult using Kotlin serialization instead of protobuf
 * This provides efficient serialization/deserialization for the scanning results
 */
@Serializable
data class QueryResult(
    val tracks: List<LocalTrack>,
    val timestamp: Long = System.currentTimeMillis(),
    val scanDurationMs: Long = 0
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromTracks(tracks: List<Track>): QueryResult {
            val localTracks = tracks.map { LocalTrack.fromTrack(it) }
            return QueryResult(tracks = localTracks)
        }

        fun fromByteArray(bytes: ByteArray): QueryResult {
            return json.decodeFromString(String(bytes))
        }
    }

    fun toByteArray(): ByteArray {
        return json.encodeToString(this).toByteArray()
    }

    fun toTracks(): List<Track> {
        return tracks.map { it.toTrack() }
    }
}

/**
 * LocalTrack represents a track in the query result
 */
@Serializable
data class LocalTrack(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: String?,
    val albumId: Long?,
    val dateAddedSec: Long,
    val genre: String?,
    val year: Int?,
    val track: Int?,
    val albumArtist: String?,
    val hasEmbeddedArt: Boolean = false,
    val filePath: String? = null
) {
    companion object {
        fun fromTrack(track: Track): LocalTrack {
            return LocalTrack(
                mediaId = track.mediaId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                artUri = track.artUri,
                albumId = track.albumId,
                dateAddedSec = track.dateAddedSec,
                genre = track.genre,
                year = track.year,
                track = track.track,
                albumArtist = track.albumArtist,
                hasEmbeddedArt = track.artUri?.startsWith("file://") == true,
                filePath = track.mediaId
            )
        }
    }

    fun toTrack(): Track {
        return Track(
            mediaId = mediaId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artUri = artUri,
            albumId = albumId,
            dateAddedSec = dateAddedSec,
            genre = genre,
            year = year,
            track = track,
            albumArtist = albumArtist
        )
    }
}

/**
 * OpenedAudioFiles storage for permanent URIs (Spotify style)
 */
@Serializable
data class OpenedAudioFiles(
    val permanentFiles: Set<String> = emptySet(),
    val temporaryFiles: Set<String> = emptySet()
) {
    companion object {
        fun fromByteArray(bytes: ByteArray): OpenedAudioFiles {
            return Json.decodeFromString(String(bytes))
        }
    }

    fun toByteArray(): ByteArray {
        return Json.encodeToString(this).toByteArray()
    }

    fun getAllFiles(): Set<String> {
        return permanentFiles + temporaryFiles
    }
}

/**
 * Storage interface for QueryResult caching
 */
interface QueryResultStorage {
    suspend fun save(result: QueryResult)
    suspend fun load(): QueryResult?
    suspend fun clear()
    fun hasValidCache(): Boolean
}

/**
 * Storage interface for custom artwork mappings
 */
interface CustomArtworkStorage {
    suspend fun save(trackUri: String, artworkUri: String)
    suspend fun load(trackUri: String): String?
    suspend fun loadAll(): Map<String, String>
    suspend fun remove(trackUri: String)
    suspend fun clear()
}

/**
 * Storage interface for opened audio files
 */
interface OpenedAudioFilesStorage {
    suspend fun save(files: Set<String>)
    suspend fun load(): OpenedAudioFiles
    suspend fun addFile(uri: String)
    suspend fun removeFile(uri: String)
}

/**
 * Implementation using SharedPreferences
 */
class OpenedAudioFilesStorageImpl(
    private val context: android.content.Context
) : OpenedAudioFilesStorage {

    private val prefs = context.getSharedPreferences("localfiles_openedfiles", android.content.Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val KEY_OPENED_FILES = "opened_audio_files"
    }

    override suspend fun save(files: Set<String>) {
        val openedFiles = OpenedAudioFiles(permanentFiles = files)
        val jsonString = json.encodeToString(openedFiles)
        prefs.edit().putString(KEY_OPENED_FILES, jsonString).apply()
    }

    override suspend fun load(): OpenedAudioFiles {
        return try {
            val jsonString = prefs.getString(KEY_OPENED_FILES, null)
            jsonString?.let { json.decodeFromString(it) } ?: OpenedAudioFiles()
        } catch (e: Exception) {
            android.util.Log.w("OpenedAudioFilesStorage", "Failed to load opened files", e)
            OpenedAudioFiles()
        }
    }

    override suspend fun addFile(uri: String) {
        val current = load()
        val updated = current.copy(permanentFiles = current.permanentFiles + uri)
        save(updated.permanentFiles)
    }

    override suspend fun removeFile(uri: String) {
        val current = load()
        val updated = current.copy(permanentFiles = current.permanentFiles - uri)
        save(updated.permanentFiles)
    }
}

/**
 * Implementation for QueryResult caching
 */
class QueryResultStorageImpl(
    private val context: Context
) : QueryResultStorage {

    private val prefs: SharedPreferences = context.getSharedPreferences("spotify_query_cache", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val KEY_QUERY_RESULT = "cached_query_result"
        private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
        private const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    override suspend fun save(result: QueryResult) {
        withContext(Dispatchers.IO) {
            try {
                val jsonString = json.encodeToString(result)
                prefs.edit()
                    .putString(KEY_QUERY_RESULT, jsonString)
                    .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                    .apply()
            } catch (e: Exception) {
                android.util.Log.e("QueryResultStorage", "Failed to save QueryResult", e)
            }
        }
    }

    override suspend fun load(): QueryResult? = withContext(Dispatchers.IO) {
        try {
            if (!hasValidCache()) return@withContext null

            val jsonString = prefs.getString(KEY_QUERY_RESULT, null)
            jsonString?.let { json.decodeFromString<QueryResult>(it) }
        } catch (e: Exception) {
            android.util.Log.e("QueryResultStorage", "Failed to load QueryResult", e)
            null
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .remove(KEY_QUERY_RESULT)
                .remove(KEY_CACHE_TIMESTAMP)
                .apply()
        }
    }

    override fun hasValidCache(): Boolean {
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        val age = System.currentTimeMillis() - timestamp
        return age < CACHE_VALIDITY_MS && prefs.contains(KEY_QUERY_RESULT)
    }
}

/**
 * Implementation for custom artwork storage
 */
class CustomArtworkStorageImpl(
    private val context: Context
) : CustomArtworkStorage {

    private val prefs: SharedPreferences = context.getSharedPreferences("spotify_custom_artwork", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val KEY_ARTWORK_MAP = "custom_artwork_map"
    }

    override suspend fun save(trackUri: String, artworkUri: String) {
        withContext(Dispatchers.IO) {
            try {
                val currentMap = loadAll().toMutableMap()
                currentMap[trackUri] = artworkUri
                val jsonString = json.encodeToString(currentMap)
                prefs.edit().putString(KEY_ARTWORK_MAP, jsonString).apply()
            } catch (e: Exception) {
                android.util.Log.e("CustomArtworkStorage", "Failed to save custom artwork", e)
            }
        }
    }

    override suspend fun load(trackUri: String): String? = withContext(Dispatchers.IO) {
        try {
            val map = loadAll()
            map[trackUri]
        } catch (e: Exception) {
            android.util.Log.e("CustomArtworkStorage", "Failed to load custom artwork", e)
            null
        }
    }

    override suspend fun loadAll(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = prefs.getString(KEY_ARTWORK_MAP, null)
            jsonString?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
        } catch (e: Exception) {
            android.util.Log.e("CustomArtworkStorage", "Failed to load custom artwork map", e)
            emptyMap()
        }
    }

    override suspend fun remove(trackUri: String) {
        withContext(Dispatchers.IO) {
            try {
                val currentMap = loadAll().toMutableMap()
                currentMap.remove(trackUri)
                val jsonString = json.encodeToString(currentMap)
                prefs.edit().putString(KEY_ARTWORK_MAP, jsonString).apply()
            } catch (e: Exception) {
                android.util.Log.e("CustomArtworkStorage", "Failed to remove custom artwork", e)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_ARTWORK_MAP).apply()
        }
    }
}
