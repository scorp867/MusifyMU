package com.musify.mu.data.paging

import android.content.Context
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.media.ArtworkManager
import com.musify.mu.data.media.SimpleMediaStoreScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * Remote mediator for Paging 3 that synchronizes MediaStore with Room database.
 * This ensures efficient loading and keeps data in sync with the device's media files.
 */
@OptIn(ExperimentalPagingApi::class)
class MediaStoreRemoteMediator(
    private val context: Context,
    private val database: AppDatabase,
    private val scanner: SimpleMediaStoreScanner
) : RemoteMediator<Int, Track>() {

    companion object {
        private const val TAG = "MediaStoreRemoteMediator"
    }

    override suspend fun initialize(): InitializeAction {
        // Always refresh if we have no tracks in the database
        val hasTracksInDb = database.dao().getAllTracks().isNotEmpty()
        return if (hasTracksInDb) {
            InitializeAction.SKIP_INITIAL_REFRESH
        } else {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        }
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, Track>
    ): MediatorResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading with type: $loadType")
            
            when (loadType) {
                LoadType.REFRESH -> {
                    // Refresh: scan MediaStore and update database
                    refreshMediaStore()
                }
                LoadType.PREPEND -> {
                    // For tracks, we don't need prepend since we load all at once
                    return@withContext MediatorResult.Success(endOfPaginationReached = true)
                }
                LoadType.APPEND -> {
                    // For tracks, we don't need append since we load all at once
                    return@withContext MediatorResult.Success(endOfPaginationReached = true)
                }
            }
            
            MediatorResult.Success(endOfPaginationReached = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error in MediaStore sync", e)
            MediatorResult.Error(e)
        }
    }

    /**
     * Refresh tracks from MediaStore and update database
     */
    private suspend fun refreshMediaStore() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting MediaStore refresh...")
            
            // Scan tracks from MediaStore (without album art extraction)
            val scannedTracks = scanner.scanTracksWithoutArtwork()
            
            if (scannedTracks.isNotEmpty()) {
                // Update database in a transaction
                database.withTransaction {
                    // Clear old tracks and insert new ones
                    database.dao().clearAllTracks()
                    database.dao().upsertTracks(scannedTracks)
                }
                
                Log.d(TAG, "MediaStore refresh completed with ${scannedTracks.size} tracks")
            } else {
                Log.w(TAG, "No tracks found in MediaStore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing MediaStore", e)
            throw e
        }
    }
}