package com.musify.mu.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VoskModelProvider {
    private const val MODEL_DIR_NAME = "model"
    private const val ASSET_DIR_NAME = "model-en-us"
    // private const val MODEL_ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    suspend fun ensureModel(context: Context): Model = withContext(Dispatchers.IO) {
        val filesRoot = context.filesDir
        val modelDir = File(filesRoot, MODEL_DIR_NAME)

        // If already present and looks valid, load directly
        if (isValidModelDir(modelDir)) {
            android.util.Log.d("VoskModelProvider", "Using existing model at: ${modelDir.absolutePath}")
            return@withContext Model(modelDir.absolutePath)
        }

        // Unpack from bundled assets only
        try {
            android.util.Log.d("VoskModelProvider", "Unpacking model from assets...")
            val model = suspendUnpackFromAssets(context)
            android.util.Log.d("VoskModelProvider", "Model unpacked from assets")
            return@withContext model
        } catch (e: Exception) {
            android.util.Log.e("VoskModelProvider", "Bundled VOSK model not available: ${e.message}")
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "Bundled voice model missing",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            throw IllegalStateException("Bundled VOSK model not found in assets: $ASSET_DIR_NAME", e)
        }
    }

    private fun isValidModelDir(dir: File): Boolean {
        return dir.exists() && dir.isDirectory && File(dir, "conf").exists()
    }

    private suspend fun suspendUnpackFromAssets(context: Context): Model = suspendCancellableCoroutine { cont ->
        StorageService.unpack(
            context,
            ASSET_DIR_NAME,
            MODEL_DIR_NAME,
            { model -> cont.resume(model) },
            { ex -> cont.resumeWithException(ex) }
        )
    }

    // downloadFile and unzip helpers retained but unused
}