package com.chessanalyzer.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chessanalyzer.data.local.db.RatingPoint
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.domain.model.Game
import com.chessanalyzer.domain.model.WeaknessInsight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Defines the time-range selector tabs, identical look to Chess.com. */
enum class StatTimeRange(val label: String, val days: Int?) {
    DAYS_7("7 Days", 7),
    DAYS_30("30 Days", 30),
    DAYS_90("90 Days", 90),
    YEAR_1("1 Year", 365),
    ALL_TIME("All Time", null)
}

data class OpeningStat(
    val name: String,
    val played: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int
)

data class StatsUiState(
    val selectedGameType: String = "rapid",
    val selectedTimeRange: StatTimeRange = StatTimeRange.DAYS_30,
    val ratingHistory: List<RatingPoint> = emptyList(),
    /** Current (most-recent) rating for selected type. */
    val currentRating: Int? = null,
    /** Delta between rating at start-of-period and current. */
    val ratingDelta: Int? = null,
    val totalGames: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val favoriteOpenings: List<OpeningStat> = emptyList(),
    val struggleOpenings: List<OpeningStat> = emptyList(),
    val weaknessInsights: List<WeaknessInsight> = emptyList(),
    val isLoading: Boolean = true
) {
    val winRate: Float get() = if (totalGames > 0) wins.toFloat() / totalGames else 0f
    val lossRate: Float get() = if (totalGames > 0) losses.toFloat() / totalGames else 0f
    val drawRate: Float get() = if (totalGames > 0) draws.toFloat() / totalGames else 0f
}

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val gameRepository: GameRepository
) : ViewModel() {

    private val _selectedGameType = MutableStateFlow("rapid")
    private val _selectedTimeRange = MutableStateFlow(StatTimeRange.DAYS_30)

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(_selectedGameType, _selectedTimeRange) { gt, tr -> gt to tr }
                .collect { (gameType, timeRange) ->
                    loadStats(gameType, timeRange)
                }
        }
    }

    fun onGameTypeSelected(gameType: String) {
        _selectedGameType.value = gameType
        _uiState.update { it.copy(selectedGameType = gameType) }
    }

    fun onTimeRangeSelected(range: StatTimeRange) {
        _selectedTimeRange.value = range
        _uiState.update { it.copy(selectedTimeRange = range) }
    }

    private fun loadStats(gameType: String, timeRange: StatTimeRange) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val allHistory = gameRepository.getRatingHistory(gameType)
            val cutoff = timeRange.days?.let { days ->
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
            }

            val filteredHistory = if (cutoff != null) {
                allHistory.filter { it.playedAt >= cutoff }
            } else {
                allHistory
            }

            val currentRating = filteredHistory.lastOrNull()?.userRating
            val startRating = filteredHistory.firstOrNull()?.userRating
            val delta = if (currentRating != null && startRating != null)
                currentRating - startRating else null

            // For W/L/D we use the games flow (one-shot)
            val games = gameRepository.getGamesByType(gameType).first()
            val timeGames = if (cutoff != null) {
                games.filter { it.playedAt >= cutoff }
            } else {
                games
            }

            val wins = timeGames.count { it.userWon == true }
            val losses = timeGames.count { it.userWon == false }
            val draws = timeGames.count { it.userWon == null }

            // ── Opening stats ──────────────────────────────────────────────
            val openingGroups = timeGames
                .filter { !it.opening.isNullOrBlank() && it.totalMoves >= 6 }
                .groupBy { game ->
                    game.opening!!.substringAfterLast("/").replace("-", " ").trim()
                }

            fun openingStat(name: String, group: List<Game>) = OpeningStat(
                name = name,
                played = group.size,
                wins = group.count { it.userWon == true },
                losses = group.count { it.userWon == false },
                draws = group.count { it.userWon == null }
            )

            val favoriteOpenings = openingGroups
                .map { (name, group) -> openingStat(name, group) }
                .sortedByDescending { it.played }
                .take(5)

            val struggleOpenings = openingGroups
                .map { (name, group) -> openingStat(name, group) }
                .filter { it.losses > it.wins }
                .sortedByDescending { it.losses - it.wins }
                .take(5)

            _uiState.update {
                it.copy(
                    selectedGameType = gameType,
                    selectedTimeRange = timeRange,
                    ratingHistory = filteredHistory,
                    currentRating = currentRating,
                    ratingDelta = delta,
                    totalGames = timeGames.size,
                    wins = wins,
                    losses = losses,
                    draws = draws,
                    favoriteOpenings = favoriteOpenings,
                    struggleOpenings = struggleOpenings,
                    weaknessInsights = gameRepository.getWeaknessInsights(),
                    isLoading = false
                )
            }
        }
    }
}
