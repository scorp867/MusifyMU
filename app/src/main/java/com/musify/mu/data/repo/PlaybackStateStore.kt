package com.musify.mu.data.repo

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("playback_state")

class PlaybackStateStore(context: Context) {
    private val ds = context.dataStore

    private val KEY_QUEUE = stringSetPreferencesKey("queue_ids")
    private val KEY_INDEX = intPreferencesKey("index")
    private val KEY_POSITION = longPreferencesKey("pos")
    private val KEY_REPEAT = intPreferencesKey("repeat")
    private val KEY_SHUFFLE = booleanPreferencesKey("shuffle")
    private val KEY_PLAY = booleanPreferencesKey("play")

    suspend fun save(
        ids: List<String>,
        index: Int,
        posMs: Long,
        repeat: Int,
        shuffle: Boolean,
        play: Boolean
    ) {
        ds.updateData {
            it.toMutablePreferences().apply {
                this[KEY_QUEUE] = ids.toSet()
                this[KEY_INDEX] = index
                this[KEY_POSITION] = posMs
                this[KEY_REPEAT] = repeat
                this[KEY_SHUFFLE] = shuffle
                this[KEY_PLAY] = play
            }
        }
    }

    suspend fun load(): State? {
        val prefs = ds.data.first()
        val ids = prefs[KEY_QUEUE]?.toList() ?: return null
        val index = prefs[KEY_INDEX] ?: 0
        val pos = prefs[KEY_POSITION] ?: 0L
        val repeat = prefs[KEY_REPEAT] ?: 0
        val shuffle = prefs[KEY_SHUFFLE] ?: false
        val play = prefs[KEY_PLAY] ?: false
        return State(ids, index, pos, repeat, shuffle, play)
    }

    data class State(
        val mediaIds: List<String>,
        val index: Int,
        val posMs: Long,
        val repeat: Int,
        val shuffle: Boolean,
        val play: Boolean
    )
}
