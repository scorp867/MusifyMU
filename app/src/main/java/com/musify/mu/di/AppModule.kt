package com.musify.mu.di

import android.content.Context
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.localfiles.LocalFilesService
import com.musify.mu.data.media.SpotifyStyleDataManager
import com.musify.mu.data.repo.LibraryRepository
import com.musify.mu.data.repo.EnhancedLibraryRepository
import com.musify.mu.data.cache.CacheManager
import com.musify.mu.data.cache.CacheStrategy
import com.musify.mu.domain.service.MediaScanningService
import com.musify.mu.domain.service.PlaybackStateService
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
        database: AppDatabase
    ): SpotifyStyleDataManager {
        return SpotifyStyleDataManager.getInstance(context, database)
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
    fun provideEnhancedLibraryRepository(
        @ApplicationContext context: Context,
        database: AppDatabase,
        dataManager: SpotifyStyleDataManager,
        cacheManager: CacheManager,
        cacheStrategy: CacheStrategy,
        mediaScanningService: MediaScanningService
    ): EnhancedLibraryRepository {
        return EnhancedLibraryRepository(context, database, dataManager, cacheManager, cacheStrategy, mediaScanningService)
    }

    @Provides
    @Singleton
    fun provideCacheStrategy(
        @ApplicationContext context: Context,
        cacheManager: CacheManager
    ): CacheStrategy {
        return CacheStrategy(context, cacheManager)
    }

    @Provides
    @Singleton
    fun provideMediaScanningService(
        @ApplicationContext context: Context,
        localFilesService: LocalFilesService,
        dataManager: SpotifyStyleDataManager,
        cacheManager: CacheManager
    ): MediaScanningService {
        return MediaScanningService(context, localFilesService, dataManager, cacheManager)
    }
}
