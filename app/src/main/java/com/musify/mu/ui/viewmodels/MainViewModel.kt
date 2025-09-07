package com.musify.mu.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musify.mu.util.PermissionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main ViewModel that manages app-level state and coordinates between other ViewModels
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanningViewModel: ScanningViewModel,
    private val playbackViewModel: PlaybackViewModel
) : ViewModel() {
    
    // Permission state
    private val _hasMediaPermissions = MutableStateFlow(false)
    val hasMediaPermissions: StateFlow<Boolean> = _hasMediaPermissions.asStateFlow()
    
    // App initialization state
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    // Combined loading state
    val isLoading: StateFlow<Boolean> = combine(
        scanningViewModel.isScanningOrLoading,
        playbackViewModel.isLoading
    ) { scanning, playback ->
        scanning || playback
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    
    /**
     * Initialize the app
     */
    fun initializeApp() {
        viewModelScope.launch {
            // Check permissions
            _hasMediaPermissions.value = PermissionManager.checkMediaPermissions(context)
            
            if (_hasMediaPermissions.value) {
                // Initialize scanning
                scanningViewModel.initializeScanning()
                
                _isInitialized.value = true
            }
        }
    }
    
    /**
     * Handle permission grant
     */
    fun onPermissionsGranted() {
        viewModelScope.launch {
            _hasMediaPermissions.value = true
            
            // Initialize scanning after permissions are granted
            scanningViewModel.initializeScanning()
            scanningViewModel.onPermissionsChanged()
            
            _isInitialized.value = true
        }
    }
    
    /**
     * Handle permission denial
     */
    fun onPermissionsDenied() {
        _hasMediaPermissions.value = false
    }
    
    /**
     * Force refresh the library
     */
    fun refreshLibrary() {
        scanningViewModel.forceRescan()
    }
}