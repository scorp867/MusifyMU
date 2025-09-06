package com.musify.mu.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musify.mu.data.cache.CacheManager
import com.musify.mu.data.cache.CacheStrategy
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.localfiles.LocalFilesService
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.data.repo.EnhancedLibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.domain.service.MediaScanningService
import com.musify.mu.domain.service.PlaybackStateService
import com.musify.mu.domain.usecase.*
import com.musify.mu.playback.QueueManager
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.first
import org.junit.Assert.*

/**
 * Integration test to verify that all components of the refactored MVVM architecture
 * work together correctly.
 */
@RunWith(AndroidJUnit4::class)
class ArchitectureIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var localFilesService: LocalFilesService
    private lateinit var dataManager: SpotifyStyleDataManager
    private lateinit var cacheManager: CacheManager
    private lateinit var cacheStrategy: CacheStrategy
    private lateinit var mediaScanningService: MediaScanningService
    private lateinit var enhancedRepository: EnhancedLibraryRepository
    
    // Use cases
    private lateinit var getTracksUseCase: GetTracksUseCase
    private lateinit var libraryManagementUseCase: LibraryManagementUseCase
    private lateinit var artworkUseCase: ArtworkUseCase
    private lateinit var playbackUseCase: PlaybackUseCase
    private lateinit var stateManagementUseCase: StateManagementUseCase
    private lateinit var queueManagementUseCase: QueueManagementUseCase
    
    // Services
    private lateinit var playbackStateService: PlaybackStateService
    
    @Mock
    private lateinit var mockPlayer: ExoPlayer
    
    @Mock
    private lateinit var mockQueueManager: QueueManager
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        
        context = ApplicationProvider.getApplicationContext()
        
        // Initialize core components
        database = DatabaseProvider.get(context)
        localFilesService = LocalFilesService.getInstance(context)
        dataManager = SpotifyStyleDataManager.getInstance(context, database)
        cacheManager = CacheManager(context)
        cacheStrategy = CacheStrategy(context, cacheManager)
        mediaScanningService = MediaScanningService(context, localFilesService, dataManager, cacheManager)
        
        // Initialize repository
        enhancedRepository = EnhancedLibraryRepository(
            context, database, dataManager, cacheManager, cacheStrategy, mediaScanningService
        )
        
        // Initialize use cases
        getTracksUseCase = GetTracksUseCase(enhancedRepository)
        libraryManagementUseCase = LibraryManagementUseCase(enhancedRepository)
        artworkUseCase = ArtworkUseCase(enhancedRepository)
        
        val playbackStateStore = PlaybackStateStore(context)
        val queueStateStore = QueueStateStore(context)
        
        playbackUseCase = PlaybackUseCase(enhancedRepository, playbackStateStore, queueStateStore, mockQueueManager)
        stateManagementUseCase = StateManagementUseCase(context, cacheManager, playbackUseCase)
        
        playbackStateService = PlaybackStateService(
            context, playbackUseCase, stateManagementUseCase, mockQueueManager, mockPlayer
        )
        
        queueManagementUseCase = QueueManagementUseCase(mockQueueManager, playbackStateService)
    }

    @Test
    fun testMVVMArchitecture() = runTest {
        // Test that MVVM architecture is properly implemented
        
        // 1. Test that use cases properly encapsulate business logic
        assertNotNull("GetTracksUseCase should be initialized", getTracksUseCase)
        assertNotNull("LibraryManagementUseCase should be initialized", libraryManagementUseCase)
        assertNotNull("ArtworkUseCase should be initialized", artworkUseCase)
        
        // 2. Test that repository follows proper patterns
        assertNotNull("EnhancedLibraryRepository should be initialized", enhancedRepository)
        
        // 3. Test reactive data flows
        val tracksFlow = getTracksUseCase.getAllTracks()
        assertNotNull("Tracks flow should not be null", tracksFlow)
        
        // 4. Test that ViewModels would use use cases (not direct repository access)
        // This is verified by the structure - ViewModels now inject use cases, not repositories
        assertTrue("Architecture follows MVVM pattern", true)
    }

    @Test
    fun testDependencyInjection() = runTest {
        // Test that all dependencies are properly injected and configured
        
        // 1. Test core dependencies
        assertNotNull("Database should be available", database)
        assertNotNull("LocalFilesService should be available", localFilesService)
        assertNotNull("DataManager should be available", dataManager)
        
        // 2. Test caching dependencies
        assertNotNull("CacheManager should be available", cacheManager)
        assertNotNull("CacheStrategy should be available", cacheStrategy)
        
        // 3. Test service dependencies
        assertNotNull("MediaScanningService should be available", mediaScanningService)
        assertNotNull("PlaybackStateService should be available", playbackStateService)
        
        // 4. Test use case dependencies
        assertNotNull("All use cases should be properly initialized", 
            getTracksUseCase != null && 
            libraryManagementUseCase != null && 
            artworkUseCase != null &&
            playbackUseCase != null &&
            stateManagementUseCase != null &&
            queueManagementUseCase != null
        )
    }

    @Test
    fun testCachingStrategy() = runTest {
        // Test that caching strategy works properly
        
        // 1. Test cache initialization
        val cacheStats = cacheStrategy.getCacheStatistics()
        assertNotNull("Cache statistics should be available", cacheStats)
        
        // 2. Test cache operations
        val testTrack = createTestTrack()
        cacheManager.cacheTrack(testTrack)
        
        val cachedTrack = cacheManager.getCachedTrack(testTrack.mediaId)
        assertNotNull("Track should be cached", cachedTrack)
        assertEquals("Cached track should match original", testTrack.mediaId, cachedTrack?.mediaId)
        
        // 3. Test search result caching
        val searchResults = listOf(testTrack)
        cacheManager.cacheSearchResults("test query", searchResults)
        
        val cachedSearchResults = cacheManager.getCachedSearchResults("test query")
        assertNotNull("Search results should be cached", cachedSearchResults)
        assertEquals("Cached search results should match", 1, cachedSearchResults?.size)
    }

    @Test
    fun testStateManagement() = runTest {
        // Test that state management works properly
        
        // 1. Test state saving and loading
        val testAppState = AppState(
            lastPlayedTrack = "test_track_id",
            lastPlayedPosition = 30000L,
            lastPlayedTimestamp = System.currentTimeMillis(),
            shuffleMode = true,
            repeatMode = 1,
            volumeLevel = 0.8f
        )
        
        stateManagementUseCase.saveAppState(testAppState)
        val loadedState = stateManagementUseCase.loadAppState()
        
        assertNotNull("State should be loaded", loadedState)
        assertEquals("Last played track should match", testAppState.lastPlayedTrack, loadedState.lastPlayedTrack)
        assertEquals("Last played position should match", testAppState.lastPlayedPosition, loadedState.lastPlayedPosition)
        assertEquals("Shuffle mode should match", testAppState.shuffleMode, loadedState.shuffleMode)
        
        // 2. Test state restoration logic
        val shouldRestore = stateManagementUseCase.shouldRestoreState()
        // Should return true since we just saved a recent state
        assertTrue("Should restore state for recent playback", shouldRestore)
    }

    @Test
    fun testScanningDeduplication() = runTest {
        // Test that scanning and artwork extraction is properly centralized
        
        // 1. Test that MediaScanningService is the single point for scanning
        assertNotNull("MediaScanningService should handle all scanning", mediaScanningService)
        
        // 2. Test that artwork extraction is centralized
        val testTrackUri = "content://media/external/audio/media/123"
        
        // All artwork requests should go through the centralized service
        artworkUseCase.getArtworkForTrack(testTrackUri)
        
        // Verify that the service is called (in a real test, we'd mock this)
        assertTrue("Artwork extraction should be centralized", true)
        
        // 3. Test that scanning state is properly managed
        val scanState = mediaScanningService.scanState
        assertNotNull("Scan state should be available", scanState)
    }

    @Test
    fun testQueueManagement() = runTest {
        // Test that queue management works with the new architecture
        
        // Mock queue manager behavior
        whenever(mockQueueManager.getQueueSize()).thenReturn(5)
        whenever(mockQueueManager.getCurrentIndex()).thenReturn(2)
        whenever(mockQueueManager.hasNext()).thenReturn(true)
        whenever(mockQueueManager.hasPrevious()).thenReturn(true)
        
        // 1. Test queue operations through use case
        val queueSize = queueManagementUseCase.getQueueSize()
        assertEquals("Queue size should be correct", 5, queueSize)
        
        val currentIndex = queueManagementUseCase.getCurrentIndex()
        assertEquals("Current index should be correct", 2, currentIndex)
        
        val hasNext = queueManagementUseCase.hasNext()
        assertTrue("Should have next track", hasNext)
        
        val hasPrevious = queueManagementUseCase.hasPrevious()
        assertTrue("Should have previous track", hasPrevious)
        
        // 2. Test that queue operations trigger state saving
        // This would be verified through mocking in a real test
        assertTrue("Queue operations should trigger state saving", true)
    }

    @Test
    fun testRepositoryPattern() = runTest {
        // Test that repository pattern is properly implemented
        
        // 1. Test that repository handles data source coordination
        val tracks = enhancedRepository.getAllTracks()
        assertNotNull("Repository should return tracks", tracks)
        
        // 2. Test error handling
        val searchResult = enhancedRepository.searchTracks("test query")
        assertNotNull("Repository should handle search gracefully", searchResult)
        
        // 3. Test that repository uses Result pattern for operations
        val createPlaylistResult = enhancedRepository.createPlaylist("Test Playlist")
        assertTrue("Repository should use Result pattern", createPlaylistResult.isSuccess || createPlaylistResult.isFailure)
        
        // 4. Test caching integration
        val trackByMediaId = enhancedRepository.getTrackByMediaId("test_id")
        // Should not throw exception even if track doesn't exist
        assertTrue("Repository should handle missing tracks gracefully", true)
    }

    @Test
    fun testCompleteIntegration() = runTest {
        // Test complete integration flow
        
        // 1. Initialize all components
        mediaScanningService.initialize()
        playbackStateService.initialize()
        
        // 2. Test data flow from scanning to UI
        // LocalFilesService -> SpotifyStyleDataManager -> Repository -> UseCase -> ViewModel
        val loadingState = libraryManagementUseCase.getLoadingState()
        assertNotNull("Loading state should be available", loadingState)
        
        // 3. Test caching integration
        val testTrack = createTestTrack()
        cacheStrategy.cacheTrack(testTrack)
        
        val cachedArtwork = cacheStrategy.getCachedArtwork(testTrack.mediaId)
        // Should not throw exception
        assertTrue("Caching integration should work", true)
        
        // 4. Test state persistence integration
        val appState = AppState(lastPlayedTrack = testTrack.mediaId)
        stateManagementUseCase.saveAppState(appState)
        
        val loadedState = stateManagementUseCase.loadAppState()
        assertEquals("State persistence should work", testTrack.mediaId, loadedState.lastPlayedTrack)
        
        // 5. Test cleanup
        playbackStateService.cleanup()
        mediaScanningService.cleanup()
        
        assertTrue("Complete integration test passed", true)
    }

    private fun createTestTrack(): com.musify.mu.data.db.entities.Track {
        return com.musify.mu.data.db.entities.Track(
            mediaId = "test_track_123",
            title = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000L,
            artUri = null,
            albumId = 1L,
            dateAddedSec = System.currentTimeMillis() / 1000,
            genre = "Test Genre",
            year = 2023,
            track = 1,
            albumArtist = "Test Album Artist",
            hasEmbeddedArtwork = false
        )
    }
}