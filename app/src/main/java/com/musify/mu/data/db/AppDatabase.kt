package com.musify.mu.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.musify.mu.data.db.entities.*

@Database(
    entities = [Track::class, Playlist::class, PlaylistItem::class, Like::class, FavoritesOrder::class, LyricsMap::class, PlayHistory::class, HiddenRecentlyAdded::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}
