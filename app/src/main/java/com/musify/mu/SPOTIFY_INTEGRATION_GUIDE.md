# Spotify-Style Local Files Integration Guide

This guide explains how to integrate the new Spotify-style local files system that was implemented based on the Spotify APK decompilation analysis.

## What Was Implemented

### 1. Core Architecture Changes

#### Deleted Old Files:
- `MediaStoreReader.kt` - Old scanning approach
- `SimpleBackgroundDataManager.kt` - Old data management
- `OnDemandArtworkLoader.kt` - Old artwork loading
- `SmartArtwork.kt`, `Artwork.kt`, `SimpleArtwork.kt` - Old UI components

#### New Spotify-Style Components:
- **`LocalFilesService`** - Main orchestrator (replaces old data manager)
- **`MediaStoreReader`** - Spotify's exact MediaStore querying approach
- **`OpenedAudioFiles`** - Manages permanent URIs in SharedPreferences
- **`LocalFilesPermissionInteractor`** - Modern permission handling
- **`SpotifyStyleArtworkLoader`** - ID3/APIC artwork extraction on-demand
- **`SpotifyStyleDataManager`** - Bridge between new system and existing UI

### 2. Key Differences from Old System

#### Old Approach:
- Pre-scanned and extracted all artwork during library scan
- Stored artwork URIs in Track entities
- Heavy upfront processing

#### New Spotify Approach:
- **No upfront artwork extraction** - artwork loaded on-demand when needed
- **ID3/APIC extraction** - uses MediaMetadataRetriever.getEmbeddedPicture()
- **Protobuf-like data structures** - efficient serialization
- **ContentObserver** - real-time MediaStore change detection
- **SharedPreferences persistence** - for permanently opened files

## Integration Steps

### 1. Update Your Application Class

```kotlin
class MusifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Spotify-style artwork loader
        SpotifyStyleArtworkLoader.initialize(this)
    }
}
```

### 2. Replace Data Manager Usage

**Old code:**
```kotlin
val dataManager = SimpleBackgroundDataManager.get(context, db)
dataManager.initializeOnAppLaunch()
val tracks = dataManager.getAllTracks()
```

**New code:**
```kotlin
val dataManager = SpotifyStyleDataManager.getInstance(context, db)
dataManager.initializeOnAppLaunch()
val tracks = dataManager.getAllTracks()
```

### 3. Update UI Components

**Old artwork usage:**
```kotlin
SmartArtwork(
    artworkUri = track.artUri,
    mediaUri = track.mediaId
)
```

**New artwork usage:**
```kotlin
SpotifyStyleArtwork(
    trackUri = track.mediaId
)

// Or use specific components:
TrackArtwork(trackUri = track.mediaId)
AlbumArtwork(albumId = album.id, firstTrackUri = firstTrack.mediaId)
CompactArtwork(trackUri = currentTrack.mediaId) // For mini player
```

### 4. Handle Permissions

```kotlin
class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        lifecycleScope.launch {
            dataManager.onPermissionsChanged()
        }
    }
    
    private fun requestPermissions() {
        val permissionInteractor = LocalFilesPermissionInteractor.getInstance(this)
        val required = permissionInteractor.getRequiredPermissions()
        permissionLauncher.launch(required.toTypedArray())
    }
}
```

### 5. Observe Scanning State

```kotlin
@Composable
fun MusicLibraryScreen() {
    val dataManager = SpotifyStyleDataManager.getInstance(LocalContext.current, db)
    val tracks by dataManager.tracks.collectAsState()
    val loadingState by dataManager.loadingState.collectAsState()
    
    when (loadingState) {
        is LoadingState.Idle -> { /* Show initial state */ }
        is LoadingState.Loading -> { /* Show loading with message */ }
        is LoadingState.Completed -> { /* Show tracks */ }
        is LoadingState.Error -> { /* Show error */ }
    }
}
```

## Key Benefits

### 1. Performance
- **Faster app startup** - no upfront artwork extraction
- **Lower memory usage** - artwork loaded only when needed
- **Efficient caching** - both memory and disk caching

### 2. Accuracy
- **Real-time updates** - ContentObserver detects MediaStore changes
- **Better filtering** - Spotify's exact MediaStore query logic
- **Proper permission handling** - Android 13+ granular permissions

### 3. Reliability
- **Negative caching** - avoids re-attempting failed artwork extractions
- **Error handling** - graceful degradation when artwork unavailable
- **State management** - proper loading states and error reporting

## Advanced Features

### 1. Adding Custom Files
```kotlin
// Add file from SAF picker
dataManager.addPermanentFile(selectedUri)

// Files persist across app restarts via SharedPreferences
```

### 2. Prefetching Artwork
```kotlin
// Automatically prefetches artwork for visible tracks
SpotifyStyleArtworkLoader.prefetchArtwork(trackUris)
```

### 3. Cache Management
```kotlin
// Get cache statistics
val stats = dataManager.getCacheStats()

// Clear caches
dataManager.clearCache()
```

## Troubleshooting

### 1. No Artwork Showing
- Check that `SpotifyStyleArtworkLoader.initialize()` was called
- Verify track URIs are valid content:// or file:// URIs
- Check if tracks actually have embedded artwork

### 2. Permission Issues
- Use `LocalFilesPermissionInteractor` to check permission state
- Handle Android 13+ READ_MEDIA_AUDIO vs older READ_EXTERNAL_STORAGE

### 3. Performance Issues
- Reduce prefetch batch sizes
- Check artwork cache hit rates
- Monitor memory usage with cache stats

## Migration Checklist

- [ ] Initialize `SpotifyStyleArtworkLoader` in Application class
- [ ] Replace `SimpleBackgroundDataManager` with `SpotifyStyleDataManager`
- [ ] Update all artwork components to use `SpotifyStyleArtwork`
- [ ] Implement proper permission handling with `LocalFilesPermissionInteractor`
- [ ] Test with various audio file types and embedded artwork
- [ ] Verify real-time updates work when files are added/removed
- [ ] Test on different Android versions (especially 13+)

This new system follows Spotify's exact architecture and should provide much better performance and reliability compared to the previous implementation.

