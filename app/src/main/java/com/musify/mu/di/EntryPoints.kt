package com.musify.mu.di

import com.musify.mu.data.repo.LibraryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoints {
    fun libraryRepository(): LibraryRepository
}

