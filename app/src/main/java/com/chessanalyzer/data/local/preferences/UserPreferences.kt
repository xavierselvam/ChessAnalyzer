package com.chessanalyzer.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val CHESS_COM_USERNAME = stringPreferencesKey("chess_com_username")
        val LICHESS_USERNAME = stringPreferencesKey("lichess_username")
        val ENGINE_DEPTH = intPreferencesKey("engine_depth")
        val ENGINE_MODE = stringPreferencesKey("engine_mode") // "local" | "cloud" | "hybrid"
        val AUTO_ANALYZE = booleanPreferencesKey("auto_analyze")
        val DARK_MODE = stringPreferencesKey("dark_mode") // "system", "light", "dark"
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
    }

    val chessComUsername: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CHESS_COM_USERNAME] ?: ""
    }

    val lichessUsername: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LICHESS_USERNAME] ?: ""
    }

    val engineDepth: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ENGINE_DEPTH] ?: 14
    }

    val engineMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ENGINE_MODE] ?: "local"
    }

    val autoAnalyze: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_ANALYZE] ?: false
    }

    val darkMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DARK_MODE] ?: "system"
    }

    val lastSyncTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[LAST_SYNC_TIME] ?: 0L
    }

    suspend fun setChessComUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[CHESS_COM_USERNAME] = username
        }
    }

    suspend fun setLichessUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[LICHESS_USERNAME] = username
        }
    }

    suspend fun setEngineDepth(depth: Int) {
        context.dataStore.edit { prefs ->
            prefs[ENGINE_DEPTH] = depth
        }
    }

    suspend fun setEngineMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[ENGINE_MODE] = mode
        }
    }

    suspend fun setAutoAnalyze(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_ANALYZE] = enabled
        }
    }

    suspend fun setDarkMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[DARK_MODE] = mode
        }
    }

    suspend fun setLastSyncTime(time: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SYNC_TIME] = time
        }
    }
}
