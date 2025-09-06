package com.musify.mu.di

import android.content.Context
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.localfiles.LocalFilesService
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return DatabaseProvider.get(context)
    }

    @Provides
    @Singleton
    fun provideLocalFilesService(@ApplicationContext context: Context): LocalFilesService {
        return LocalFilesService.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideSpotifyStyleDataManager(
        @ApplicationContext context: Context,
        database: AppDatabase,
        localFilesService: LocalFilesService
    ): SpotifyStyleDataManager {
        // Remove singleton pattern usage and create directly
        return SpotifyStyleDataManager(context, database)
    }

    @Provides
    @Singleton
    fun provideLibraryRepository(
        @ApplicationContext context: Context,
        database: AppDatabase,
        dataManager: SpotifyStyleDataManager
    ): LibraryRepository {
        return LibraryRepository(context, database, dataManager)
    }

    @Provides
    @Singleton
    fun providePlaybackStateStore(
        @ApplicationContext context: Context
    ): PlaybackStateStore {
        return PlaybackStateStore(context)
    }

    @Provides
    @Singleton
    fun provideQueueStateStore(
        @ApplicationContext context: Context
    ): QueueStateStore {
        return QueueStateStore(context)
    }
}
