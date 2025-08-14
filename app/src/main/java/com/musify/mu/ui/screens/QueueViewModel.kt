package com.musify.mu.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.musify.mu.data.db.entities.Track
import com.musify.mu.playback.QueueManager

class QueueViewModel : ViewModel() {

    private var queueManager: QueueManager? = null

    // Local UI state
    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()

    // Queue changes from QueueManager
    val queueChanges: LiveData<QueueManager.QueueChangeEvent>?
        get() = queueManager?.queueChangesLiveData

    // Queue state from QueueManager
    val queueState: LiveData<QueueManager.QueueState>?
        get() = queueManager?.queueStateLiveData

    // Current item from QueueManager
    val currentItem: LiveData<QueueManager.QueueItem?>?
        get() = queueManager?.currentItemLiveData

    data class QueueUiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val isDragInProgress: Boolean = false,
        val recentAction: QueueAction? = null
    )

    sealed class QueueAction {
        data class ItemMoved(val from: Int, val to: Int, val item: Track) : QueueAction()
        data class ItemRemoved(val item: Track, val position: Int) : QueueAction()
        data class ItemAdded(val item: Track, val position: Int) : QueueAction()
        object QueueShuffled : QueueAction()
        object QueueCleared : QueueAction()
    }

    fun setQueueManager(queueManager: QueueManager) {
        this.queueManager = queueManager
    }

    fun shuffleQueue(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                queueManager?.setShuffle(enabled)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    recentAction = QueueAction.QueueShuffled
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to toggle shuffle: ${e.message}"
                )
            }
        }
    }



    fun moveQueueItem(from: Int, to: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isDragInProgress = true)
                queueManager?.move(from, to)
                _uiState.value = _uiState.value.copy(isDragInProgress = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDragInProgress = false,
                    error = "Failed to move item: ${e.message}"
                )
            }
        }
    }

    fun removeQueueItem(index: Int) {
        viewModelScope.launch {
            try {
                queueManager?.removeAt(index)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to remove item: ${e.message}"
                )
            }
        }
    }

    fun clearQueue(keepCurrent: Boolean = false) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                queueManager?.clearQueue(keepCurrent)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    recentAction = QueueAction.QueueCleared
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to clear queue: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearRecentAction() {
        _uiState.value = _uiState.value.copy(recentAction = null)
    }

    fun setDragInProgress(inProgress: Boolean) {
        _uiState.value = _uiState.value.copy(isDragInProgress = inProgress)
    }
}