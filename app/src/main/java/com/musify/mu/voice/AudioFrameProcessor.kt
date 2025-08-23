package com.musify.mu.voice

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Utility class for audio frame processing optimizations.
 * Handles frame buffering and efficient audio data conversion.
 */
object AudioFrameProcessor {
    private const val TAG = "AudioFrameProcessor"
    
    // Frame buffer for efficient processing
    private val frameBuffer = ShortArray(480) // Optimal frame size for audio processing
    private val mutex = Mutex()
    
    /**
     * Convert ShortArray to ByteArray in little-endian format for Vosk
     */
    fun shortsToBytesLE(src: ShortArray, len: Int): ByteArray {
        val out = ByteArray(len * 2)
        var i = 0
        var j = 0
        while (i < len) {
            val v = src[i].toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v ushr 8) and 0xFF).toByte()
            i++
        }
        return out
    }
    
    /**
     * Convert ByteArray back to ShortArray from little-endian format
     */
    fun bytesToShortsLE(src: ByteArray, len: Int): ShortArray {
        val out = ShortArray(len / 2)
        var i = 0
        var j = 0
        while (i < len - 1) {
            val low = src[i].toInt() and 0xFF
            val high = src[i + 1].toInt() and 0xFF
            out[j] = ((high shl 8) or low).toShort()
            i += 2
            j++
        }
        return out
    }
    
    /**
     * Process audio frame with optional noise suppression
     * This is the main entry point for real-time audio processing
     */
    suspend fun processAudioFrame(
        rawFrame: ShortArray,
        frameLength: Int,
        rnnoiseProcessor: RNNoiseProcessor?
    ): ShortArray {
        return mutex.withLock {
            try {
                if (rnnoiseProcessor?.isReady() == true) {
                    // Apply RNNoise noise suppression
                    val cleanedFrame = rnnoiseProcessor.processFrame(rawFrame, frameLength)
                    if (cleanedFrame != null) {
                        Log.v(TAG, "Frame processed with RNNoise: ${rawFrame.size} -> ${cleanedFrame.size}")
                        return cleanedFrame
                    } else {
                        Log.w(TAG, "RNNoise processing failed, using raw frame")
                        return rawFrame
                    }
                } else {
                    // RNNoise not ready, use raw frame
                    Log.v(TAG, "RNNoise not ready, using raw frame")
                    return rawFrame
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio frame", e)
                return rawFrame
            }
        }
    }
    
    /**
     * Check if frame size is optimal for audio processing
     */
    fun isOptimalFrameSize(frameSize: Int): Boolean {
        // Audio processing works best with standard frame sizes
        // For 16kHz, that's 160 samples (10ms), 320 samples (20ms), or 480 samples (30ms)
        return frameSize == 160 || frameSize == 320 || frameSize == 480
    }
    
    /**
     * Get recommended frame size for optimal audio processing performance
     */
    fun getRecommendedFrameSize(sampleRate: Int): Int {
        return when (sampleRate) {
            16000 -> 160  // 10ms at 16kHz
            32000 -> 320  // 10ms at 32kHz
            48000 -> 480  // 10ms at 48kHz
            else -> 320   // Default fallback
        }
    }
}