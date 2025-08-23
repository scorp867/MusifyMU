package com.musify.mu.voice

import android.util.Log
import de.maxhenkel.rnnoise4j.Denoiser as RNNoise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RNNoise processor for real-time audio noise suppression.
 * Processes audio frames in real-time and feeds cleaned audio to Vosk.
 */
class RNNoiseProcessor {
    companion object {
        private const val TAG = "RNNoiseProcessor"
        private const val FRAME_SIZE = 480 // RNNoise expects 480 samples per frame (10ms at 48kHz)
        private const val SAMPLE_RATE = 16000 // Vosk sample rate
        private const val SCALE_FACTOR = 3 // 48kHz / 16kHz = 3
    }

    private var rnnoise: RNNoise? = null
    private val mutex = Mutex()
    private var isInitialized = false

    // Buffers for audio processing
    private val inputBuffer = FloatArray(FRAME_SIZE)
    private val outputBuffer = FloatArray(FRAME_SIZE)
    private val tempBuffer = ShortArray(FRAME_SIZE)
    private val resampledBuffer = ShortArray(FRAME_SIZE / SCALE_FACTOR)

    /**
     * Initialize RNNoise processor
     */
    suspend fun initialize(): Boolean = mutex.withLock {
        return try {
            rnnoise = RNNoise()
            isInitialized = true
            Log.d(TAG, "RNNoise initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RNNoise", e)
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
        if (!isInitialized || rnnoise == null) {
            Log.w(TAG, "RNNoise not initialized, skipping frame")
            return null
        }

        return try {
            // Convert input frame to float and upsample to 48kHz for RNNoise
            val upsampledFrame = upsampleFrame(inputFrame, frameLength)
            
            // Process with RNNoise
            val cleanedFrame = rnnoise!!.processFrame(upsampledFrame)
            
            // Downsample back to 16kHz for Vosk
            val downsampledFrame = downsampleFrame(cleanedFrame)
            
            Log.v(TAG, "Processed frame: $frameLength -> ${downsampledFrame.size} samples")
            downsampledFrame
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame with RNNoise", e)
            null
        }
    }

    /**
     * Upsample 16kHz audio to 48kHz for RNNoise processing
     */
    private fun upsampleFrame(input: ShortArray, length: Int): FloatArray {
        // Simple linear interpolation upsampling
        for (i in 0 until FRAME_SIZE) {
            val srcIndex = i / SCALE_FACTOR
            val srcIndexNext = (srcIndex + 1).coerceAtMost(length - 1)
            
            val weight = (i % SCALE_FACTOR).toFloat() / SCALE_FACTOR
            val sample1 = input[srcIndex].toFloat() / 32768.0f
            val sample2 = input[srcIndexNext].toFloat() / 32768.0f
            
            inputBuffer[i] = sample1 * (1.0f - weight) + sample2 * weight
        }
        return inputBuffer
    }

    /**
     * Downsample 48kHz audio back to 16kHz for Vosk
     */
    private fun downsampleFrame(input: FloatArray): ShortArray {
        // Simple decimation downsampling
        for (i in 0 until resampledBuffer.size) {
            val srcIndex = i * SCALE_FACTOR
            val sample = input[srcIndex].coerceIn(-1.0f, 1.0f)
            resampledBuffer[i] = (sample * 32767.0f).toInt().toShort()
        }
        return resampledBuffer
    }



    /**
     * Reset RNNoise state (useful after wake word detection)
     */
    suspend fun reset(): Boolean = mutex.withLock {
        return try {
            val rnnoiseInstance = rnnoise
            if (rnnoiseInstance != null) {
                rnnoiseInstance.reset()
                Log.d(TAG, "RNNoise state reset")
                true
            } else {
                Log.w(TAG, "RNNoise instance is null, cannot reset")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset RNNoise", e)
            false
        }
    }

    /**
     * Clean up resources
     */
    suspend fun cleanup() = mutex.withLock {
        try {
            val rnnoiseInstance = rnnoise
            if (rnnoiseInstance != null) {
                rnnoiseInstance.close()
                Log.d(TAG, "RNNoise cleaned up")
            }
            rnnoise = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Check if RNNoise is ready to process frames
     */
    fun isReady(): Boolean = isInitialized && rnnoise != null
}