package com.musify.mu.data.repo

import android.content.Context
import androidx.paging.*
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.paging.MediaStoreRemoteMediator
import com.musify.mu.data.media.SimpleMediaStoreScanner
import kotlinx.coroutines.flow.Flow

/**
 * Repository that provides Paging 3 integration for efficient data loading
 * Works alongside LibraryRepository for paginated data access
 */
class PagingRepository private constructor(
    private val context: Context,
    private val database: AppDatabase
) {
    
    private val scanner by lazy { SimpleMediaStoreScanner(context, database) }
    
    /**
     * Get all tracks with Paging 3 support
     */
    @OptIn(ExperimentalPagingApi::class)
    fun getAllTracksPaged(pageSize: Int = 50): Flow<PagingData<Track>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                prefetchDistance = pageSize / 2
            ),
            remoteMediator = MediaStoreRemoteMediator(context, database, scanner),
            pagingSourceFactory = { database.dao().getAllTracksPaged() }
        ).flow
    }
    
    /**
     * Search tracks with Paging 3 support
     */
    fun searchTracksPaged(query: String, pageSize: Int = 30): Flow<PagingData<Track>> {
        val searchQuery = "%$query%"
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                prefetchDistance = pageSize / 2
            ),
            pagingSourceFactory = { database.dao().searchTracksPaged(searchQuery) }
        ).flow
    }
    
    /**
     * Get recently added tracks with Paging 3 support
     */
    fun getRecentlyAddedPaged(pageSize: Int = 30): Flow<PagingData<Track>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                prefetchDistance = pageSize / 2
            ),
            pagingSourceFactory = { database.dao().getRecentlyAddedPaged() }
        ).flow
    }
    
    /**
     * Get recently played tracks with Paging 3 support
     */
    fun getRecentlyPlayedPaged(pageSize: Int = 30): Flow<PagingData<Track>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                prefetchDistance = pageSize / 2
            ),
            pagingSourceFactory = { database.dao().getRecentlyPlayedPaged() }
        ).flow
    }
    
    /**
     * Get favorites with Paging 3 support
     */
    fun getFavoritesPaged(pageSize: Int = 30): Flow<PagingData<Track>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                prefetchDistance = pageSize / 2
            ),
            pagingSourceFactory = { database.dao().getFavoritesPaged() }
        ).flow
    }
    
    /**
     * Get playlist tracks with Paging 3 support
     */
    fun getPlaylistTracksPaged(playlistId: Long, pageSize: Int = 30): Flow<PagingData<Track>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                prefetchDistance = pageSize / 2
            ),
            pagingSourceFactory = { database.dao().getPlaylistTracksPaged(playlistId) }
        ).flow
    }
    
    companion object {
        @Volatile 
        private var INSTANCE: PagingRepository? = null
        
        fun getInstance(context: Context): PagingRepository {
            return INSTANCE ?: synchronized(this) {
                val database = DatabaseProvider.get(context)
                INSTANCE ?: PagingRepository(context.applicationContext, database).also { 
                    INSTANCE = it 
                }
            }
        }
    }
}