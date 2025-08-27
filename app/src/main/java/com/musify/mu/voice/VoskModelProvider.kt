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

        // If already present and looks valid, load directly; otherwise wipe and recopy
        if (isValidModelDir(modelDir)) {
            android.util.Log.d("VoskModelProvider", "Using existing model at: ${modelDir.absolutePath}")
            return@withContext Model(modelDir.absolutePath)
        } else if (modelDir.exists()) {
            android.util.Log.w("VoskModelProvider", "Invalid model directory found, reinitializing: ${modelDir.absolutePath}")
            deleteRecursively(modelDir)
        }

        // Try to copy from bundled assets (supports nested model directories)
        try {
            android.util.Log.d("VoskModelProvider", "Copying model from assets...")
            val model = copyFromAssets(context, modelDir)
            android.util.Log.d("VoskModelProvider", "Model copied from assets")
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
        if (!dir.exists() || !dir.isDirectory) return false
        val confDir = File(dir, "conf")
        val ivectorDir = File(dir, "ivector")
        val extractorCandidates = listOf(
            File(ivectorDir, "final.ie"),
            File(ivectorDir, "final.dubm"),
            File(ivectorDir, "online_cmvn.conf"),
            File(ivectorDir, "ivector_online.conf")
        )
        val hasIvector = ivectorDir.exists() && ivectorDir.isDirectory && extractorCandidates.any { it.exists() }
        return confDir.exists() && hasIvector
    }

    private fun deleteRecursively(target: File) {
        if (!target.exists()) return
        if (target.isDirectory) {
            target.listFiles()?.forEach { deleteRecursively(it) }
        }
        try { target.delete() } catch (_: Exception) {}
    }

    private suspend fun copyFromAssets(context: Context, outDir: File): Model {
        val am = context.assets

        // Prefer a nested directory under ASSET_DIR_NAME that contains 'conf'
        val candidates = am.list(ASSET_DIR_NAME)?.toList() ?: emptyList()
        var assetRoot: String? = null

        // Check nested children first
        for (child in candidates) {
            val childEntries = am.list("$ASSET_DIR_NAME/$child")?.toList() ?: emptyList()
            if (childEntries.contains("conf")) {
                assetRoot = "$ASSET_DIR_NAME/$child"
                break
            }
        }
        // Fallback: ASSET_DIR_NAME itself contains 'conf'
        if (assetRoot == null) {
            val rootEntries = am.list(ASSET_DIR_NAME)?.toList() ?: emptyList()
            if (rootEntries.contains("conf")) {
                assetRoot = ASSET_DIR_NAME
            }
        }

        if (assetRoot == null) {
            throw IllegalStateException("No asset directory containing 'conf' found under $ASSET_DIR_NAME")
        }

        if (!outDir.exists()) outDir.mkdirs()
        // Recursively copy the chosen asset root's contents into outDir
        copyAssetDir(am, assetRoot, outDir)

        if (!isValidModelDir(outDir)) throw IllegalStateException("Copied model invalid at ${outDir.absolutePath}")
        return Model(outDir.absolutePath)
    }

    private fun copyAssetDir(am: android.content.res.AssetManager, assetPath: String, outDir: File) {
        val entries = am.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // This is a file
            copyAssetFile(am, assetPath, File(outDir, assetPath.substringAfterLast('/')))
            return
        }
        // Directory
        for (name in entries) {
            val childAssetPath = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val childOut = File(outDir, name)
            val subEntries = am.list(childAssetPath) ?: emptyArray()
            if (subEntries.isEmpty()) {
                // file
                copyAssetFile(am, childAssetPath, childOut)
            } else {
                // dir
                if (!childOut.exists()) childOut.mkdirs()
                copyAssetDir(am, childAssetPath, childOut)
            }
        }
    }

    private fun copyAssetFile(am: android.content.res.AssetManager, assetPath: String, outFile: File) {
        am.open(assetPath).use { input ->
            FileOutputStream(outFile).use { fos ->
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (true) {
                    read = input.read(buffer)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                }
                fos.flush()
            }
        }
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