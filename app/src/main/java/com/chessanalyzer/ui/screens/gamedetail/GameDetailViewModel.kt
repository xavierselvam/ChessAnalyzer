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
import com.chessanalyzer.domain.engine.LichessOpeningService
import com.chessanalyzer.domain.engine.LichessTablebaseService
import com.chessanalyzer.domain.engine.OpeningExplorerResult
import com.chessanalyzer.domain.engine.PositionEvaluator
import com.chessanalyzer.domain.engine.TablebaseResult
import com.chessanalyzer.domain.model.AnalysisStatus
import com.chessanalyzer.domain.model.Game
import com.chessanalyzer.domain.model.MoveClassification
import com.chessanalyzer.domain.model.MoveEvaluation
import com.chessanalyzer.domain.usecase.GetGamesUseCase
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.ui.components.EngineLine
import com.chessanalyzer.audio.ChessSoundPlayer
import com.chessanalyzer.worker.AnalysisWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
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
    val errorMessage: String? = null,
    // ── Explore mode (user drags pieces on the board for free analysis) ──────
    val isExploreMode: Boolean = false,
    val selectedSquare: String? = null,
    val legalTargets: Set<String> = emptySet(),
    val exploreFen: String? = null,
    val exploreArrowFrom: String? = null,
    val exploreArrowTo: String? = null,
    val isExploreAnalyzing: Boolean = false,
    // ── Multi-PV engine lines (live in explore/practice) ─────────────────────
    val explorePvLines: List<EngineLine> = emptyList(),
    // ── Live board evaluation (centipawns, White's perspective) ───────────────
    val liveBoardEvalCp: Int? = null,
    // ── Tablebase lookup (auto-triggers when ≤7 pieces in explore mode) ───────
    val tablebaseResult: TablebaseResult? = null,
    // ── Opening explorer (fetched on move navigation) ─────────────────────────
    val openingResult: OpeningExplorerResult? = null,
    val isFetchingOpening: Boolean = false,
    // ── Practice mode (user color vs engine) ─────────────────────────────────
    val isPracticeMode: Boolean = false,
    val practicePlayerIsWhite: Boolean = true,
    val isEngineThinking: Boolean = false,
    // ── PGN export (one-shot — screen consumes once then clears) ─────────────
    val pgnExportText: String? = null
) {
    /** FEN to display — explore override in explore mode, game position otherwise */
    val boardFen: String
        get() = exploreFen ?: currentFen

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
     * From/to squares of the engine's best move — explore Stockfish result in explore mode,
     * game analysis arrow otherwise.
     */
    val boardBestArrowFrom: String?
        get() = if (isExploreMode) exploreArrowFrom else computeBestMoveSquare(from = true)

    val boardBestArrowTo: String?
        get() = if (isExploreMode) exploreArrowTo else computeBestMoveSquare(from = false)

    /**
     * Actual played-move FROM arrow — shown in red/orange when the move wasn't the best.
     * Null in explore mode (no game move to compare against).
     */
    val actualArrowFrom: String?
        get() {
            if (isExploreMode) return null
            val eval = currentMoveEval ?: return null
            if (eval.bestMove == eval.moveSan) return null
            return lastMoveFrom
        }

    val actualArrowTo: String?
        get() {
            if (isExploreMode) return null
            val eval = currentMoveEval ?: return null
            if (eval.bestMove == eval.moveSan) return null
            return lastMoveTo
        }

    /** Classification of the current move — used by the screen to pick the actual-arrow color. */
    val actualMoveClassification: MoveClassification?
        get() = if (isExploreMode) null else currentMoveEval?.classification

    /**
     * Score bubble centipawn value — live in explore/practice mode, game analysis value in review.
     */
    val scoreBubbleCp: Int?
        get() = if (isExploreMode) liveBoardEvalCp else currentMoveEval?.evalAfter

    /**
     * Engine lines built from the stored multi-PV evaluation for the current position.
     * In explore mode, use [explorePvLines] directly instead.
     */
    val reviewEngineLines: List<EngineLine>
        get() {
            val eval = currentMoveEval ?: return emptyList()
            val lines = mutableListOf<EngineLine>()
            // Line 1: best move
            lines.add(EngineLine(
                rank = 1,
                moveSan = eval.bestMove,
                evalCp = eval.bestMoveEval,
                isMate = eval.isMate,
                mateIn = if (eval.isMate) eval.mateIn else null
            ))
            // Line 2: 2nd best move
            if (eval.secondBestMove.isNotBlank()) {
                lines.add(EngineLine(
                    rank = 2,
                    moveSan = eval.secondBestMove,
                    evalCp = eval.secondBestMoveEval ?: eval.bestMoveEval
                ))
            }
            // Line 3: 3rd best move
            if (eval.thirdBestMove.isNotBlank()) {
                lines.add(EngineLine(
                    rank = 3,
                    moveSan = eval.thirdBestMove,
                    evalCp = eval.thirdBestMoveEval ?: eval.bestMoveEval
                ))
            }
            return lines
        }

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
    private val gameRepository: GameRepository,
    private val positionEvaluator: PositionEvaluator,
    private val tablebaseService: LichessTablebaseService,
    private val openingService: LichessOpeningService,
    private val soundPlayer: ChessSoundPlayer
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
        val state = _uiState.value
        val newIndex = minOf(state.positions.size - 1, state.currentPositionIndex + 1)
        if (newIndex != state.currentPositionIndex) {
            val san = state.positions.getOrNull(newIndex)?.lastMoveSan ?: ""
            val fen = state.positions.getOrNull(newIndex)?.fen
            if (fen != null) {
                val board = try { ChessBoard.fromFen(fen) } catch (_: Exception) { null }
                val isCapture = 'x' in san
                if (board != null) soundPlayer.playMoveSound(board, isCapture)
                else if (isCapture) soundPlayer.playCapture() else soundPlayer.playMove()
            }
        }
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
            .setInputData(workDataOf(
                AnalysisWorker.KEY_GAME_ID to gameId,
                AnalysisWorker.KEY_IS_USER_REQUESTED to true
            ))
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

    // ── Explore mode ─────────────────────────────────────────────────────────

    /**
     * Called when the user taps a square on the board.
     * First tap selects a piece; second tap on a legal target moves it and triggers
     * a depth-10 Stockfish analysis of the resulting position.
     */
    fun onSquareTapped(square: String) {
        val state = _uiState.value
        val fen = state.exploreFen ?: state.currentFen
        val board = ChessBoard.fromFen(fen)
        val selected = state.selectedSquare

        if (selected == null) {
            // First tap — try to select a piece belonging to the side to move
            val piece = board.getPieceAt(square) ?: return
            val isWhitePiece = piece.isUpperCase()
            if (isWhitePiece != (board.activeColor == 'w')) return
            // In practice mode: prevent moving the engine's pieces
            if (state.isPracticeMode || state.isEngineThinking) {
                val engineIsWhite = !state.practicePlayerIsWhite
                val isEngineTurn = (engineIsWhite && board.activeColor == 'w') ||
                                   (!engineIsWhite && board.activeColor == 'b')
                if (isEngineTurn || state.isEngineThinking) return
            }
            val targets = board.legalTargetsFor(square)
            if (targets.isEmpty()) return
            _uiState.update {
                it.copy(
                    isExploreMode = true,
                    selectedSquare = square,
                    legalTargets = targets
                )
            }
        } else {
            val targets = board.legalTargetsFor(selected)
            when {
                square == selected -> {
                    // Tap same square → deselect
                    _uiState.update { it.copy(selectedSquare = null, legalTargets = emptySet()) }
                }
                square in targets -> {
                    // Play appropriate sound (check > capture > move)
                    val movingPiece = board.getPieceAt(selected) ?: ' '
                    val captureTarget = board.getPieceAt(square)
                    val isCapture = captureTarget != null || (movingPiece.lowercaseChar() == 'p' && selected[0] != square[0])
                    val newBoard = board.applyMoveSquares(selected, square)
                    soundPlayer.playMoveSound(newBoard, isCapture)
                    val newFen = newBoard.toFen()
                    val inPractice = state.isPracticeMode
                    _uiState.update {
                        it.copy(
                            isExploreMode = true,
                            selectedSquare = null,
                            legalTargets = emptySet(),
                            exploreFen = newFen,
                            exploreArrowFrom = null,
                            exploreArrowTo = null,
                            isExploreAnalyzing = !inPractice,
                            isEngineThinking = inPractice
                        )
                    }
                    if (inPractice) {
                        practiceEngineMove(newFen)
                    } else {
                        analyzeExploreFen(newFen)
                    }
                }
                else -> {
                    // Tap a different square — try to re-select another piece
                    val piece = board.getPieceAt(square)
                    if (piece != null && piece.isUpperCase() == (board.activeColor == 'w')) {
                        val newTargets = board.legalTargetsFor(square)
                        if (newTargets.isNotEmpty()) {
                            _uiState.update { it.copy(selectedSquare = square, legalTargets = newTargets) }
                            return
                        }
                    }
                    _uiState.update { it.copy(selectedSquare = null, legalTargets = emptySet()) }
                }
            }
        }
    }

    /** Return to the game position and exit explore / practice mode. */
    fun exitExploreMode() {
        _uiState.update {
            it.copy(
                isExploreMode = false,
                selectedSquare = null,
                legalTargets = emptySet(),
                exploreFen = null,
                exploreArrowFrom = null,
                exploreArrowTo = null,
                isExploreAnalyzing = false,
                explorePvLines = emptyList(),
                liveBoardEvalCp = null,
                tablebaseResult = null,
                isPracticeMode = false,
                isEngineThinking = false
            )
        }
    }

    /** Toggle practice mode (user plays one color vs engine). */
    fun togglePracticeMode(playerIsWhite: Boolean = true) {
        val current = _uiState.value.isPracticeMode
        if (current) {
            // Turning off — stay in explore mode but drop practice flag
            _uiState.update { it.copy(isPracticeMode = false, isEngineThinking = false) }
        } else {
            val state = _uiState.value
            val fen = state.exploreFen ?: state.currentFen
            val board = try { ChessBoard.fromFen(fen) } catch (_: Exception) { return }
            _uiState.update {
                it.copy(
                    isExploreMode = true,
                    isPracticeMode = true,
                    practicePlayerIsWhite = playerIsWhite,
                    isEngineThinking = false,
                    explorePvLines = emptyList()
                )
            }
            // If it's already the engine's turn, make the engine move immediately
            val engineIsWhite = !playerIsWhite
            if ((engineIsWhite && board.activeColor == 'w') ||
                (!engineIsWhite && board.activeColor == 'b')) {
                practiceEngineMove(fen)
            }
        }
    }

    /** Run a depth-10 Stockfish eval on [fen] and surface the best-move arrow + PV lines. */
    private fun analyzeExploreFen(fen: String) {
        viewModelScope.launch {
            try {
                val result = positionEvaluator.evaluateMultiPV(fen, depth = 10, mode = "local")
                val bestUci = result.bestMoveUci
                val pvLines = buildEngineLines(fen, result)
                if (bestUci != null && bestUci.length >= 4) {
                    _uiState.update {
                        it.copy(
                            exploreArrowFrom = bestUci.substring(0, 2),
                            exploreArrowTo   = bestUci.substring(2, 4),
                            isExploreAnalyzing = false,
                            explorePvLines = pvLines,
                            liveBoardEvalCp = result.effectiveCp
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isExploreAnalyzing = false, explorePvLines = pvLines,
                            liveBoardEvalCp = result.effectiveCp)
                    }
                }
                // Tablebase lookup when ≤7 pieces
                val piecePart = fen.split(" ").firstOrNull() ?: ""
                if (piecePart.count { it.isLetter() } <= 7) {
                    val tb = tablebaseService.lookup(fen)
                    _uiState.update { it.copy(tablebaseResult = tb) }
                } else {
                    _uiState.update { it.copy(tablebaseResult = null) }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isExploreAnalyzing = false) }
            }
        }
    }

    /** Engine responds to the user's move in practice mode. */
    private fun practiceEngineMove(fen: String) {
        viewModelScope.launch {
            try {
                delay(500) // let user see board before engine responds
                val result = positionEvaluator.evaluateMultiPV(fen, depth = 10, mode = "local")
                val bestUci = result.bestMoveUci ?: run {
                    _uiState.update { it.copy(isEngineThinking = false) }
                    return@launch
                }
                if (bestUci.length >= 4) {
                    val board = ChessBoard.fromFen(fen)
                    val newBoard = board.applyMoveSquares(
                        bestUci.substring(0, 2), bestUci.substring(2, 4)
                    )
                    val newFen = newBoard.toFen()
                    _uiState.update {
                        it.copy(
                            exploreFen = newFen,
                            exploreArrowFrom = bestUci.substring(0, 2),
                            exploreArrowTo   = bestUci.substring(2, 4),
                            isEngineThinking = false,
                            liveBoardEvalCp = result.effectiveCp
                        )
                    }
                    // Analyse the new position so engine lines are ready for user's next move
                    analyzeExploreFen(newFen)
                } else {
                    _uiState.update { it.copy(isEngineThinking = false) }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isEngineThinking = false) }
            }
        }
    }

    /** Fetch opening continuations for the current board position. */
    fun fetchOpeningContinuations(fen: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingOpening = true) }
            try {
                val result = openingService.fetchContinuations(fen)
                _uiState.update { it.copy(openingResult = result, isFetchingOpening = false) }
            } catch (_: Exception) {
                _uiState.update { it.copy(isFetchingOpening = false) }
            }
        }
    }

    /**
     * Build an annotated PGN string from the stored game and evaluations,
     * then surface it via [pgnExportText] for the screen to share.
     */
    fun exportAnnotatedPgn() {
        val state = _uiState.value
        val game = state.game ?: return
        val evals = state.evaluations
        val positions = state.positions
        val sb = StringBuilder()
        // Headers
        sb.appendLine("[Event \"Chess Analysis\"]")
        sb.appendLine("[Site \"lichess.org\"]")
        val dateStr = game.playedAt ?: "????.??.??"
        sb.appendLine("[Date \"$dateStr\"]")
        val isUserWhite = game.userColor?.name == "WHITE"
        sb.appendLine("[White \"${if (isUserWhite) "You" else (game.opponent ?: "?")}\"]")
        sb.appendLine("[Black \"${if (!isUserWhite) "You" else (game.opponent ?: "?")}\"]")
        sb.appendLine("[Result \"${game.result?.display ?: "*"}\"]")
        if (!game.opening.isNullOrBlank()) sb.appendLine("[Opening \"${game.opening}\"]")
        sb.appendLine()
        // Moves with symbols + eval comments
        for (i in 1 until positions.size) {
            val pos = positions[i]
            val moveSan = pos.lastMoveSan ?: continue
            val eval = evals.getOrNull(i - 1)
            if (pos.isWhiteMove) sb.append("${pos.moveNumber}. ")
            else if (i == 1) sb.append("${pos.moveNumber}... ")
            sb.append(moveSan)
            val symbol = eval?.classification?.symbol
            if (!symbol.isNullOrBlank()) sb.append(symbol)
            if (eval != null) {
                val evalStr = if (eval.isMate && eval.mateIn != null) {
                    "#${if (eval.mateIn > 0) eval.mateIn else -eval.mateIn}"
                } else {
                    val cp = eval.evalAfter / 100f
                    if (cp >= 0) "+%.2f".format(cp) else "%.2f".format(cp)
                }
                sb.append(" {[%eval $evalStr]}")
            }
            sb.append(' ')
        }
        sb.append(game.result?.display ?: "*")
        _uiState.update { it.copy(pgnExportText = sb.toString()) }
    }

    /** Clear the one-shot PGN export text once the screen has consumed it. */
    fun consumePgnExport() {
        _uiState.update { it.copy(pgnExportText = null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert PV lines from engine result into [EngineLine] list for display. */
    private fun buildEngineLines(fen: String, result: com.chessanalyzer.domain.engine.StockfishEngine.EvalResult): List<EngineLine> {
        val board = try { ChessBoard.fromFen(fen) } catch (_: Exception) { return emptyList() }
        return result.pvLines.mapIndexed { idx, pvLine ->
            val tokens = pvLine.pv.split(" ").filter { it.isNotBlank() }
            val firstUci = tokens.firstOrNull() ?: ""
            val moveSan = if (firstUci.length >= 4) {
                try { uciToDisplaySan(board, firstUci) } catch (_: Exception) { firstUci }
            } else firstUci
            val continuation = tokens.drop(1).take(5).joinToString(" ")
            val evalCp = if (pvLine.isMate && pvLine.mateIn != null) {
                val sign = if (pvLine.mateIn > 0) 1 else -1
                sign * (10000 - kotlin.math.abs(pvLine.mateIn) * 100)
            } else pvLine.centipawns
            EngineLine(
                rank = idx + 1,
                moveSan = moveSan,
                evalCp = evalCp,
                isMate = pvLine.isMate,
                mateIn = pvLine.mateIn,
                continuation = continuation
            )
        }
    }

    /** Lightweight UCI → display SAN (no full disambiguation; good enough for engine lines). */
    private fun uciToDisplaySan(board: ChessBoard, uci: String): String {
        if (uci.length < 4) return uci
        val from = uci.substring(0, 2)
        val to   = uci.substring(2, 4)
        val promo = if (uci.length > 4) uci[4] else null
        val piece = board.getPieceAt(from) ?: return uci
        if (piece.lowercaseChar() == 'k') {
            if (from[0] == 'e' && to[0] == 'g') return "O-O"
            if (from[0] == 'e' && to[0] == 'c') return "O-O-O"
        }
        val target = board.getPieceAt(to)
        val isCapture = target != null || (piece.lowercaseChar() == 'p' && from[0] != to[0])
        val sb = StringBuilder()
        if (piece.lowercaseChar() != 'p') sb.append(piece.uppercaseChar())
        if (piece.lowercaseChar() == 'p' && isCapture) sb.append(from[0])
        if (isCapture) sb.append('x')
        sb.append(to)
        if (promo != null) { sb.append('='); sb.append(promo.uppercaseChar()) }
        return sb.toString()
    }
}

