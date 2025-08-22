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
    private const val MODEL_ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    suspend fun ensureModel(context: Context): Model = withContext(Dispatchers.IO) {
        val filesRoot = context.filesDir
        val modelDir = File(filesRoot, MODEL_DIR_NAME)

        // If already present and looks valid, load directly
        if (isValidModelDir(modelDir)) {
            android.util.Log.d("VoskModelProvider", "Using existing model at: ${modelDir.absolutePath}")
            return@withContext Model(modelDir.absolutePath)
        }

        // Try to unpack from assets (if bundled)
        try {
            android.util.Log.d("VoskModelProvider", "Trying to unpack model from assets...")
            val model = suspendUnpackFromAssets(context)
            android.util.Log.d("VoskModelProvider", "Model unpacked from assets")
            return@withContext model
        } catch (e: Exception) {
            android.util.Log.w("VoskModelProvider", "Assets model not available: ${e.message}")
        }

        // Download and unzip on first run
        android.util.Log.d("VoskModelProvider", "Downloading VOSK model...")
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                "Downloading voice model (~50MB)...",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        val tempZip = File(filesRoot, "vosk-model.zip")
        downloadFile(MODEL_ZIP_URL, tempZip)
        // Clean any existing incomplete model folder
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()
        unzipStripTopLevel(tempZip, modelDir)
        tempZip.delete()

        if (!isValidModelDir(modelDir)) {
            throw IllegalStateException("Downloaded model is invalid at ${modelDir.absolutePath}")
        }

        android.util.Log.d("VoskModelProvider", "Model downloaded and extracted to ${modelDir.absolutePath}")
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                "Voice model ready",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        Model(modelDir.absolutePath)
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

    private fun downloadFile(url: String, dest: File) {
        BufferedInputStream(URL(url).openStream()).use { input ->
            FileOutputStream(dest).use { fos ->
                val out = BufferedOutputStream(fos)
                val data = ByteArray(DEFAULT_BUFFER_SIZE)
                var count: Int
                while (true) {
                    count = input.read(data)
                    if (count == -1) break
                    out.write(data, 0, count)
                }
                out.flush()
            }
        }
    }

    private fun unzipStripTopLevel(zipFile: File, outDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val stripped = stripTopLevel(entry.name)
                    if (stripped.isNotBlank()) {
                        val outFile = File(outDir, stripped)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                            }
                            fos.flush()
                        }
                    }
                } else {
                    val dirName = stripTopLevel(entry.name)
                    if (dirName.isNotBlank()) {
                        File(outDir, dirName).mkdirs()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun stripTopLevel(path: String): String {
        val idx = path.indexOf('/')
        return if (idx >= 0) path.substring(idx + 1) else path
    }
}