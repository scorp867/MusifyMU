package com.musify.mu.domain.usecase

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.musify.mu.data.cache.CacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appStateDataStore by preferencesDataStore("app_state")

@Singleton
class StateManagementUseCase @Inject constructor(
    private val context: Context,
    private val cacheManager: CacheManager,
    private val playbackUseCase: PlaybackUseCase
) {
    
    private val dataStore = context.appStateDataStore
    
    companion object {
        private val LAST_PLAYED_TRACK = stringPreferencesKey("last_played_track")
        private val LAST_PLAYED_POSITION = longPreferencesKey("last_played_position")
        private val LAST_PLAYED_TIMESTAMP = longPreferencesKey("last_played_timestamp")
        private val SHUFFLE_MODE = booleanPreferencesKey("shuffle_mode")
        private val REPEAT_MODE = intPreferencesKey("repeat_mode")
        private val VOLUME_LEVEL = floatPreferencesKey("volume_level")
        private val LIBRARY_SCAN_VERSION = intPreferencesKey("library_scan_version")
        private val LAST_BACKUP_TIMESTAMP = longPreferencesKey("last_backup_timestamp")
        private val USER_PREFERENCES_VERSION = intPreferencesKey("user_preferences_version")
    }
    
    /**
     * Save complete app state for restoration
     */
    suspend fun saveAppState(state: AppState) {
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LAST_PLAYED_TRACK] = state.lastPlayedTrack ?: ""
                this[LAST_PLAYED_POSITION] = state.lastPlayedPosition
                this[LAST_PLAYED_TIMESTAMP] = state.lastPlayedTimestamp
                this[SHUFFLE_MODE] = state.shuffleMode
                this[REPEAT_MODE] = state.repeatMode
                this[VOLUME_LEVEL] = state.volumeLevel
                this[LIBRARY_SCAN_VERSION] = state.libraryScanVersion
                this[LAST_BACKUP_TIMESTAMP] = state.lastBackupTimestamp
                this[USER_PREFERENCES_VERSION] = state.userPreferencesVersion
            }
        }
        
        // Also save playback state through the dedicated use case
        if (state.playbackState != null) {
            playbackUseCase.savePlaybackState(
                mediaIds = state.playbackState.mediaIds,
                currentIndex = state.playbackState.currentIndex,
                positionMs = state.playbackState.positionMs,
                repeatMode = state.playbackState.repeatMode,
                shuffleEnabled = state.playbackState.shuffleEnabled,
                isPlaying = state.playbackState.isPlaying
            )
        }
        
        if (state.queueState != null) {
            playbackUseCase.saveQueueState(
                playNextItems = state.queueState.playNextItems,
                userQueueItems = state.queueState.userQueueItems,
                currentMainIndex = state.queueState.currentMainIndex
            )
        }
    }
    
    /**
     * Load complete app state
     */
    suspend fun loadAppState(): AppState {
        val preferences = dataStore.data.first()
        val playbackState = playbackUseCase.loadPlaybackState()
        val queueState = playbackUseCase.loadQueueState()
        
        return AppState(
            lastPlayedTrack = preferences[LAST_PLAYED_TRACK]?.takeIf { it.isNotBlank() },
            lastPlayedPosition = preferences[LAST_PLAYED_POSITION] ?: 0L,
            lastPlayedTimestamp = preferences[LAST_PLAYED_TIMESTAMP] ?: 0L,
            shuffleMode = preferences[SHUFFLE_MODE] ?: false,
            repeatMode = preferences[REPEAT_MODE] ?: 0,
            volumeLevel = preferences[VOLUME_LEVEL] ?: 1.0f,
            libraryScanVersion = preferences[LIBRARY_SCAN_VERSION] ?: 0,
            lastBackupTimestamp = preferences[LAST_BACKUP_TIMESTAMP] ?: 0L,
            userPreferencesVersion = preferences[USER_PREFERENCES_VERSION] ?: 0,
            playbackState = playbackState?.let { 
                PlaybackState(
                    mediaIds = it.mediaIds,
                    currentIndex = it.index,
                    positionMs = it.posMs,
                    repeatMode = it.repeat,
                    shuffleEnabled = it.shuffle,
                    isPlaying = it.play
                )
            },
            queueState = queueState?.let {
                QueueState(
                    playNextItems = it.playNextItems,
                    userQueueItems = it.userQueueItems,
                    currentMainIndex = it.currentMainIndex
                )
            }
        )
    }
    
    /**
     * Get app state as flow for reactive updates
     */
    fun getAppStateFlow(): Flow<AppState> {
        return dataStore.data.map { preferences ->
            AppState(
                lastPlayedTrack = preferences[LAST_PLAYED_TRACK]?.takeIf { it.isNotBlank() },
                lastPlayedPosition = preferences[LAST_PLAYED_POSITION] ?: 0L,
                lastPlayedTimestamp = preferences[LAST_PLAYED_TIMESTAMP] ?: 0L,
                shuffleMode = preferences[SHUFFLE_MODE] ?: false,
                repeatMode = preferences[REPEAT_MODE] ?: 0,
                volumeLevel = preferences[VOLUME_LEVEL] ?: 1.0f,
                libraryScanVersion = preferences[LIBRARY_SCAN_VERSION] ?: 0,
                lastBackupTimestamp = preferences[LAST_BACKUP_TIMESTAMP] ?: 0L,
                userPreferencesVersion = preferences[USER_PREFERENCES_VERSION] ?: 0
            )
        }
    }
    
    /**
     * Clear all app state
     */
    suspend fun clearAppState() {
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().clear()
        }
        playbackUseCase.clearPlaybackState()
        cacheManager.clearAllCaches()
    }
    
    /**
     * Update library scan version (triggers cache invalidation)
     */
    suspend fun updateLibraryScanVersion(version: Int) {
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LIBRARY_SCAN_VERSION] = version
            }
        }
    }
    
    /**
     * Check if state restoration is needed
     */
    suspend fun shouldRestoreState(): Boolean {
        val state = loadAppState()
        val timeSinceLastPlay = System.currentTimeMillis() - state.lastPlayedTimestamp
        // Restore if last played within 24 hours and has valid track
        return timeSinceLastPlay < 24 * 60 * 60 * 1000 && state.lastPlayedTrack != null
    }
}

/**
 * Complete app state model
 */
data class AppState(
    val lastPlayedTrack: String? = null,
    val lastPlayedPosition: Long = 0L,
    val lastPlayedTimestamp: Long = 0L,
    val shuffleMode: Boolean = false,
    val repeatMode: Int = 0,
    val volumeLevel: Float = 1.0f,
    val libraryScanVersion: Int = 0,
    val lastBackupTimestamp: Long = 0L,
    val userPreferencesVersion: Int = 0,
    val playbackState: PlaybackState? = null,
    val queueState: QueueState? = null
)

data class PlaybackState(
    val mediaIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val isPlaying: Boolean
)

data class QueueState(
    val playNextItems: List<String>,
    val userQueueItems: List<String>,
    val currentMainIndex: Int
)