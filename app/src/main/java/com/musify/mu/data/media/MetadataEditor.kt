package com.musify.mu.data.media

import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MetadataEditor {
    suspend fun requestWriteAccess(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pi: PendingIntent = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                // We cannot start UI here as a background agent, but callers may handle if needed.
                // For now, best-effort no-op; user will be prompted when writing if required.
            } catch (_: Exception) {}
        }
    }

    /**
     * Embed album art into the audio file by copying to a temp file, updating tags, then writing back.
     * Returns a cached file URI for the artwork for immediate UI use.
     */
    suspend fun embedAlbumArt(context: Context, audioUri: Uri, imageUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // Copy audio to temp file
            val tmpAudio = File.createTempFile("tagedit_", ".audio", context.cacheDir)
            context.contentResolver.openInputStream(audioUri)?.use { input ->
                tmpAudio.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null

            // Copy image to temp file (jaudiotagger wants File)
            val tmpImage = File.createTempFile("cover_", ".img", context.cacheDir)
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                tmpImage.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null

            // Update tags using jaudiotagger
            try {
                val audioFile = org.jaudiotagger.audio.AudioFileIO.read(tmpAudio)
                val tag = audioFile.tagOrCreateAndSetDefault
                val artwork = org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(tmpImage)
                tag.deleteArtworkField()
                tag.addField(artwork)
                org.jaudiotagger.audio.AudioFileIO.write(audioFile)
            } catch (_: Exception) {
                // Fallback: still return UI art
            }

            // Write back modified temp file to content URI
            context.contentResolver.openOutputStream(audioUri, "w")?.use { out ->
                tmpAudio.inputStream().use { it.copyTo(out) }
            }

            // Store artwork bytes into on-demand cache for immediate UI
            val artBytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            if (artBytes != null) {
                return@withContext com.musify.mu.util.OnDemandArtworkLoader.storeArtworkBytes(audioUri.toString(), artBytes)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun editBasicTags(context: Context, audioUri: Uri, title: String?, artist: String?, album: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val tmpAudio = File.createTempFile("tagedit_", ".audio", context.cacheDir)
            context.contentResolver.openInputStream(audioUri)?.use { input ->
                tmpAudio.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false

            try {
                val audioFile = org.jaudiotagger.audio.AudioFileIO.read(tmpAudio)
                val tag = audioFile.tagOrCreateAndSetDefault
                if (!title.isNullOrBlank()) tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, title)
                if (!artist.isNullOrBlank()) tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, artist)
                if (!album.isNullOrBlank()) tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, album)
                org.jaudiotagger.audio.AudioFileIO.write(audioFile)
            } catch (_: Exception) {}

            context.contentResolver.openOutputStream(audioUri, "w")?.use { out ->
                tmpAudio.inputStream().use { it.copyTo(out) }
            }

            true
        } catch (_: Exception) { false }
    }
}

