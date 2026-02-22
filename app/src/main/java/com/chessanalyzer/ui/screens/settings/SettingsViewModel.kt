package com.chessanalyzer.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chessanalyzer.data.local.preferences.UserPreferences
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.data.repository.SyncRepository
import com.chessanalyzer.di.ApplicationScope
import com.chessanalyzer.worker.AutoAnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val chessComUsername: String = "",
    val lichessUsername: String = "",
    val engineDepth: Int = 18,
    val engineMode: String = "local",  // "local" | "cloud" | "hybrid"
    val autoAnalyze: Boolean = false,
    val darkMode: String = "system",
    val isValidatingChessCom: Boolean = false,
    val isValidatingLichess: Boolean = false,
    val chessComValid: Boolean? = null,
    val lichessValid: Boolean? = null,
    val saveMessage: String? = null,
    val isRefreshing: Boolean = false,
    val totalGames: Int = 0,
    val analyzedGames: Int = 0,
    val analysisStatus: String? = null,
    /** True when Analysis or Appearance settings have been changed but not yet saved. */
    val pendingChanges: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val syncRepository: SyncRepository,
    private val gameRepository: GameRepository,
    private val autoAnalysisScheduler: AutoAnalysisScheduler,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                userPreferences.chessComUsername,
                userPreferences.lichessUsername,
                userPreferences.engineDepth,
                userPreferences.autoAnalyze,
                userPreferences.darkMode
            ) { chesscom, lichess, depth, autoAnalyze, darkMode ->
                // Use update so we never wipe totalGames / analyzedGames / analysisStatus
                _uiState.update { it.copy(
                    chessComUsername = chesscom,
                    lichessUsername = lichess,
                    engineDepth = depth,
                    autoAnalyze = autoAnalyze,
                    darkMode = darkMode
                )}
            }.collect()
        }
        // Watch engineMode separately (combine only supports 5 flows with typed lambdas)
        viewModelScope.launch {
            userPreferences.engineMode.collect { mode ->
                _uiState.update { it.copy(engineMode = mode) }
            }
        }
        loadGameCounts()

        // Live analysis status from WorkManager; reload counts when queue drains
        viewModelScope.launch {
            var wasActive = false
            autoAnalysisScheduler.observeStatus().collect { status ->
                if (wasActive && status == null) loadGameCounts()
                wasActive = status != null
                _uiState.update { it.copy(analysisStatus = status) }
            }
        }
    }

    private fun loadGameCounts() {
        viewModelScope.launch {
            val total = gameRepository.getGameCount()
            val analyzed = gameRepository.getAnalyzedGameCount()
            _uiState.update { it.copy(totalGames = total, analyzedGames = analyzed) }
        }
    }

    fun onChessComUsernameChange(username: String) {
        _uiState.update { it.copy(chessComUsername = username, chessComValid = null) }
    }

    fun onLichessUsernameChange(username: String) {
        _uiState.update { it.copy(lichessUsername = username, lichessValid = null) }
    }

    fun onEngineDepthChange(depth: Int) {
        _uiState.update { it.copy(engineDepth = depth, pendingChanges = true) }
    }

    fun onEngineModeChange(mode: String) {
        _uiState.update { it.copy(engineMode = mode, pendingChanges = true) }
    }

    fun onAutoAnalyzeChange(enabled: Boolean) {
        _uiState.update { it.copy(autoAnalyze = enabled, pendingChanges = true) }
    }

    fun onDarkModeChange(mode: String) {
        _uiState.update { it.copy(darkMode = mode, pendingChanges = true) }
    }

    /** Persists all Analysis + Appearance settings in one go. */
    fun saveSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            val prevAutoAnalyze = userPreferences.autoAnalyze.first()
            userPreferences.setEngineDepth(state.engineDepth)
            userPreferences.setEngineMode(state.engineMode)
            userPreferences.setDarkMode(state.darkMode)
            userPreferences.setAutoAnalyze(state.autoAnalyze)
            // Handle scheduler side-effects
            if (state.autoAnalyze && !prevAutoAnalyze) {
                autoAnalysisScheduler.scheduleAllPending()
            } else if (!state.autoAnalyze && prevAutoAnalyze) {
                autoAnalysisScheduler.cancelAll()
            }
            _uiState.update { it.copy(pendingChanges = false, saveMessage = "Settings saved") }
        }
    }

    /** Reverts draft to what's currently persisted in DataStore. */
    fun discardChanges() {
        viewModelScope.launch {
            val depth = userPreferences.engineDepth.first()
            val mode = userPreferences.engineMode.first()
            val dark = userPreferences.darkMode.first()
            val auto = userPreferences.autoAnalyze.first()
            _uiState.update {
                it.copy(
                    engineDepth = depth,
                    engineMode = mode,
                    darkMode = dark,
                    autoAnalyze = auto,
                    pendingChanges = false
                )
            }
        }
    }

    fun saveChessComUsername() {
        val username = _uiState.value.chessComUsername.trim()
        if (username.isBlank()) {
            viewModelScope.launch { userPreferences.setChessComUsername("") }
            _uiState.update { it.copy(chessComValid = null, saveMessage = "Chess.com account cleared") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingChessCom = true) }
            val valid = syncRepository.validateChessComUsername(username)
            _uiState.update {
                it.copy(
                    isValidatingChessCom = false,
                    chessComValid = valid,
                    saveMessage = if (valid) "Chess.com account saved!" else "Username not found on Chess.com"
                )
            }
            if (valid) {
                userPreferences.setChessComUsername(username)
            }
        }
    }

    fun saveLichessUsername() {
        val username = _uiState.value.lichessUsername.trim()
        if (username.isBlank()) {
            viewModelScope.launch { userPreferences.setLichessUsername("") }
            _uiState.update { it.copy(lichessValid = null, saveMessage = "Lichess account cleared") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingLichess = true) }
            val valid = syncRepository.validateLichessUsername(username)
            _uiState.update {
                it.copy(
                    isValidatingLichess = false,
                    lichessValid = valid,
                    saveMessage = if (valid) "Lichess account saved!" else "Username not found on Lichess"
                )
            }
            if (valid) {
                userPreferences.setLichessUsername(username)
            }
        }
    }

    /** Wipes all local game data and re-syncs from APIs so gameType/ratings are correct.
     *  Runs in [appScope] (Application lifetime) so it survives navigation away from Settings. */
    fun refreshAllGameData() {
        appScope.launch {
            _uiState.update { it.copy(isRefreshing = true, saveMessage = null) }
            gameRepository.deleteAllGames()
            when (val result = syncRepository.syncAllPlatforms()) {
                is SyncRepository.SyncResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            saveMessage = "Refreshed ${result.newGamesCount} games with updated ratings"
                        )
                    }
                    loadGameCounts()
                }
                is SyncRepository.SyncResult.Error ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            saveMessage = "Refresh failed: ${result.message}"
                        )
                    }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(saveMessage = null) }
        loadGameCounts() // refresh counts in case analysis just finished
    }
}
