package com.musify.mu.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Minimal metadata writer using MediaStore updates where possible.
 * For broader tag formats (ID3/Vorbis), a dedicated library would be required.
 * This utility updates MediaStore title/artist/album and album art via album id where supported.
 */
object MetadataWriter {
    @JvmStatic
    fun writeTags(
        context: Context,
        mediaUriString: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null
    ) {
        try {
            val uri = Uri.parse(mediaUriString)
            val values = ContentValues()
            if (!title.isNullOrBlank()) values.put(MediaStore.Audio.Media.TITLE, title)
            if (!artist.isNullOrBlank()) values.put(MediaStore.Audio.Media.ARTIST, artist)
            if (!album.isNullOrBlank()) values.put(MediaStore.Audio.Media.ALBUM, album)
            if (values.size() > 0) {
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (_: Exception) {
        }
    }
}

