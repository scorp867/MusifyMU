# Audio Noise Suppression Integration for Voice Commands

This document describes the integration of Android's built-in audio noise suppression for real-time audio processing in the voice command system.

## Overview

Android's built-in audio noise suppression is integrated to provide real-time noise reduction for voice commands, improving wake word detection and command recognition accuracy in noisy environments.

## Architecture

### Flow Diagram
```
Microphone → AudioRecord → Audio Effects → Vosk → Command Processing
    ↓           ↓           ↓        ↓           ↓
   Raw Audio → 20ms Frames → Cleaned → Speech → Execute
```

### Key Components

1. **AudioNoiseProcessor** (`AudioNoiseProcessor.kt`)
   - Handles Android audio effects initialization and cleanup
   - Processes audio frames in real-time
   - Uses NoiseSuppressor and AcousticEchoCanceler

2. **AudioFrameProcessor** (`AudioFrameProcessor.kt`)
   - Utility class for audio frame processing
   - Handles frame conversion and optimization
   - Provides fallback when audio effects are unavailable

3. **WakeWordService** (`WakeWordService.kt`)
   - Integrates audio noise suppression into the audio processing pipeline
   - Manages real-time frame streaming
   - Coordinates wake word detection and command processing

## Implementation Details

### Real-Time Processing
- **Frame Size**: 320 samples (20ms at 16kHz)
- **Sample Rate**: 16kHz input and output (no conversion needed)
- **Latency**: Minimal - each frame is processed immediately without buffering

### Audio Pipeline
1. **Raw Audio Capture**: 16kHz mono PCM 16-bit from microphone
2. **Frame Segmentation**: 20ms chunks for real-time processing
3. **Audio Effects Processing**: Noise Suppression + Echo Cancellation
4. **Vosk Integration**: Cleaned audio sent directly to speech recognition
5. **Command Execution**: Processed commands trigger media controls

### Performance Optimizations
- **Frame Buffering**: Pre-allocated buffers to minimize GC pressure
- **Mutex Protection**: Thread-safe audio processing with minimal contention
- **Fallback Handling**: Graceful degradation when audio effects are unavailable
- **State Management**: Proper cleanup and reset for optimal performance

## Dependencies

```kotlin
// Uses Android's built-in audio effects - no external dependencies required
// NoiseSuppressor and AcousticEchoCanceler are part of android.media.audiofx package
```

## Usage

### Initialization
```kotlin
val audioNoiseProcessor = AudioNoiseProcessor()
val success = audioNoiseProcessor.initialize()
```

### Frame Processing
```kotlin
val cleanedFrame = AudioFrameProcessor.processAudioFrame(
    rawFrame, 
    frameLength, 
    audioNoiseProcessor
)
```

### Cleanup
```kotlin
audioNoiseProcessor.cleanup()
```

## Configuration

### Frame Sizes
- **Optimal**: 160 samples (10ms at 16kHz)
- **Current**: 320 samples (20ms at 16kHz)
- **Maximum**: 480 samples (30ms at 16kHz)

### Sample Rates
- **Input**: 16kHz (Vosk requirement)
- **Processing**: 16kHz (native Android audio effects)
- **Output**: 16kHz (Vosk compatibility)

## Benefits

1. **Improved Wake Word Detection**: Better accuracy in noisy environments
2. **Enhanced Command Recognition**: Cleaner audio for Vosk processing
3. **Real-Time Performance**: No buffering delays
4. **Robust Fallback**: Continues working even if audio effects fail
5. **Low Latency**: Minimal processing overhead
6. **Native Android**: No external dependencies required
7. **Device Optimized**: Uses hardware-accelerated audio processing when available

## Troubleshooting

### Common Issues
1. **Audio Effects Initialization Failed**
   - Check if NoiseSuppressor and AcousticEchoCanceler are available on the device
   - Verify device compatibility with audio effects
   - Check logcat for detailed error messages

2. **High CPU Usage**
   - Ensure frame sizes are optimal
   - Check if audio effects are hardware-accelerated
   - Monitor audio processing thread

3. **Audio Quality Issues**
   - Verify audio effects are properly enabled
   - Check if noise suppression is actually processing frames
   - Ensure proper cleanup between sessions

### Debug Logging
Enable verbose logging to monitor frame processing:
```kotlin
Log.v("AudioFrameProcessor", "Frame processed with audio noise suppression: ${rawFrame.size} -> ${cleanedFrame.size}")
```

## Future Enhancements

1. **Adaptive Frame Sizing**: Dynamic frame size based on audio characteristics
2. **Quality Metrics**: Real-time audio quality assessment
3. **Multiple Noise Models**: Environment-specific noise suppression
4. **Hardware Acceleration**: Leverage device-specific audio processing capabilities
5. **Streaming Optimization**: Further reduce latency with advanced buffering
6. **Custom Audio Effects**: Implement additional noise reduction algorithms

## References

- [Android NoiseSuppressor](https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor)
- [Android AcousticEchoCanceler](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler)
- [Android Audio Effects](https://developer.android.com/guide/topics/media/audiofx)
- [Vosk Speech Recognition](https://alphacephei.com/vosk/)
- [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)