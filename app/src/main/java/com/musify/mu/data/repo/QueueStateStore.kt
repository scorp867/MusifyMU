package com.musify.mu.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.queueDataStore by preferencesDataStore("queue_state")

class QueueStateStore(context: Context) {
    private val ds = context.queueDataStore
    private val KEY_PLAY_NEXT_COUNT = intPreferencesKey("play_next_count")

    suspend fun getPlayNextCount(): Int =
        ds.data.first()[KEY_PLAY_NEXT_COUNT] ?: 0

    suspend fun setPlayNextCount(count: Int) {
        ds.edit { it[KEY_PLAY_NEXT_COUNT] = count.coerceAtLeast(0) }
    }

    suspend fun decrementOnAdvance() {
        val current = getPlayNextCount()
        if (current > 0) setPlayNextCount(current - 1)
    }
}