# Voice Controls - Gym Mode

## Overview
Musify MU now includes voice controls that allow you to control music playback using voice commands through your headset microphone. This feature is specifically designed for gym use where your hands might be busy.

## Features
- **Headset Microphone Only**: Uses only the headset microphone (wired or Bluetooth), never the device's built-in microphone
- **Automatic Detection**: Automatically detects when headphones are connected
- **Smart Audio Routing**: Forces audio routing to headset for optimal voice recognition
- **Voice Commands**: Supports all major playback controls via voice

## How to Enable
1. Connect your headphones (wired or Bluetooth)
2. Open the Now Playing screen
3. Tap the "More" button (⋮) in the top right corner
4. Toggle "Gym Mode" to ON

## Voice Commands

### Basic Playback
- **Play/Resume**: Say "play", "resume", "start", or "go"
- **Pause/Stop**: Say "pause", "stop", "halt", or "freeze"
- **Next Track**: Say "next", "skip", "forward", or "advance"
- **Previous Track**: Say "previous", "back", "rewind", or "last"

### Playback Modes
- **Shuffle On**: Say "shuffle on" or "shuffle enable"
- **Shuffle Off**: Say "shuffle off" or "shuffle disable"
- **Repeat One**: Say "repeat one"
- **Repeat All**: Say "repeat all"
- **Repeat Off**: Say "repeat off" or "repeat disable"

### Volume Control
- **Volume Up**: Say "volume up" or "volume increase"
- **Volume Down**: Say "volume down" or "volume decrease"
- **Mute**: Say "mute"

## Technical Details

### Headphone Detection
- Automatically detects wired headphones (3.5mm jack, USB-C)
- Automatically detects Bluetooth headphones
- Monitors connection status in real-time
- Gym Mode automatically disables if headphones disconnect

### Audio Routing
- Forces Bluetooth SCO (Synchronous Connection-Oriented) audio for Bluetooth headsets
- Routes audio to wired headset when connected
- Ensures voice recognition uses headset microphone only

### Permissions Required
- `RECORD_AUDIO`: For voice recognition
- `BLUETOOTH`: For Bluetooth headset detection
- `BLUETOOTH_CONNECT`: For Bluetooth audio routing
- `BLUETOOTH_ADMIN`: For Bluetooth SCO audio management
- `MODIFY_AUDIO_SETTINGS`: For audio routing control

## Troubleshooting

### Gym Mode Won't Enable
- Ensure headphones are properly connected
- Check that microphone permissions are granted
- Try disconnecting and reconnecting headphones

### Voice Commands Not Working
- Ensure Gym Mode is enabled (toggle is ON)
- Speak clearly and at normal volume
- Check that headset microphone is working
- Try saying commands more slowly

### Bluetooth Issues
- Ensure Bluetooth is enabled on your device
- Try pairing the headset again
- Check if the headset supports microphone input

## Safety Notes
- Voice controls only work when headphones are connected
- Device microphone is never used for voice recognition
- Commands are processed locally for privacy
- Audio routing automatically reverts when Gym Mode is disabled

## Supported Languages
Currently supports the device's default language. Voice commands work best in English but may work in other languages depending on the device's speech recognition capabilities.

