package com.musify.mu.data.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
                val artUri = getOrCreateTrackArtwork(contentUri.toString(), albumId, title, artist, album)
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
    
    private suspend fun getOrCreateTrackArtwork(trackUri: String, albumId: Long, title: String, artist: String, album: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Create unique filename for this track
                val trackKey = "${trackUri.hashCode()}_${title.trim()}_${artist.trim()}"
                val hashKey = trackKey.hashCode().toString()
                val filename = "track_art_${hashKey}.jpg"
                
                val artDir = File(context.filesDir, "track_artwork")
                if (!artDir.exists()) {
                    artDir.mkdirs()
                }
                
                val artFile = File(artDir, filename)
                
                // If file already exists, return its URI
                if (artFile.exists()) {
                    return@withContext Uri.fromFile(artFile).toString()
                }
                
                // Try to get artwork from the individual track first
                val bitmap = try {
                    val uri = Uri.parse(trackUri)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                    } else {
                        // For older versions, try to get track artwork
                        val cursor = context.contentResolver.query(
                            uri,
                            arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                            null,
                            null,
                            null
                        )
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val trackAlbumId = it.getLong(0)
                                if (trackAlbumId != 0L) {
                                    val albumArtUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, trackAlbumId)
                                    val albumCursor = context.contentResolver.query(
                                        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                        arrayOf(MediaStore.Audio.Albums.ALBUM_ART),
                                        "${MediaStore.Audio.Albums._ID} = ?",
                                        arrayOf(trackAlbumId.toString()),
                                        null
                                    )
                                    albumCursor?.use { ac ->
                                        if (ac.moveToFirst()) {
                                            val artPath = ac.getString(0)
                                            if (artPath != null) {
                                                BitmapFactory.decodeFile(artPath)
                                            } else null
                                        } else null
                                    }
                                } else null
                            } else null
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to album artwork if track artwork fails
                    if (albumId != 0L) {
                        val albumArtUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                context.contentResolver.loadThumbnail(albumArtUri, Size(512, 512), null)
                            } else {
                                val cursor = context.contentResolver.query(
                                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                    arrayOf(MediaStore.Audio.Albums.ALBUM_ART),
                                    "${MediaStore.Audio.Albums._ID} = ?",
                                    arrayOf(albumId.toString()),
                                    null
                                )
                                cursor?.use {
                                    if (it.moveToFirst()) {
                                        val artPath = it.getString(0)
                                        if (artPath != null) {
                                            BitmapFactory.decodeFile(artPath)
                                        } else null
                                    } else null
                                }
                            }
                        } catch (e2: Exception) {
                            null
                        }
                    } else null
                }
                
                // Save bitmap to file
                bitmap?.let {
                    FileOutputStream(artFile).use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    Uri.fromFile(artFile).toString()
                } ?: run {
                    // Return null if we can't process the image
                    null
                }
            } catch (e: Exception) {
                android.util.Log.w("MediaStoreScanner", "Failed to get track artwork for: $title", e)
                null
            }
        }
    }
}
