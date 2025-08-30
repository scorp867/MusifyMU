package com.musify.mu.data.media

/**
 * Represents the loading state of the data manager
 */
sealed class LoadingState {
    object Idle : LoadingState()
    data class Loading(val message: String) : LoadingState()
    data class Completed(val trackCount: Int) : LoadingState()
    data class Error(val message: String) : LoadingState()
}