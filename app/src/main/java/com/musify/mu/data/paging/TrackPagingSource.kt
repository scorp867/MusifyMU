package com.musify.mu.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrackPagingSource(
    private val db: AppDatabase,
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

            android.util.Log.d("TrackPagingSource", "Loading page $page with size $pageSize")

            val tracks = withContext(Dispatchers.IO) {
                if (query.isNullOrBlank()) {
                    val result = db.dao().getTracksPaged(
                        limit = pageSize,
                        offset = page * pageSize
                    )
                    android.util.Log.d("TrackPagingSource", "Loaded ${result.size} tracks for page $page")
                    result
                } else {
                    val result = db.dao().searchTracksPaged(
                        query = "%$query%",
                        limit = pageSize,
                        offset = page * pageSize
                    )
                    android.util.Log.d("TrackPagingSource", "Loaded ${result.size} tracks for search query '$query' page $page")
                    result
                }
            }

            LoadResult.Page(
                data = tracks,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (tracks.isEmpty()) null else page + 1
            )
        } catch (e: Exception) {
            android.util.Log.e("TrackPagingSource", "Error loading page", e)
            LoadResult.Error(e)
        }
    }
}