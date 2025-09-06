package com.musify.mu.data.repo

import android.content.Context
import org.json.JSONArray
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.AppDao
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.db.entities.*
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.data.media.LoadingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val context: Context,
    private val db: AppDatabase,
    private val injectedDataManager: SpotifyStyleDataManager
) {

    // Data manager is now injected via constructor

    // Background loading progress
    val loadingState: StateFlow<LoadingState> = injectedDataManager.loadingState
    
    // Expose dataManager for external access
    val dataManager: SpotifyStyleDataManager = injectedDataManager

    // Fast access to all tracks from cache - fallback to database if cache is empty
    fun getAllTracks(): List<Track> {
        val cachedTracks = dataManager.getAllTracks()
        return if (cachedTracks.isNotEmpty()) {
            cachedTracks
        } else {
            // Fallback to database if cache is empty (e.g., after app restart)
            try {
                runBlocking { db.dao().getAllTracks() }
            } catch (e: Exception) {
                android.util.Log.w("LibraryRepository", "Failed to get tracks from database", e)
                emptyList()
            }
        }
    }



    // Search in cached data - NO database query
    fun search(q: String): List<Track> = dataManager.searchTracks(q)

    // Get playlists from database (these are user-created, not cached)
    suspend fun playlists(): List<Playlist> = db.dao().getPlaylists()

    // Playlist management operations
    suspend fun createPlaylist(name: String, imageUri: String? = null): Long = db.dao().createPlaylist(Playlist(name = name, imageUri = imageUri))
    suspend fun renamePlaylist(id: Long, name: String) = db.dao().renamePlaylist(id, name)
    suspend fun deletePlaylist(id: Long) = db.dao().deletePlaylist(id)

    suspend fun addToPlaylist(playlistId: Long, mediaIds: List<String>) {
        val existing = db.dao().getPlaylistTracks(playlistId).map { it.mediaId }.toSet()
        val toInsert = mediaIds.filter { it !in existing }
        if (toInsert.isEmpty()) return
        val start = db.dao().getPlaylistTracks(playlistId).size
        val items = toInsert.mapIndexed { idx, id -> PlaylistItem(playlistId, id, start + idx) }
        db.dao().addItems(items)
    }

    suspend fun removeFromPlaylist(playlistId: Long, mediaId: String) = db.dao().removeItem(playlistId, mediaId)
    suspend fun playlistTracks(playlistId: Long): List<Track> {
        val databaseTracks = db.dao().getPlaylistTracks(playlistId)
        val currentTrackIds = dataManager.tracks.value.map { it.mediaId }.toSet()
        // If cache is empty (e.g., before first scan completes), show DB results to avoid empty playlists
        if (currentTrackIds.isEmpty()) return databaseTracks
        // Otherwise, filter out tracks that no longer exist on device
        return databaseTracks.filter { track -> currentTrackIds.contains(track.mediaId) }
    }

    // Persist a new playlist order by replacing positions via REPLACE insert
    suspend fun reorderPlaylist(playlistId: Long, orderedMediaIds: List<String>) {
        if (orderedMediaIds.isEmpty()) return
        val items = orderedMediaIds.mapIndexed { index, mediaId ->
            PlaylistItem(playlistId = playlistId, mediaId = mediaId, position = index)
        }
        db.dao().addItems(items)
    }

    // Like/unlike operations
    suspend fun like(mediaId: String) = db.dao().like(Like(mediaId))
    suspend fun unlike(mediaId: String) = db.dao().unlike(mediaId)
    suspend fun isLiked(mediaId: String): Boolean = db.dao().isLiked(mediaId)

    // Get favorites from database (these are user-created, not cached)
    // Filter out deleted tracks by checking against current cached tracks
    suspend fun favorites(): List<Track> {
        val databaseFavorites = db.dao().getFavorites()
        val currentTrackIds = dataManager.tracks.value.map { it.mediaId }.toSet()
        return databaseFavorites.filter { track -> currentTrackIds.contains(track.mediaId) }
    }
    suspend fun saveFavoritesOrder(order: List<FavoritesOrder>) = db.dao().upsertFavoriteOrder(order)

    // Get track by media ID from cached data - optimized to avoid multiple getAllTracks calls
    fun getTrackByMediaId(mediaId: String): Track? {
        // Use the cached tracks directly from the data manager's StateFlow
        // This avoids calling getAllTracks() repeatedly
        return dataManager.tracks.value.find { it.mediaId == mediaId }
    }

    // Get recently added/played from database (these track user interactions, not cached)
    // Filter out deleted tracks by checking against current cached tracks
    suspend fun recentlyAdded(limit: Int = 20): List<Track> {
        val databaseRecent = db.dao().getRecentlyAdded(limit)
        val currentTrackIds = dataManager.tracks.value.map { it.mediaId }.toSet()
        return databaseRecent.filter { track -> currentTrackIds.contains(track.mediaId) }
    }

    suspend fun recentlyPlayed(limit: Int = 20): List<Track> {
        val databaseRecent = db.dao().getRecentlyPlayed(limit)
        val currentTrackIds = dataManager.tracks.value.map { it.mediaId }.toSet()
        return databaseRecent.filter { track -> currentTrackIds.contains(track.mediaId) }
    }
    suspend fun recordPlayed(mediaId: String) = db.dao().insertOrUpdatePlayHistory(mediaId)

    // Clear recently played history
    suspend fun clearRecentlyPlayed() = db.dao().clearPlayHistory()

    // Clear recently added (this would typically involve clearing the MediaStore data or marking tracks as processed)
    // For now, we'll implement a method that could be extended to clear the recently added cache
    suspend fun clearRecentlyAdded() {
        // This would need to be implemented based on how recently added is tracked
        // For now, we'll leave this as a placeholder that could clear any cached recently added data
    }

    // Update track metadata (for user edits)
    suspend fun updateTrackMetadata(track: com.musify.mu.data.db.entities.Track) {
        db.dao().updateTrackMetadata(
            mediaId = track.mediaId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            genre = track.genre,
            year = track.year
        )
    }

    // Artwork cache update from playback metadata
    suspend fun updateTrackArt(mediaId: String, artUri: String?) = db.dao().updateTrackArt(mediaId, artUri)

    companion object {
        @Volatile private var INSTANCE: LibraryRepository? = null
        fun get(context: Context): LibraryRepository =
            INSTANCE ?: synchronized(this) {
                // Prefer Hilt-provided singleton if available
                try {
                    val entryPoint = dagger.hilt.android.EntryPoints.get(
                        context.applicationContext,
                        com.musify.mu.di.AppEntryPoints::class.java
                    )
                    val repo = entryPoint.libraryRepository()
                    INSTANCE = repo
                    repo
                } catch (_: Throwable) {
                    // Fallback to manual instantiation (e.g., in tests)
                    val db = DatabaseProvider.get(context)
                    val dataManager = SpotifyStyleDataManager.getInstance(context, db)
                    INSTANCE ?: LibraryRepository(context.applicationContext, db, dataManager).also {
                        INSTANCE = it
                    }
                }
            }
    }

    // ----- Search history (persisted locally, independent of play history) -----
    private val searchPrefs by lazy { context.getSharedPreferences("search_history_prefs", Context.MODE_PRIVATE) }
    private val searchKey = "history"

    fun getSearchHistory(max: Int = 20): List<String> {
        return try {
            val raw = searchPrefs.getString(searchKey, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val q = arr.optString(i).orEmpty()
                if (q.isNotBlank()) out += q
            }
            out.take(max)
        } catch (_: Exception) { emptyList() }
    }

    fun addSearchHistory(query: String, max: Int = 20) {
        val q = query.trim()
        if (q.isBlank()) return
        val current = getSearchHistory(max).toMutableList()
        current.removeAll { it.equals(q, ignoreCase = true) }
        current.add(0, q)
        while (current.size > max) current.removeLastOrNull()
        saveSearchHistory(current)
    }

    fun clearSearchHistory() {
        searchPrefs.edit().putString(searchKey, "[]").apply()
    }

    private fun saveSearchHistory(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        searchPrefs.edit().putString(searchKey, arr.toString()).apply()
    }
}
