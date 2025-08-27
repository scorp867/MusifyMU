# WebRTC and Audio Issues Fixes Summary

## 1. WebRTC Implementation Fix

### Issue
The app was using direct AudioRecord capture instead of WebRTC for audio processing, even though a WebRtcCapture class was available.

### Solution
- Modified `WakeWordService` to use `WebRtcCapture` for audio processing with WebRTC's audio enhancements (AEC/NS/AGC/HPF)
- Added fallback mechanism to direct AudioRecord if WebRTC fails
- WebRTC processes audio and feeds 20ms frames at 16kHz mono to Vosk for wake word and command detection

### Changes in WakeWordService.kt:
- Replaced direct AudioRecord initialization with WebRtcCapture
- Added `processWakeWordFrame()` method to handle wake word detection
- Added `fallbackToDirectAudioRecord()` method as a backup if WebRTC fails
- WebRTC onFrame callback handles both wake word and command processing

## 2. Bluetooth Communication Mode Bug Fix

### Issue
When manually disconnecting communication mode from Bluetooth headset, music would play from the phone's speaker instead of ending communication mode properly.

### Solution
- Enhanced `HeadphoneDetector.restoreDefaultAudioRouting()` to properly restore audio mode to NORMAL
- Added proper audio mode handling in headphone disconnect monitoring
- Ensured that audio mode is reset before stopping Bluetooth SCO

### Changes in HeadphoneDetector.kt:
- Updated `restoreDefaultAudioRouting()` to always restore MODE_NORMAL first
- Added proper SCO connection cleanup
- Set communication mode only when needed for Bluetooth microphone

### Changes in WakeWordService.kt:
- Enhanced headphone monitoring to properly restore audio routing on disconnect
- Added re-establishment of routing when headphones reconnect
- Ensured audio mode is restored to NORMAL when headphones disconnect

## 3. Notification Dismissal Behavior Fix

### Issue
Notification banner was easily dismissed even during playback, and should be non-dismissable when music is playing.

### Solution
- Created custom `MusifyMediaNotificationProvider` that implements MediaNotification.Provider
- Made notification non-dismissable (ongoing) during playback
- Allowed dismissal only when playback is paused/stopped

### New file: MusifyMediaNotificationProvider.kt
- Implements custom notification with MediaStyle
- Sets `setOngoing(true)` and removes delete intent during playback
- Adds proper playback controls (prev, play/pause, next)
- Handles notification channel creation

## 4. Notification Delay on App Swipe Fix

### Issue
When swiping app from recents, notification took 5 seconds to dismiss instead of dismissing immediately.

### Solution
- Modified `PlayerService.onTaskRemoved()` to immediately stop player and release media session
- Added immediate notification cancellation through NotificationManager
- Ensures all cleanup happens synchronously before service stops

### Changes in PlayerService.kt:
- Enhanced `onTaskRemoved()` to immediately stop player and clear media items
- Release media session immediately to trigger notification removal
- Added explicit notification cancellation via NotificationManager
- Set custom notification provider in onCreate()

### Changes in AndroidManifest.xml:
- Added NotificationDismissReceiver for handling notification dismissal events

## Testing Instructions

1. **WebRTC Audio Processing**:
   - Start the wake word service
   - Check logs for "WebRTC audio processing" messages
   - If WebRTC fails, should see fallback to direct AudioRecord

2. **Bluetooth Communication Mode**:
   - Connect Bluetooth headset
   - Start wake word listening
   - Manually disconnect communication mode from Bluetooth settings
   - Music should NOT play from speaker
   - Audio mode should return to NORMAL

3. **Notification Dismissal**:
   - Play music
   - Try to swipe away notification - should not dismiss
   - Pause music
   - Notification should now be dismissable

4. **App Swipe from Recents**:
   - Play music with notification showing
   - Swipe app from recents
   - Notification should disappear immediately (not after 5 seconds)

## Key Technical Details

- WebRTC capture runs at 16kHz mono with 20ms frames (320 samples)
- Audio mode management ensures proper routing between communication and normal modes
- Custom notification provider gives full control over notification lifecycle
- Immediate cleanup in onTaskRemoved prevents notification delays