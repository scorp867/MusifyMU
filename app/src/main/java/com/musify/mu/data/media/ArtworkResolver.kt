package com.musify.mu.data.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.musify.mu.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtworkResolver(private val context: Context, private val db: AppDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun resolveMissingArtAsync() {
        scope.launch {
            resolveMissingArt()
        }
    }

    private suspend fun resolveMissingArt() = withContext(Dispatchers.IO) {
        val missing = db.dao().getTracksMissingArt()
        missing.forEach { track ->
            val art = extractEmbeddedArtPath(track.mediaId)
            db.dao().updateTrackArt(track.mediaId, art)
        }
    }

    private fun extractEmbeddedArtPath(audioUri: String): String? {
        return runCatching {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, Uri.parse(audioUri))
            val bytes = mmr.embeddedPicture
            mmr.release()
            if (bytes != null) {
                // Persist to app cache directory
                val dir = java.io.File(context.cacheDir, "embedded_art")
                if (!dir.exists()) dir.mkdirs()
                val file = java.io.File(dir, (audioUri.hashCode().toString()) + ".jpg")
                java.io.FileOutputStream(file).use { out ->
                    out.write(bytes)
                }
                file.absolutePath
            } else null
        }.getOrNull()
    }
}


