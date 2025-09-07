package com.musify.mu.di

import android.content.Context
import android.os.Looper
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.musify.mu.data.repo.QueueStateStore
import com.musify.mu.playback.QueueManager
import com.musify.mu.playback.QueueManagerProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

@Module
@InstallIn(ServiceComponent::class)
object ServiceMediaModule {

    @Provides
    @ServiceScoped
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        cache: SimpleCache
    ): ExoPlayer {
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            .setCacheWriteDataSinkFactory(null)

        val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLooper(Looper.getMainLooper())
            .build()
    }

    @Provides
    @ServiceScoped
    fun provideQueueManager(
        exoPlayer: ExoPlayer,
        queueStateStore: QueueStateStore
    ): QueueManager {
        return QueueManager(exoPlayer, queueStateStore).also { qm ->
            // Expose for Compose helpers
            QueueManagerProvider.setInstance(qm)
        }
    }
}


