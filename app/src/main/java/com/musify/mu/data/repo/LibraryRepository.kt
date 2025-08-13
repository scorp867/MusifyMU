package com.musify.mu.data.repo

import android.content.Context
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.db.entities.*
import com.musify.mu.data.media.MediaStoreScanner

class LibraryRepository private constructor(private val context: Context, private val db: AppDatabase) {

    private val scanner by lazy { MediaStoreScanner(context, db) }

    suspend fun refreshLibrary(): List<Track> = scanner.scanAndCache()
    suspend fun getAllTracks(): List<Track> = db.dao().getAllTracks()
    suspend fun search(q: String): List<Track> = db.dao().searchTracks("%$q%")
    suspend fun playlists(): List<Playlist> = db.dao().getPlaylists()
    suspend fun createPlaylist(name: String): Long = db.dao().createPlaylist(Playlist(name = name))
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
    suspend fun playlistTracks(playlistId: Long): List<Track> = db.dao().getPlaylistTracks(playlistId)

    suspend fun reorderPlaylist(playlistId: Long, orderedMediaIds: List<String>) {
        val items = orderedMediaIds.mapIndexed { index, mediaId -> PlaylistItem(playlistId, mediaId, index) }
        db.dao().addItems(items)
    }

    suspend fun like(mediaId: String) = db.dao().like(Like(mediaId))
    suspend fun unlike(mediaId: String) = db.dao().unlike(mediaId)
    suspend fun isLiked(mediaId: String): Boolean = db.dao().isLiked(mediaId)
    suspend fun favorites(): List<Track> = db.dao().getFavorites()
    suspend fun saveFavoritesOrder(order: List<FavoritesOrder>) = db.dao().upsertFavoriteOrder(order)

    suspend fun getTrackByMediaId(mediaId: String): Track? = db.dao().getTrack(mediaId)

    suspend fun recentlyAdded(limit: Int = 20): List<Track> = db.dao().getRecentlyAdded(limit)
    suspend fun recentlyPlayed(limit: Int = 20): List<Track> = db.dao().getRecentlyPlayed(limit)
    suspend fun recordPlayed(mediaId: String) = db.dao().insertPlayHistory(PlayHistory(mediaId = mediaId))
    suspend fun recordPlayedUnique(mediaId: String) {
        db.dao().clearPlayHistoryFor(mediaId)
        db.dao().insertPlayHistory(PlayHistory(mediaId = mediaId))
    }

    companion object {
        @Volatile private var INSTANCE: LibraryRepository? = null
        fun get(context: Context): LibraryRepository =
            INSTANCE ?: synchronized(this) {
                val db = DatabaseProvider.get(context)
                INSTANCE ?: LibraryRepository(context.applicationContext, db).also { INSTANCE = it }
            }
    }
}
