package com.musify.mu.di

import android.content.Context
import com.musify.mu.util.SpotifyStyleArtworkLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ArtworkModule {

    @Provides
    @Singleton
    fun provideSpotifyStyleArtworkLoader(@ApplicationContext context: Context): SpotifyStyleArtworkLoader {
        SpotifyStyleArtworkLoader.initialize(context)
        return SpotifyStyleArtworkLoader
    }
}
