package com.chessanalyzer.ui.screens.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.domain.model.AnalysisStatus
import com.chessanalyzer.domain.model.Game
import com.chessanalyzer.domain.model.Platform
import com.chessanalyzer.domain.usecase.GetGamesUseCase
import com.chessanalyzer.domain.usecase.SyncGamesUseCase
import com.chessanalyzer.data.repository.SyncRepository
import com.chessanalyzer.worker.AnalysisWorker
import com.chessanalyzer.worker.AutoAnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GamesUiState(
    val games: List<Game> = emptyList(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val selectedPlatform: Platform? = null,       // null = All
    val selectedGameTypes: Set<String> = emptySet(), // empty = All; multi-select
    val openingFilter: String? = null,       // set when navigated from Stats opening tap
    val syncMessage: String? = null,
    val hasAccounts: Boolean = false,
    /** Maps gameId → analysis progress (0f..1f) for games currently being analyzed. */
    val analysisProgressMap: Map<String, Float> = emptyMap(),
    /** Game IDs whose WorkRequest is ENQUEUED (waiting in queue, not yet running). */
    val queuedGameIds: Set<String> = emptySet(),
    /** Game IDs currently in the auto-analysis queue (at most BATCH_SIZE at once). */
    val autoQueuedIds: Set<String> = emptySet(),
)

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val getGamesUseCase: GetGamesUseCase,
    private val syncGamesUseCase: SyncGamesUseCase,
    private val gameRepository: GameRepository,
    private val workManager: WorkManager,
    private val autoAnalysisScheduler: AutoAnalysisScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(GamesUiState(isLoading = true))
    val uiState: StateFlow<GamesUiState> = _uiState.asStateFlow()

    private val _selectedPlatform = MutableStateFlow<Platform?>(null)
    private val _selectedGameTypes = MutableStateFlow<Set<String>>(emptySet())
    private val _openingFilter = MutableStateFlow<String?>(null)

    /** Track which game IDs we've already started a WorkInfo observer for. */
    private val observedProgressGames = mutableSetOf<String>()

    /** Guard so the stuck-analysis check only runs once per app session. */
    private var stuckCheckDone = false

    init {
        // Phase 1: show first 50 games instantly while the reactive flow warms up
        viewModelScope.launch {
            val initial = gameRepository.getInitialGames(50)
            if (initial.isNotEmpty()) {
                _uiState.update { it.copy(games = initial, isLoading = false, hasAccounts = true) }
            }
        }

        // Phase 2: Observe games with platform + game type filters (replaces phase 1 when ready)
        viewModelScope.launch {
            combine(_selectedPlatform, _selectedGameTypes, _openingFilter) { platform, gameTypes, opening ->
                Triple(platform, gameTypes, opening)
            }
                .flatMapLatest { (platform, gameTypes, opening) ->
                    getGamesUseCase(platform, null).map { games ->
                        val typeFiltered = if (gameTypes.isEmpty()) games
                        else games.filter { it.gameType in gameTypes }
                        if (opening != null) {
                            typeFiltered.filter { game ->
                                game.opening?.substringAfterLast("/")?.replace("-", " ")?.trim()
                                    ?.equals(opening, ignoreCase = true) == true
                            }
                        } else typeFiltered
                    }
                }.collect { games ->
                _uiState.update {
                    it.copy(
                        games = games,
                        isLoading = false,
                        hasAccounts = true,
                        openingFilter = _openingFilter.value
                    )
                }

                // One-shot: on first game emission, reset any stuck ANALYZING games whose
                // WorkManager job is no longer active (app was killed mid-analysis).
                if (!stuckCheckDone) {
                    stuckCheckDone = true
                    viewModelScope.launch {
                        games.filter { it.analysisStatus == AnalysisStatus.ANALYZING }
                            .forEach { game ->
                                val infos = workManager
                                    .getWorkInfosByTagLiveData(game.id).asFlow().first()
                                val isActive = infos.any {
                                    it.state == WorkInfo.State.RUNNING ||
                                    it.state == WorkInfo.State.ENQUEUED ||
                                    it.state == WorkInfo.State.BLOCKED
                                }
                                if (!isActive) {
                                    gameRepository.updateAnalysisStatus(game.id, AnalysisStatus.PENDING)
                                }
                            }
                    }
                }

                // Start WorkInfo observer for any newly ANALYZING game
                games.filter { it.analysisStatus == AnalysisStatus.ANALYZING }
                    .filter { it.id !in observedProgressGames }
                    .forEach { game ->
                        observedProgressGames.add(game.id)
                        observeWorkProgress(game.id)
                    }

                // Clean up progress map for games no longer analyzing
                val analyzingIds = games
                    .filter { it.analysisStatus == AnalysisStatus.ANALYZING }
                    .map { it.id }.toSet()
                _uiState.update { state ->
                    state.copy(
                        analysisProgressMap = state.analysisProgressMap.filterKeys { it in analyzingIds }
                    )
                }
            }
        }

        // Track exactly which game IDs are in the auto queue (max BATCH_SIZE at a time)
        viewModelScope.launch {
            autoAnalysisScheduler.observeAutoQueuedIds().collect { ids ->
                _uiState.update { it.copy(autoQueuedIds = ids) }
            }
        }

        // Auto-sync silently on init (no "already up to date" toast)
        syncGames(silent = true)
    }

    private fun observeWorkProgress(gameId: String) {
        viewModelScope.launch {
            workManager
                .getWorkInfosByTagLiveData(gameId)
                .asFlow()
                .collect { infos ->
                    val enqueued = infos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                    }
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }

                    // Track queued status (ENQUEUED = waiting in line, not yet running)
                    _uiState.update { state ->
                        val newQueued = if (enqueued && running == null)
                            state.queuedGameIds + gameId
                        else
                            state.queuedGameIds - gameId
                        state.copy(queuedGameIds = newQueued)
                    }

                    // Track progress for running job
                    if (running != null) {
                        val pct = running.progress.getFloat(AnalysisWorker.KEY_PROGRESS, 0f)
                        _uiState.update { state ->
                            state.copy(analysisProgressMap = state.analysisProgressMap + (gameId to pct))
                        }
                    }
                }
        }
    }

    fun onPlatformFilterChange(platform: Platform?) {
        _selectedPlatform.value = platform
        _uiState.update { it.copy(selectedPlatform = platform) }
    }

    fun toggleGameTypeFilter(type: String?) {
        val current = _selectedGameTypes.value.toMutableSet()
        if (type == null) {
            current.clear() // "All" clears everything
        } else {
            if (type in current) current.remove(type) else current.add(type)
        }
        _selectedGameTypes.value = current
        _uiState.update { it.copy(selectedGameTypes = current) }
    }

    fun setOpeningFilter(opening: String?) {
        _openingFilter.value = opening
        _uiState.update { it.copy(openingFilter = opening) }
    }

    fun syncGames(silent: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            when (val result = syncGamesUseCase()) {
                is SyncRepository.SyncResult.Success -> {
                    val msg = when {
                        result.newGamesCount > 0 -> "Synced ${result.newGamesCount} new game(s)"
                        silent -> null   // suppress "already up to date" on auto-sync
                        else -> "Already up to date"
                    }
                    _uiState.update { it.copy(isSyncing = false, syncMessage = msg) }
                    // If auto-analyze is on, queue any newly synced (or pre-existing) pending games
                    autoAnalysisScheduler.scheduleAllPending()
                }
                is SyncRepository.SyncResult.Error -> {
                    _uiState.update { it.copy(isSyncing = false, syncMessage = "Sync failed: ${result.message}") }
                }
            }
        }
    }

    fun analyzeGame(gameId: String) {
        // Reset to null first so LaunchedEffect(syncMessage) always re-fires,
        // even if the previous message was also "Analysis queued…".
        _uiState.update { it.copy(syncMessage = null) }
        // Immediately mark as queued in UI — WorkInfo observer will confirm/clear it
        _uiState.update { state ->
            state.copy(
                syncMessage = "Analysis queued…",
                queuedGameIds = state.queuedGameIds + gameId
            )
        }
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(workDataOf(
                AnalysisWorker.KEY_GAME_ID to gameId,
                AnalysisWorker.KEY_IS_USER_REQUESTED to true
            ))
            .addTag(gameId)
            .build()
        // APPEND_OR_REPLACE: if another game is running, this queues after it.
        // The singleton StockfishEngine is never accessed from two workers at once.
        workManager
            .beginUniqueWork(AnalysisWorker.USER_QUEUE_NAME, ExistingWorkPolicy.REPLACE, request)
            .enqueue()
        // Ensure WorkInfo is observed for this game (may not be ANALYZING in DB yet)
        if (gameId !in observedProgressGames) {
            observedProgressGames.add(gameId)
            observeWorkProgress(gameId)
        }
    }

    fun cancelGame(gameId: String) {
        workManager.cancelAllWorkByTag(gameId)
        viewModelScope.launch {
            gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.PENDING)
        }
        _uiState.update { state ->
            state.copy(
                queuedGameIds = state.queuedGameIds - gameId,
                analysisProgressMap = state.analysisProgressMap - gameId
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(syncMessage = null) }
    }
}
