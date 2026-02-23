package com.chessanalyzer.ui.screens.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chessanalyzer.data.local.db.MoveEvaluationDao
import com.chessanalyzer.data.local.db.PracticePuzzleRow
import com.chessanalyzer.data.local.db.PracticeSolvedDao
import com.chessanalyzer.data.local.db.PracticeSolvedEntity
import com.chessanalyzer.domain.chess.ChessBoard
import com.chessanalyzer.domain.chess.indicesToSquare
import com.chessanalyzer.domain.chess.squareToIndices
import com.chessanalyzer.audio.ChessSoundPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Settings ─────────────────────────────────────────────────────────────────

data class PracticeSettings(
    val daysBack: Int = 30,
    val classifications: Set<String> = setOf("inaccuracy", "mistake", "blunder")
)

// ── UiState ───────────────────────────────────────────────────────────────────

data class PracticeUiState(
    val isLoading: Boolean = true,
    val puzzle: ActivePuzzle? = null,
    val queueSize: Int = 0,           // total puzzles available this session
    val solvedThisSession: Int = 0,
    val allSolved: Boolean = false,   // queue exhausted
    val showSuccess: Boolean = false,
    val showWrong: Boolean = false,
    val showHint: Boolean = false,
    val hintFromSquare: String? = null,
    val hintToSquare: String? = null,
    val settings: PracticeSettings = PracticeSettings(),
    val showSettings: Boolean = false,
    val selectedSquare: String? = null,
    val legalTargets: Set<String> = emptySet()
)

