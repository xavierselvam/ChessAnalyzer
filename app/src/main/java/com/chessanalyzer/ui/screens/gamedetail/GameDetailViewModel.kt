package com.chessanalyzer.ui.screens.gamedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.lifecycle.asFlow
import com.chessanalyzer.domain.chess.PgnParser
import com.chessanalyzer.domain.chess.ChessBoard
import com.chessanalyzer.domain.chess.indicesToSquare
import com.chessanalyzer.domain.model.AnalysisStatus
import com.chessanalyzer.domain.model.Game
import com.chessanalyzer.domain.model.MoveEvaluation
import com.chessanalyzer.domain.usecase.GetGamesUseCase
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.worker.AnalysisWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GameDetailUiState(
    val game: Game? = null,
    val positions: List<PgnParser.Position> = emptyList(),
    val evaluations: List<MoveEvaluation> = emptyList(),
    val currentPositionIndex: Int = 0,
    val isLoading: Boolean = true,
    val isAnalyzing: Boolean = false,
    val isQueued: Boolean = false,
    val analysisProgress: Float = 0f,
    val errorMessage: String? = null
) {
    val currentFen: String
        get() = positions.getOrNull(currentPositionIndex)?.fen
            ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    val currentMoveEval: MoveEvaluation?
        get() {
            if (currentPositionIndex == 0) return null
            return evaluations.getOrNull(currentPositionIndex - 1)
        }

    val totalPositions: Int get() = positions.size

    val canGoBack: Boolean get() = currentPositionIndex > 0
    val canGoForward: Boolean get() = currentPositionIndex < positions.size - 1

    /**
     * Clock remaining for white as of the current move.
     * Walks back from [currentPositionIndex] to find the most recent white move with a
     * non-null clock. Falls back to the last white clock in the game (shown at start position
     * or before white's first move, so there's always something visible).
     */
    val currentWhiteClock: String?
        get() {
            // Walk backward from current index
            for (i in currentPositionIndex downTo 1) {
                val pos = positions.getOrNull(i) ?: break
                if (pos.isWhiteMove && pos.clock != null) return pos.clock
            }
            // Fallback: last white clock found anywhere in the game
            for (i in positions.indices.reversed()) {
                val pos = positions[i]
                if (pos.isWhiteMove && pos.clock != null) return pos.clock
            }
            return null
        }

    /**
     * Clock remaining for black as of the current move.
     * Same logic as [currentWhiteClock] but for black's moves.
     */
    val currentBlackClock: String?
        get() {
            // Walk backward from current index
            for (i in currentPositionIndex downTo 1) {
                val pos = positions.getOrNull(i) ?: break
                if (!pos.isWhiteMove && pos.clock != null) return pos.clock
            }
            // Fallback: last black clock found anywhere in the game
            for (i in positions.indices.reversed()) {
                val pos = positions[i]
                if (!pos.isWhiteMove && pos.clock != null) return pos.clock
            }
            return null
        }

    val moveList: List<String>
        get() = positions.drop(1).mapNotNull { it.lastMoveSan }

    /** From/to squares of the move that led to the current position, for board highlighting. */
    val lastMoveFrom: String?
        get() = positions.getOrNull(currentPositionIndex)?.lastMoveFrom

    val lastMoveTo: String?
        get() = positions.getOrNull(currentPositionIndex)?.lastMoveTo

    /**
     * From/to squares of the engine's best move, parsed from [currentMoveEval].bestMove SAN
     * applied on the position BEFORE the current move. Returns null when the player played
     * the best move or when analysis is unavailable.
     */
    val bestMoveFrom: String?
        get() = computeBestMoveSquare(from = true)

    val bestMoveTo: String?
        get() = computeBestMoveSquare(from = false)

    private fun computeBestMoveSquare(from: Boolean): String? {
        val eval = currentMoveEval ?: return null
        // If the player played the best move, no arrow needed
        if (eval.bestMove == eval.moveSan) return null
        val prevFen = positions.getOrNull(currentPositionIndex - 1)?.fen ?: return null
        return try {
            val board = ChessBoard.fromFen(prevFen)
            val move = board.parseSanMove(eval.bestMove) ?: return null
            if (from) indicesToSquare(move.fromRank, move.fromFile)
            else indicesToSquare(move.toRank, move.toFile)
        } catch (_: Exception) { null }
    }
}

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    private val getGamesUseCase: GetGamesUseCase,
    private val workManager: WorkManager,
    private val gameRepository: GameRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    fun loadGame(gameId: String) {
        // One-shot: reset stuck ANALYZING status if no WorkManager job is active for this game.
        // This happens when the app was killed mid-analysis and the DB was left in ANALYZING state.
        viewModelScope.launch {
            val game = gameRepository.getGameById(gameId) ?: return@launch
            if (game.analysisStatus == AnalysisStatus.ANALYZING) {
                val infos = workManager.getWorkInfosByTagLiveData(gameId).asFlow().first()
                val isActive = infos.any {
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.BLOCKED
                }
                if (!isActive) {
                    gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.PENDING)
                }
            }
        }
        // Observe game reactively — picks up ANALYZING→DONE transitions triggered from any screen.
        // isAnalyzing is driven directly from the DB analysisStatus — this is the source of truth
        // and is set by the worker immediately, avoiding any WorkManager timing races.
        viewModelScope.launch {
            getGamesUseCase.observeById(gameId).collect { game ->
                if (game == null) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Game not found") }
                    return@collect
                }
                val positions = if (_uiState.value.positions.isEmpty()) {
                    PgnParser.parseToPositions(game.pgn)
                } else {
                    _uiState.value.positions
                }

                val analyzing = game.analysisStatus == AnalysisStatus.ANALYZING
                _uiState.update {
                    it.copy(
                        game = game,
                        positions = positions,
                        isLoading = false,
                        // Only flip isAnalyzing from DB status — WorkInfo used for progress % only
                        isAnalyzing = analyzing,
                        // Clear stale progress when analysis finishes
                        analysisProgress = if (!analyzing) 0f else it.analysisProgress
                    )
                }
            }
        }
        // Observe evaluations — auto-refreshes whenever analysis completes (from any screen)
        viewModelScope.launch {
            getGamesUseCase.observeById(gameId)
                .filter { it?.analysisStatus == AnalysisStatus.DONE }
                .flatMapLatest { gameRepository.getEvaluationsForGame(gameId) }
                .collect { evals ->
                    _uiState.update { it.copy(evaluations = evals) }
                }
        }
        // Observe WorkManager: drives isQueued (ENQUEUED), progress % (RUNNING), and errors.
        viewModelScope.launch {
            workManager.getWorkInfosByTagLiveData(gameId).asFlow()
                .collect { infos ->
                    val enqueued = infos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                    }
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val failed  = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
                    when {
                        running != null -> {
                            val pct = running.progress.getFloat(AnalysisWorker.KEY_PROGRESS, 0f)
                            _uiState.update { it.copy(isQueued = false, analysisProgress = pct) }
                        }
                        enqueued -> {
                            _uiState.update { it.copy(isQueued = true) }
                        }
                        failed != null -> {
                            val msg = failed.outputData.getString("error") ?: "Analysis failed"
                            _uiState.update { it.copy(isQueued = false, errorMessage = msg) }
                        }
                        else -> {
                            // SUCCEEDED / CANCELLED / no work — clear queued flag
                            _uiState.update { it.copy(isQueued = false) }
                        }
                    }
                }
        }
    }

    fun goToStart() {
        _uiState.update { it.copy(currentPositionIndex = 0) }
    }

    fun goBack() {
        _uiState.update {
            it.copy(currentPositionIndex = maxOf(0, it.currentPositionIndex - 1))
        }
    }

    fun goForward() {
        _uiState.update {
            it.copy(currentPositionIndex = minOf(it.positions.size - 1, it.currentPositionIndex + 1))
        }
    }

    fun goToEnd() {
        _uiState.update { it.copy(currentPositionIndex = it.positions.size - 1) }
    }

    fun goToPosition(index: Int) {
        _uiState.update {
            it.copy(currentPositionIndex = index.coerceIn(0, it.positions.size - 1))
        }
    }

    fun analyzeGame() {
        val gameId = _uiState.value.game?.id ?: return
        // Clear stale evaluations and immediately mark as queued in the UI.
        // isAnalyzing will flip to true once the worker writes ANALYZING to DB.
        _uiState.update { it.copy(analysisProgress = 0f, evaluations = emptyList(), isQueued = true) }
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(workDataOf(AnalysisWorker.KEY_GAME_ID to gameId))
            .addTag(gameId)
            .build()
        workManager
            .beginUniqueWork(AnalysisWorker.USER_QUEUE_NAME, ExistingWorkPolicy.REPLACE, request)
            .enqueue()
    }

    fun cancelAnalysis() {
        val gameId = _uiState.value.game?.id ?: return
        // Cancel regardless of which queue the job is in — both are tagged with game.id
        workManager.cancelAllWorkByTag(gameId)
        viewModelScope.launch {
            gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.PENDING)
        }
        _uiState.update { it.copy(isAnalyzing = false, isQueued = false, analysisProgress = 0f) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
