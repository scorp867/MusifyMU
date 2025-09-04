package com.musify.mu.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.queueDataStore by preferencesDataStore("queue_state")

class QueueStateStore(context: Context) {
    private val ds = context.queueDataStore
    private val KEY_PLAY_NEXT_COUNT = intPreferencesKey("play_next_count")
    private val KEY_PLAY_NEXT_ITEMS = stringSetPreferencesKey("play_next_items")
    private val KEY_USER_QUEUE_ITEMS = stringSetPreferencesKey("user_queue_items")
    private val KEY_CURRENT_MAIN_INDEX = intPreferencesKey("current_main_index")

    suspend fun getPlayNextCount(): Int =
        ds.data.first()[KEY_PLAY_NEXT_COUNT] ?: 0

    suspend fun setPlayNextCount(count: Int) {
        ds.edit { it[KEY_PLAY_NEXT_COUNT] = count.coerceAtLeast(0) }
    }

    suspend fun decrementOnAdvance() {
        val current = getPlayNextCount()
        if (current > 0) setPlayNextCount(current - 1)
    }

    // Comprehensive queue state management
    suspend fun saveQueueState(playNextItems: List<String>, userQueueItems: List<String>, currentMainIndex: Int) {
        ds.edit { preferences ->
            preferences[KEY_PLAY_NEXT_ITEMS] = playNextItems.toSet()
            preferences[KEY_USER_QUEUE_ITEMS] = userQueueItems.toSet()
            preferences[KEY_CURRENT_MAIN_INDEX] = currentMainIndex
            preferences[KEY_PLAY_NEXT_COUNT] = playNextItems.size
        }
    }

    suspend fun loadQueueState(): QueueState? {
        val preferences = ds.data.first()
        val playNextItems = preferences[KEY_PLAY_NEXT_ITEMS]?.toList() ?: emptyList()
        val userQueueItems = preferences[KEY_USER_QUEUE_ITEMS]?.toList() ?: emptyList()
        val currentMainIndex = preferences[KEY_CURRENT_MAIN_INDEX] ?: 0

        return if (playNextItems.isNotEmpty() || userQueueItems.isNotEmpty()) {
            QueueState(playNextItems, userQueueItems, currentMainIndex)
        } else null
    }

    suspend fun clearQueueState() {
        ds.edit { preferences ->
            preferences.remove(KEY_PLAY_NEXT_ITEMS)
            preferences.remove(KEY_USER_QUEUE_ITEMS)
            preferences.remove(KEY_CURRENT_MAIN_INDEX)
            preferences.remove(KEY_PLAY_NEXT_COUNT)
        }
    }

    data class QueueState(
        val playNextItems: List<String>,
        val userQueueItems: List<String>,
        val currentMainIndex: Int
    )
}