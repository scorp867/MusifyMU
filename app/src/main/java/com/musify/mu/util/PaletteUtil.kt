package com.musify.mu.util

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

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

object PaletteUtil {

    fun createGradientBitmap(palette: Palette): Bitmap {
        val width = 200
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val vibrant = palette.getVibrantColor(Color.BLACK)
        val darkVibrant = palette.getDarkVibrantColor(vibrant)
        val lightVibrant = palette.getLightVibrantColor(vibrant)

        val gradient = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(vibrant, darkVibrant, lightVibrant),
            null,
            Shader.TileMode.CLAMP
        )

        val paint = Paint()
        paint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        return bitmap
    }
}
