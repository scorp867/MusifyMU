package com.musify.mu.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.musify.mu.data.db.entities.Track
import com.musify.mu.data.media.SimpleBackgroundDataManager
import kotlinx.coroutines.flow.first

class CachedTrackPagingSource(
    private val dataManager: SimpleBackgroundDataManager,
    private val query: String? = null
) : PagingSource<Int, Track>() {

    override fun getRefreshKey(state: PagingState<Int, Track>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        return try {
            val page = params.key ?: 0
            val pageSize = params.loadSize
            
            android.util.Log.d("CachedTrackPagingSource", "Loading page $page with size $pageSize")
            
            // Get all tracks from cache
            val allTracks = dataManager.cachedTracks.first()
            
            // Filter if query is provided
            val filteredTracks = if (!query.isNullOrBlank()) {
                allTracks.filter { track ->
                    track.title.contains(query, ignoreCase = true) ||
                    track.artist.contains(query, ignoreCase = true) ||
                    track.album.contains(query, ignoreCase = true)
                }
            } else {
                allTracks
            }
            
            // Calculate pagination
            val startIndex = page * pageSize
            val endIndex = minOf(startIndex + pageSize, filteredTracks.size)
            
            val tracks = if (startIndex < filteredTracks.size) {
                filteredTracks.subList(startIndex, endIndex)
            } else {
                emptyList()
            }
            
            android.util.Log.d("CachedTrackPagingSource", "Returning ${tracks.size} tracks from cache (total: ${filteredTracks.size})")
            
            LoadResult.Page(
                data = tracks,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (endIndex >= filteredTracks.size) null else page + 1
            )
        } catch (e: Exception) {
            android.util.Log.e("CachedTrackPagingSource", "Error loading page", e)
            LoadResult.Error(e)
        }
    }
}