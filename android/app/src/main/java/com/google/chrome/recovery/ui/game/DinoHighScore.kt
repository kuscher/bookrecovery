package com.google.chrome.recovery.ui.game

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dinoDataStore by preferencesDataStore(name = "dino_game")

/** DataStore-backed persistence for the runner's high score. */
object DinoHighScore {

    private val KEY_HIGH_SCORE = intPreferencesKey("high_score")

    fun flow(context: Context): Flow<Int> =
        context.dinoDataStore.data.map { it[KEY_HIGH_SCORE] ?: 0 }

    suspend fun save(context: Context, score: Int) {
        context.dinoDataStore.edit { preferences ->
            val current = preferences[KEY_HIGH_SCORE] ?: 0
            if (score > current) preferences[KEY_HIGH_SCORE] = score
        }
    }
}
