package com.musify.mu.data.repo

import android.content.Context
import android.net.Uri
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.db.entities.LyricsMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LyricsRepository(private val context: Context) {

    private val db by lazy { DatabaseProvider.get(context) }

    // Attach an .lrc file to a mediaId
    suspend fun attachLrc(mediaId: String, fileUri: Uri) = withContext(Dispatchers.IO) {
        db.dao().upsertLyricsMap(LyricsMap(mediaId, "lrc", fileUri.toString()))
    }

    // Attach plain text lyrics to a mediaId
    suspend fun attachText(mediaId: String, text: String) = withContext(Dispatchers.IO) {
        db.dao().upsertLyricsMap(LyricsMap(mediaId, "text", text))
    }

    // Retrieve stored lyrics mapping for a mediaId
    suspend fun get(mediaId: String): LyricsMap? = db.dao().getLyricsMap(mediaId)
}
