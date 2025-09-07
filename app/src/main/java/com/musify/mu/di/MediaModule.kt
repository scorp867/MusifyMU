package com.musify.mu.di

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.musify.mu.data.db.AppDatabase
import com.musify.mu.data.repo.PlaybackStateStore
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.data.repo.LyricsStateStore
import com.musify.mu.data.repo.LyricsRepository
import com.musify.mu.playback.AudioFocusManager
import com.musify.mu.playback.QueueManager
import com.musify.mu.playback.QueueManagerProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    @Singleton
    fun provideMediaCache(@ApplicationContext context: Context): SimpleCache {
        val cacheDir = File(context.cacheDir, "media3_cache")
        val cacheEvictor = LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024L) // 500MB cache
        return SimpleCache(cacheDir, cacheEvictor)
    }

    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context, cache: SimpleCache): ExoPlayer {
        // Create cache data source factory
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            .setCacheWriteDataSinkFactory(null) // Disable writing to cache for now
        
        // Create media source factory with caching
        val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)
        
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    @Provides
    @Singleton
    fun providePlaybackStateStore(@ApplicationContext context: Context): PlaybackStateStore {
        return PlaybackStateStore(context)
    }

    @Provides
    @Singleton
    fun provideQueueStateStore(@ApplicationContext context: Context): QueueStateStore {
        return QueueStateStore(context)
    }

    @Provides
    @Singleton
    fun provideLyricsRepository(@ApplicationContext context: Context, database: AppDatabase): LyricsRepository {
        return LyricsRepository(context, database)
    }

    @Provides
    @Singleton
    fun provideLyricsStateStore(@ApplicationContext context: Context, lyricsRepository: LyricsRepository): LyricsStateStore {
        return LyricsStateStore(context, lyricsRepository)
    }

    @Provides
    @Singleton
    fun provideQueueManager(
        exoPlayer: ExoPlayer,
        queueStateStore: QueueStateStore
    ): QueueManager {
        return QueueManager(exoPlayer, queueStateStore).also {
            QueueManagerProvider.setInstance(it)
        }
    }

    @Provides
    @Singleton
    fun provideAudioFocusManager(@ApplicationContext context: Context): AudioFocusManager {
        return AudioFocusManager(context).apply {
            // Callback will be set by the service after injection
        }
    }
}
