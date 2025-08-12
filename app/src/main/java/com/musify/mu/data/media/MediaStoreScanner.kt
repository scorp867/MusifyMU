package com.musify.mu.data.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreScanner(private val context: Context, private val db: AppDatabase) {

    suspend fun scanAndCache(): List<Track> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} = 1",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artist = cursor.getString(artistCol) ?: "Unknown"
                val album = cursor.getString(albumCol) ?: "Unknown"
                val duration = cursor.getLong(durationCol)
                val albumId = cursor.getLong(albumIdCol)
                val artUri = getAlbumArt(albumId)
                val dateAdded = cursor.getLong(dateAddedCol)

                tracks += Track(
                    mediaId = contentUri.toString(),
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    artUri = artUri,
                    albumId = albumId,
                    dateAddedSec = dateAdded
                )
            }
        }
        db.dao().upsertTracks(tracks)
        tracks
    }
    
    private fun getAlbumArt(albumId: Long): String? {
        return if (albumId != 0L) {
            ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId).toString()
        } else null
    }
}
