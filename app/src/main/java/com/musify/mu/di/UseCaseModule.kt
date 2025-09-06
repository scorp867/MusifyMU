package com.musify.mu.di

import com.musify.mu.data.cache.CacheManager
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.domain.usecase.*
import com.musify.mu.domain.service.PlaybackStateService
import com.musify.mu.playback.QueueManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Provides
    @Singleton
    fun provideGetTracksUseCase(
        libraryRepository: LibraryRepository
    ): GetTracksUseCase {
        return GetTracksUseCase(libraryRepository)
    }

    @Provides
    @Singleton
    fun providePlaybackUseCase(
        libraryRepository: LibraryRepository,
        playbackStateStore: PlaybackStateStore,
        queueStateStore: QueueStateStore,
        queueManager: QueueManager
    ): PlaybackUseCase {
        return PlaybackUseCase(libraryRepository, playbackStateStore, queueStateStore, queueManager)
    }

    @Provides
    @Singleton
    fun provideArtworkUseCase(
        libraryRepository: LibraryRepository
    ): ArtworkUseCase {
        return ArtworkUseCase(libraryRepository)
    }

    @Provides
    @Singleton
    fun provideLibraryManagementUseCase(
        libraryRepository: LibraryRepository
    ): LibraryManagementUseCase {
        return LibraryManagementUseCase(libraryRepository)
    }

    @Provides
    @Singleton
    fun provideStateManagementUseCase(
        @ApplicationContext context: android.content.Context,
        cacheManager: CacheManager,
        playbackUseCase: PlaybackUseCase
    ): StateManagementUseCase {
        return StateManagementUseCase(context, cacheManager, playbackUseCase)
    }

    @Provides
    @Singleton
    fun provideQueueManagementUseCase(
        queueManager: QueueManager,
        playbackStateService: PlaybackStateService
    ): QueueManagementUseCase {
        return QueueManagementUseCase(queueManager, playbackStateService)
    }
}