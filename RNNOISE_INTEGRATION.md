# RNNoise Integration for Voice Commands

This document describes the integration of RNNoise for real-time audio noise suppression in the voice command system.

## Overview

RNNoise is integrated to provide real-time noise suppression for voice commands, improving wake word detection and command recognition accuracy in noisy environments.

## Architecture

### Flow Diagram
```
Microphone → AudioRecord → RNNoise → Vosk → Command Processing
    ↓           ↓           ↓        ↓           ↓
   Raw Audio → 20ms Frames → Cleaned → Speech → Execute
```

### Key Components

1. **RNNoiseProcessor** (`RNNoiseProcessor.kt`)
   - Handles RNNoise initialization and cleanup
   - Processes audio frames in real-time
   - Manages sample rate conversion (16kHz ↔ 48kHz)

2. **AudioFrameProcessor** (`AudioFrameProcessor.kt`)
   - Utility class for audio frame processing
   - Handles frame conversion and optimization
   - Provides fallback when RNNoise is unavailable

3. **WakeWordService** (`WakeWordService.kt`)
   - Integrates RNNoise into the audio processing pipeline
   - Manages real-time frame streaming
   - Coordinates wake word detection and command processing

## Implementation Details

### Real-Time Processing
- **Frame Size**: 320 samples (20ms at 16kHz)
- **Sample Rate**: 16kHz input, upsampled to 48kHz for RNNoise, downsampled back to 16kHz for Vosk
- **Latency**: Minimal - each frame is processed immediately without buffering

### Audio Pipeline
1. **Raw Audio Capture**: 16kHz mono PCM 16-bit from microphone
2. **Frame Segmentation**: 20ms chunks for real-time processing
3. **RNNoise Processing**: Upsample → Noise Suppression → Downsample
4. **Vosk Integration**: Cleaned audio sent directly to speech recognition
5. **Command Execution**: Processed commands trigger media controls

### Performance Optimizations
- **Frame Buffering**: Pre-allocated buffers to minimize GC pressure
- **Mutex Protection**: Thread-safe audio processing with minimal contention
- **Fallback Handling**: Graceful degradation when RNNoise is unavailable
- **State Management**: Proper cleanup and reset for optimal performance

## Dependencies

```kotlin
implementation("de.maxhenkel.rnnoise4j:rnnoise4j:2.1.2")
```

## Usage

### Initialization
```kotlin
val rnnoiseProcessor = RNNoiseProcessor()
val success = rnnoiseProcessor.initialize()
```

### Frame Processing
```kotlin
val cleanedFrame = AudioFrameProcessor.processAudioFrame(
    rawFrame, 
    frameLength, 
    rnnoiseProcessor
)
```

### Cleanup
```kotlin
rnnoiseProcessor.cleanup()
```

## Configuration

### Frame Sizes
- **Optimal**: 160 samples (10ms at 16kHz)
- **Current**: 320 samples (20ms at 16kHz)
- **Maximum**: 480 samples (30ms at 16kHz)

### Sample Rates
- **Input**: 16kHz (Vosk requirement)
- **RNNoise**: 48kHz (optimal performance)
- **Output**: 16kHz (Vosk compatibility)

## Benefits

1. **Improved Wake Word Detection**: Better accuracy in noisy environments
2. **Enhanced Command Recognition**: Cleaner audio for Vosk processing
3. **Real-Time Performance**: No buffering delays
4. **Robust Fallback**: Continues working even if RNNoise fails
5. **Low Latency**: Minimal processing overhead

## Troubleshooting

### Common Issues
1. **RNNoise Initialization Failed**
   - Check if the library is properly included
   - Verify device compatibility
   - Check logcat for detailed error messages

2. **High CPU Usage**
   - Ensure frame sizes are optimal
   - Check if unnecessary upsampling is occurring
   - Monitor audio processing thread

3. **Audio Quality Issues**
   - Verify sample rate conversion is working correctly
   - Check if RNNoise is actually processing frames
   - Ensure proper cleanup between sessions

### Debug Logging
Enable verbose logging to monitor frame processing:
```kotlin
Log.v("AudioFrameProcessor", "Frame processed with RNNoise: ${rawFrame.size} -> ${cleanedFrame.size}")
```

## Future Enhancements

1. **Adaptive Frame Sizing**: Dynamic frame size based on audio characteristics
2. **Quality Metrics**: Real-time audio quality assessment
3. **Multiple Noise Models**: Environment-specific noise suppression
4. **GPU Acceleration**: Offload processing to GPU when available
5. **Streaming Optimization**: Further reduce latency with advanced buffering

## References

- [RNNoise4J Library](https://github.com/MaxHenkel/rnnoise4j)
- [RNNoise Paper](https://arxiv.org/abs/1709.08243)
- [Vosk Speech Recognition](https://alphacephei.com/vosk/)
- [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)