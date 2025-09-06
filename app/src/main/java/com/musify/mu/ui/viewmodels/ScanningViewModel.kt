package com.musify.mu.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.data.localfiles.LocalFilesService
import com.musify.mu.data.localfiles.ScanState
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.data.media.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

/**
 * ViewModel for managing library scanning operations.
 * Centralizes all scanning logic to prevent duplicate scans.
 */
@HiltViewModel
class ScanningViewModel @Inject constructor(
    private val localFilesService: LocalFilesService,
    private val dataManager: SpotifyStyleDataManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "ScanningViewModel"
    }
    
    // Scanning state from LocalFilesService
    val scanState: StateFlow<ScanState> = localFilesService.scanState
    
    // Loading state from DataManager
    val loadingState: StateFlow<LoadingState> = dataManager.loadingState
    
    // Combined scanning status
    private val _isScanningOrLoading = MutableStateFlow(false)
    val isScanningOrLoading: StateFlow<Boolean> = _isScanningOrLoading.asStateFlow()
    
    // Track count
    val trackCount: StateFlow<Int> = dataManager.tracks.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
    
    // Scan progress (0-100)
    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()
    
    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Last scan time
    private val _lastScanTime = MutableStateFlow(0L)
    val lastScanTime: StateFlow<Long> = _lastScanTime.asStateFlow()
    
    init {
        // Monitor scan states to update combined status
        viewModelScope.launch {
            combine(scanState, loadingState) { scan, loading ->
                when {
                    scan is ScanState.Scanning -> true
                    loading is LoadingState.Loading -> true
                    else -> false
                }
            }.collect { isScanning ->
                _isScanningOrLoading.value = isScanning
            }
        }
        
        // Monitor scan completion
        viewModelScope.launch {
            scanState.collect { state ->
                when (state) {
                    is ScanState.Completed -> {
                        _lastScanTime.value = System.currentTimeMillis()
                        _scanProgress.value = 100
                        Log.d(TAG, "Scan completed with ${state.trackCount} tracks")
                    }
                    is ScanState.Error -> {
                        _error.value = state.message
                        _scanProgress.value = 0
                        Log.e(TAG, "Scan error: ${state.message}")
                    }
                    is ScanState.Scanning -> {
                        _scanProgress.value = 50 // Indeterminate progress
                        _error.value = null
                    }
                    else -> {
                        _scanProgress.value = 0
                    }
                }
            }
        }
    }
    
    /**
     * Initialize scanning on app launch (called once)
     */
    fun initializeScanning() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Initializing scanning...")
                _error.value = null
                
                // Initialize LocalFilesService if not already done
                localFilesService.initialize()
                
                // Initialize DataManager
                dataManager.initializeOnAppLaunch()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing scanning", e)
                _error.value = "Failed to initialize music library: ${e.message}"
            }
        }
    }
    
    /**
     * Force a manual rescan of the library
     */
    fun forceRescan() {
        if (_isScanningOrLoading.value) {
            Log.w(TAG, "Scan already in progress, ignoring force rescan request")
            return
        }
        
        viewModelScope.launch {
            try {
                Log.d(TAG, "Forcing library rescan...")
                _error.value = null
                _scanProgress.value = 0
                
                localFilesService.forceRefresh()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error forcing rescan", e)
                _error.value = "Failed to rescan library: ${e.message}"
            }
        }
    }
    
    /**
     * Handle permission changes
     */
    fun onPermissionsChanged() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Permissions changed, updating scanning state...")
                _error.value = null
                
                localFilesService.onPermissionsChanged()
                dataManager.onPermissionsChanged()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling permission changes", e)
                _error.value = "Failed to update permissions: ${e.message}"
            }
        }
    }
    
    /**
     * Add a file from file picker
     */
    fun addFileFromPicker(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Adding file from picker: $uri")
                _error.value = null
                
                localFilesService.addPermanentFile(uri)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error adding file", e)
                _error.value = "Failed to add file: ${e.message}"
            }
        }
    }
    
    /**
     * Clear all caches
     */
    fun clearCaches() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Clearing all caches...")
                dataManager.clearCache()
                _error.value = null
                
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing caches", e)
                _error.value = "Failed to clear caches: ${e.message}"
            }
        }
    }
    
    /**
     * Get scanning status message
     */
    fun getScanStatusMessage(): String {
        return when (val state = scanState.value) {
            is ScanState.Idle -> {
                if (trackCount.value > 0) {
                    "${trackCount.value} tracks in library"
                } else {
                    "No music found"
                }
            }
            is ScanState.Scanning -> state.message
            is ScanState.Completed -> "${state.trackCount} tracks found"
            is ScanState.Error -> "Error: ${state.message}"
            is ScanState.PermissionRequired -> "Permission required to scan music"
        }
    }
    
    /**
     * Check if initial scan has been completed
     */
    fun hasCompletedInitialScan(): Boolean {
        return lastScanTime.value > 0 || trackCount.value > 0
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }
}