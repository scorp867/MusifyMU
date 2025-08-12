package com.musify.mu.util

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun extractPalette(contentResolver: ContentResolver, artUri: String?): Palette? =
    withContext(Dispatchers.IO) {
        if (artUri == null) return@withContext null
        runCatching {
            contentResolver.openInputStream(Uri.parse(artUri)).use { input ->
                if (input != null) {
                    val bitmap = BitmapFactory.decodeStream(input)
                    Palette.from(bitmap).clearFilters().generate()
                } else null
            }
        }.getOrNull()
    }
