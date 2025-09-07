package com.musify.mu.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.musify.mu.data.cache.CacheManager
import com.musify.mu.data.localfiles.LocalFilesService
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.domain.service.MediaScanningService
import com.musify.mu.domain.service.PlaybackStateService
import com.musify.mu.domain.usecase.PlaybackUseCase
import com.musify.mu.domain.usecase.StateManagementUseCase
import com.musify.mu.playback.QueueManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun providePlaybackStateService(
        @ApplicationContext context: Context,
        playbackUseCase: PlaybackUseCase,
        stateManagementUseCase: StateManagementUseCase,
        queueManager: QueueManager,
        player: ExoPlayer
    ): PlaybackStateService {
        return PlaybackStateService(context, playbackUseCase, stateManagementUseCase, queueManager, player)
    }
}