data class ActivePuzzle(
    val key: String,         // positionKey in practice_solved
    val row: PracticePuzzleRow,
    val boardFen: String,    // == row.fenBefore
    val isUserWhite: Boolean // true → board as-is; false → flipped
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class PracticeViewModel @Inject constructor(
    private val moveEvalDao: MoveEvaluationDao,
    private val solvedDao: PracticeSolvedDao,
    private val soundPlayer: ChessSoundPlayer
) : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    /** Queue of raw rows not yet solved this session. */
    private var puzzleQueue: ArrayDeque<PracticePuzzleRow> = ArrayDeque()

    init {
        loadPuzzles()
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun setDaysBack(days: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(daysBack = days)) }
        loadPuzzles()
    }

    fun toggleClassification(cls: String) {
        val current = _uiState.value.settings.classifications.toMutableSet()
        if (cls in current) current.remove(cls) else current.add(cls)
        if (current.isEmpty()) return // keep at least one
        _uiState.update { it.copy(settings = it.settings.copy(classifications = current)) }
        loadPuzzles()
    }

    // ── Puzzle loading ────────────────────────────────────────────────────────

    fun loadPuzzles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, allSolved = false) }
            val settings = _uiState.value.settings
            val cutoffMs = System.currentTimeMillis() - settings.daysBack * 24L * 60 * 60 * 1000

            val solved = solvedDao.getAllSolvedKeys().toSet()
            val rows = moveEvalDao.getPracticePuzzles(
                classifications = settings.classifications.toList(),
                cutoffMs = cutoffMs
            )

            // Filter out already-solved and ensure fenBefore is a valid FEN
            val unsolved = rows.filter { row ->
                val key = "moveEvalId_${row.id}"
                key !in solved && row.fenBefore.contains(' ')
            }

            puzzleQueue = ArrayDeque(unsolved)
            val sessionSolved = solvedDao.getSolvedCount()

            _uiState.update { it.copy(
                isLoading = false,
                queueSize = unsolved.size,
                solvedThisSession = sessionSolved,
                allSolved = unsolved.isEmpty()
            )}

            if (unsolved.isNotEmpty()) showNextPuzzle()
        }
    }

    fun resetProgress() {
        viewModelScope.launch {
            solvedDao.clearAll()
            loadPuzzles()
        }
    }

    private fun showNextPuzzle() {
        val row = puzzleQueue.removeFirstOrNull()
        if (row == null) {
            _uiState.update { it.copy(puzzle = null, allSolved = true) }
            return
        }
        val isUserWhite = row.userColor.uppercase() == "WHITE"
        _uiState.update {
            it.copy(
                puzzle = ActivePuzzle(
                    key = "moveEvalId_${row.id}",
                    row = row,
                    boardFen = row.fenBefore,
                    isUserWhite = isUserWhite
                ),
                showSuccess = false,
                showWrong = false,
                showHint = false,
                hintFromSquare = null,
                hintToSquare = null,
                selectedSquare = null,
                legalTargets = emptySet()
            )
        }
    }

    fun skipPuzzle() {
        _uiState.update { it.copy(showWrong = false, showSuccess = false, showHint = false, hintFromSquare = null, hintToSquare = null, selectedSquare = null, legalTargets = emptySet()) }
        showNextPuzzle()
    }

    fun showHint() {
        val puzzle = _uiState.value.puzzle ?: return
        val board = try { ChessBoard.fromFen(puzzle.boardFen) } catch (_: Exception) { return }
        val move = try { board.parseSanMove(puzzle.row.bestMove) } catch (_: Exception) { return } ?: return
        val fromSq = indicesToSquare(move.fromRank, move.fromFile)
        val toSq   = indicesToSquare(move.toRank,   move.toFile)
        _uiState.update { it.copy(
            showHint = true,
            hintFromSquare = fromSq,
            hintToSquare = toSq,
            selectedSquare = null,
            legalTargets = emptySet()
        )}
    }

    // ── Board interaction ─────────────────────────────────────────────────────

    fun onSquareTapped(square: String) {
        val state = _uiState.value
        if (state.showSuccess || state.puzzle == null) return

        val board = try { ChessBoard.fromFen(state.puzzle.boardFen) } catch (_: Exception) { return }
        val selected = state.selectedSquare

        if (selected == null) {
            // First tap — select piece belonging to side to move
            val piece = board.getPieceAt(square) ?: return
            if (piece.isUpperCase() != (board.activeColor == 'w')) return
            val targets = board.legalTargetsFor(square)
            if (targets.isEmpty()) return
            _uiState.update { it.copy(selectedSquare = square, legalTargets = targets, showWrong = false) }
        } else {
            when {
                square == selected -> {
                    _uiState.update { it.copy(selectedSquare = null, legalTargets = emptySet()) }
                }
                square in state.legalTargets -> {
                    // Play capture sound if destination has a piece or it's a pawn diagonal (en passant)
                    val movingPiece = board.getPieceAt(selected) ?: ' '
                    val captureTarget = board.getPieceAt(square)
                    val isCapture = captureTarget != null || (movingPiece.lowercaseChar() == 'p' && selected[0] != square[0])
                    val newBoard = board.applyMoveSquares(selected, square)
                    soundPlayer.playMoveSound(newBoard, isCapture)
                    evaluateMove(board, selected, square)
                }
                else -> {
                    // Try re-select another piece
                    val piece = board.getPieceAt(square)
                    if (piece != null && piece.isUpperCase() == (board.activeColor == 'w')) {
                        val targets = board.legalTargetsFor(square)
                        if (targets.isNotEmpty()) {
                            _uiState.update { it.copy(selectedSquare = square, legalTargets = targets) }
                            return
                        }
                    }
                    _uiState.update { it.copy(selectedSquare = null, legalTargets = emptySet()) }
                }
            }
        }
    }

    private fun evaluateMove(board: ChessBoard, fromSquare: String, toSquare: String) {
        val puzzle = _uiState.value.puzzle ?: return
        val bestMoveSan = puzzle.row.bestMove

        // Convert bestMove SAN to from/to squares for comparison
        val bestMoveObj = try { board.parseSanMove(bestMoveSan) } catch (_: Exception) { null }
        val (fromR, fromF) = try { squareToIndices(fromSquare) } catch (_: Exception) { return }
        val (toR, toF)     = try { squareToIndices(toSquare) }   catch (_: Exception) { return }

        val isCorrect = bestMoveObj != null &&
                bestMoveObj.fromRank == fromR && bestMoveObj.fromFile == fromF &&
                bestMoveObj.toRank   == toR   && bestMoveObj.toFile   == toF

        _uiState.update { it.copy(selectedSquare = null, legalTargets = emptySet()) }

        if (isCorrect) {
            _uiState.update { it.copy(showSuccess = true, showWrong = false) }
            viewModelScope.launch {
                // Save as solved
                solvedDao.insert(PracticeSolvedEntity(positionKey = puzzle.key))
                val newCount = solvedDao.getSolvedCount()
                _uiState.update { it.copy(solvedThisSession = newCount) }
                delay(2000)
                showNextPuzzle()
            }
        } else {
            soundPlayer.playError()
            _uiState.update { it.copy(showWrong = true) }
            viewModelScope.launch {
                delay(900)
                _uiState.update { it.copy(showWrong = false) }
            }
        }
    }
}
