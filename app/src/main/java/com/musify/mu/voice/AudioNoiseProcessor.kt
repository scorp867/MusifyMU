package com.musify.mu.voice

import android.util.Log
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AcousticEchoCanceler
import android.media.AudioRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio noise suppression processor using Android's built-in audio effects.
 * Processes audio frames in real-time and feeds cleaned audio to Vosk.
 */
class AudioNoiseProcessor {
    companion object {
        private const val TAG = "AudioNoiseProcessor"
        private const val FRAME_SIZE = 320 // 20ms at 16kHz
        private const val SAMPLE_RATE = 16000 // Vosk sample rate
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private val mutex = Mutex()
    private var isInitialized = false

    // Buffers for audio processing
    private val inputBuffer = ShortArray(FRAME_SIZE)
    private val outputBuffer = ShortArray(FRAME_SIZE)

    /**
     * Initialize Android audio effects for noise suppression
     */
    suspend fun initialize(): Boolean = mutex.withLock {
        return try {
            // Check if noise suppression is available
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(0, SAMPLE_RATE)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "NoiseSuppressor initialized successfully")
            } else {
                Log.w(TAG, "NoiseSuppressor not available on this device")
            }

            // Check if acoustic echo cancellation is available
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(0, SAMPLE_RATE)
                acousticEchoCanceler?.enabled = true
                Log.d(TAG, "AcousticEchoCanceler initialized successfully")
            } else {
                Log.w(TAG, "AcousticEchoCanceler not available on this device")
            }

            isInitialized = true
            Log.d(TAG, "Audio effects initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio effects", e)
            isInitialized = false
            false
        }
    }

    /**
     * Process a frame of audio data and return the cleaned frame.
     * This method handles the real-time streaming requirement.
     * 
     * @param inputFrame Raw audio frame from microphone (16kHz, 16-bit PCM)
     * @param frameLength Length of the input frame
     * @return Cleaned audio frame ready for Vosk, or null if processing failed
     */
    suspend fun processFrame(inputFrame: ShortArray, frameLength: Int): ShortArray? = mutex.withLock {
        if (!isInitialized) {
            Log.w(TAG, "Audio effects not initialized, skipping frame")
            return null
        }

        return try {
            // Copy input frame to our buffer
            val frameToProcess = if (frameLength <= FRAME_SIZE) {
                inputFrame.copyOf(frameLength)
            } else {
                inputFrame.take(FRAME_SIZE).toShortArray()
            }

            // Apply noise suppression if available
            if (noiseSuppressor != null) {
                // Note: Android's NoiseSuppressor works on the AudioRecord level
                // For frame-level processing, we'll apply basic filtering
                val cleanedFrame = applyBasicNoiseReduction(frameToProcess)
                Log.v(TAG, "Frame processed with noise suppression: ${frameToProcess.size} samples")
                return cleanedFrame
            } else {
                // If no noise suppression available, return the frame as-is
                Log.v(TAG, "No noise suppression available, using raw frame")
                return frameToProcess
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame with audio effects", e)
            null
        }
    }

    /**
     * Apply basic noise reduction using simple threshold-based filtering
     */
    private fun applyBasicNoiseReduction(frame: ShortArray): ShortArray {
        val threshold = 500 // Adjustable noise threshold
        val result = ShortArray(frame.size)
        
        for (i in frame.indices) {
            val sample = frame[i]
            // Apply simple threshold-based noise reduction
            if (kotlin.math.abs(sample.toInt()) < threshold) {
                result[i] = 0
            } else {
                result[i] = sample
            }
        }
        
        return result
    }



    /**
     * Reset audio effects state (useful after wake word detection)
     */
    suspend fun reset(): Boolean = mutex.withLock {
        return try {
            // Re-enable audio effects if they were disabled
            noiseSuppressor?.enabled = true
            acousticEchoCanceler?.enabled = true
            Log.d(TAG, "Audio effects state reset")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset audio effects", e)
            false
        }
    }

    /**
     * Clean up resources
     */
    suspend fun cleanup() = mutex.withLock {
        try {
            noiseSuppressor?.release()
            acousticEchoCanceler?.release()
            noiseSuppressor = null
            acousticEchoCanceler = null
            isInitialized = false
            Log.d(TAG, "Audio effects cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Check if audio effects are ready to process frames
     */
    fun isReady(): Boolean = isInitialized && (noiseSuppressor != null || acousticEchoCanceler != null)
}