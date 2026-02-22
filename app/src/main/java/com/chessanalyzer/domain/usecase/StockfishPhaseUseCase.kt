package com.chessanalyzer.domain.usecase

import android.util.Log
import com.chessanalyzer.data.local.db.MoveEvaluationEntity
import com.chessanalyzer.data.local.preferences.UserPreferences
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.domain.chess.ChessBoard
import com.chessanalyzer.domain.chess.indicesToSquare
import com.chessanalyzer.domain.engine.*
import com.chessanalyzer.domain.model.AnalysisStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Phase 2 of the two-phase hybrid analysis.
 *
 * Picks up [BATCH_GAMES] "cloud_done" games at a time, runs Stockfish for every
 * move that has moveStatus="needs_stockfish", then finalises each game with
 * overall accuracy and marks it "done".
 *
 * Stockfish access is serialised through [AnalyzeGameUseCase.ENGINE_MUTEX] —
 * the same mutex used by single-game analysis — so the engine is never
 * accessed concurrently.
 *
 * Returns [Result] which tells the caller whether more cloud_done games remain
 * (so [StockfishAnalysisWorker] can re-enqueue itself).
 */
class StockfishPhaseUseCase @Inject constructor(
    private val gameRepository: GameRepository,
    private val stockfishEngine: StockfishEngine,
    private val moveClassifier: MoveClassifier,
    private val userPreferences: UserPreferences
) {
    companion object {
        private const val TAG = "StockfishPhaseUseCase"

        /** Games processed per worker invocation. */
        private const val BATCH_GAMES = 5
    }

    data class Progress(val doneGames: Int, val totalGames: Int, val percentage: Float)

    data class Result(
        /** True when there are still cloud_done games waiting after this batch. */
        val hasMoreGames: Boolean
    )

    /**
     * Process one batch of cloud_done games.
     * [onProgress] is called after each game is fully completed.
     */
    suspend fun invoke(onProgress: suspend (Progress) -> Unit = {}): Result {
        val gameIds = gameRepository.getCloudDoneGameIds(BATCH_GAMES)
        if (gameIds.isEmpty()) {
            Log.d(TAG, "No cloud_done games found — Stockfish phase done")
            return Result(hasMoreGames = false)
        }

        val depth = userPreferences.engineDepth.first()
        Log.i(TAG, "Stockfish phase: processing ${gameIds.size} game(s) at depth $depth")
        val startMs = System.currentTimeMillis()

        AnalyzeGameUseCase.ENGINE_MUTEX.withLock {
            stockfishEngine.start()
            try {
                for ((idx, gameId) in gameIds.withIndex()) {
                    processGame(gameId, depth)
                    val done = idx + 1
                    onProgress(Progress(done, gameIds.size, done.toFloat() / gameIds.size))
                    Log.i(TAG, "[stockfish] game $gameId done ($done/${gameIds.size})")
                }
            } finally {
                stockfishEngine.stop()
            }
        }

        Log.i(TAG, "Stockfish phase batch done in ${System.currentTimeMillis() - startMs}ms")

        // Check if more cloud_done games remain after processing this batch
        val remaining = gameRepository.getCloudDoneGameIds(1)
        return Result(hasMoreGames = remaining.isNotEmpty())
    }

    // ── Per-game processing ───────────────────────────────────────────────────

    private suspend fun processGame(gameId: String, depth: Int) {
        gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.ANALYZING)

        val needsStockfish = gameRepository.getNeedsStockfishRows(gameId)
        Log.d(TAG, "[stockfish] game $gameId: ${needsStockfish.size} move(s) need Stockfish")

        for (row in needsStockfish) {
            val updatedRow = fillWithStockfish(row, depth)
            gameRepository.updateEvaluation(updatedRow)
        }

        // Reload ALL rows for this game (including cloud_hit ones) to compute accuracy
        val allRows = gameRepository.getEvaluationsEntityForGame(gameId)
        val whiteAccuracy = GameMetrics.overallAccuracy(
            allRows.filter { it.color == "white" }.map { it.accuracyScore }
        )
        val blackAccuracy = GameMetrics.overallAccuracy(
            allRows.filter { it.color == "black" }.map { it.accuracyScore }
        )

        gameRepository.updateAnalysisResult(gameId, whiteAccuracy, blackAccuracy)
        // updateAnalysisResult sets status = 'done' in DB
    }

    /**
     * Evaluate missing evals with Stockfish and build the fully classified row.
     */
    private suspend fun fillWithStockfish(
        row: MoveEvaluationEntity,
        depth: Int
    ): MoveEvaluationEntity {
        val prevFen = row.fenBefore
        val currentFen = row.fen
        val isWhite = row.color == "white"
        val moveIndex = row.moveNumber * 2 - (if (isWhite) 1 else 0)  // approx half-move index

        // Evaluate positions that are still pending
        val beforeResult: StockfishEngine.EvalResult = if (row.evalBeforeSource == "pending") {
            stockfishEngine.evaluate(prevFen, depth, multiPv = 3)
        } else {
            // Already have a cloud eval for before — reconstruct a minimal EvalResult
            StockfishEngine.EvalResult(
                centipawns  = row.evalBefore,
                isMate      = false,
                mateIn      = null,
                bestMoveUci = "", // will be recomputed from realBeforeResult
                pvLines     = emptyList()
            )
        }

        val afterResult: StockfishEngine.EvalResult = if (row.evalAfterSource == "pending") {
            stockfishEngine.evaluate(currentFen, depth, multiPv = 1)
        } else {
            StockfishEngine.EvalResult(
                centipawns = row.evalAfter,
                isMate     = row.isMate,
                mateIn     = row.mateIn,
                bestMoveUci = "",
                pvLines     = emptyList()
            )
        }

        // If we ran Stockfish for before (most common: both pending), use its bestMove.
        // If before was already cloud, we need to re-run at mpv=3 to get secondBest for
        // classification.  Most moves that reach here have BOTH as "pending", so this is fast.
        val realBeforeResult: StockfishEngine.EvalResult = if (row.evalBeforeSource == "pending") {
            beforeResult
        } else {
            // before was cloud — but we need bestMoveUci and secondBest for classification.
            // Re-run Stockfish for the before position.
            stockfishEngine.evaluate(prevFen, depth, multiPv = 3)
        }

        val bestEval   = realBeforeResult.effectiveCp
        val actualEval = afterResult.effectiveCp

        val secondBestEval = realBeforeResult.pvLines.getOrNull(1)?.let { pv ->
            if (pv.isMate && pv.mateIn != null) {
                val sign = if (pv.mateIn > 0) 1 else -1
                sign * (10000 - kotlin.math.abs(pv.mateIn) * 100)
            } else pv.centipawns
        }

        val bestMoveUci = realBeforeResult.bestMoveUci
        val bestMoveSan = try {
            uciToSan(ChessBoard.fromFen(prevFen), bestMoveUci) ?: bestMoveUci
        } catch (_: Exception) { bestMoveUci }
        val isBestMove = bestMoveUci == sanToUci(ChessBoard.fromFen(prevFen), row.moveSan)
        val materialBefore = GamePhaseDetector.totalMaterialFromFen(prevFen)
        val gamePhase = GamePhaseDetector.detectPhase(materialBefore)

        val classification = moveClassifier.classify(
            MoveClassifier.ClassifyInput(
                evalBefore         = bestEval,
                evalAfter          = actualEval,
                bestMoveEval       = bestEval,
                secondBestMoveEval = secondBestEval,
                isWhite            = isWhite,
                isBestMove         = isBestMove,
                moveIndex          = moveIndex,
                materialBefore     = if (isWhite)
                    GamePhaseDetector.playerMaterialFromFen(prevFen, true)
                else
                    GamePhaseDetector.playerMaterialFromFen(prevFen, false),
                materialAfter      = if (isWhite)
                    GamePhaseDetector.playerMaterialFromFen(currentFen, true)
                else
                    GamePhaseDetector.playerMaterialFromFen(currentFen, false)
            )
        )

        val prevCpForPlayer = if (isWhite) bestEval else -bestEval
        val nextCpForPlayer = if (isWhite) actualEval else -actualEval
        val accuracy        = GameMetrics.moveAccuracy(prevCpForPlayer, nextCpForPlayer)
        val winProbBefore   = GameMetrics.winProbability(bestEval)
        val winProbAfter    = GameMetrics.winProbability(actualEval)
        val isKeyMoment     = GameMetrics.isKeyMoment(bestEval, actualEval)
        val isTurningPoint  = GameMetrics.isTurningPoint(bestEval, actualEval)

        Log.d(TAG, "[stockfish] game=${row.gameId} move=${row.moveNumber} ${row.color} " +
              "${row.moveSan}: bestEval=$bestEval actualEval=$actualEval " +
              "acc=${"%.1f".format(accuracy)}% → ${classification.toDbString()}")

        return row.copy(
            evalBefore         = bestEval,
            evalAfter          = actualEval,
            bestMove           = bestMoveSan,
            bestMoveEval       = bestEval,
            secondBestMoveEval = secondBestEval,
            classification     = classification.toDbString(),
            isMate             = afterResult.isMate,
            mateIn             = afterResult.mateIn,
            winProbBefore      = winProbBefore,
            winProbAfter       = winProbAfter,
            accuracyScore      = accuracy,
            isKeyMoment        = isKeyMoment,
            isTurningPoint     = isTurningPoint,
            gamePhase          = gamePhase.toDbString(),
            moveStatus         = "stockfish_done",
            evalBeforeSource   = "stockfish",
            evalAfterSource    = "stockfish"
        )
    }

    // ── UCI / SAN helpers ─────────────────────────────────────────────────────

    private fun uciToSan(board: ChessBoard, uci: String): String? {
        if (uci.length < 4) return null
        val fromSquare = uci.substring(0, 2)
        val toSquare   = uci.substring(2, 4)
        val promotion  = if (uci.length > 4) uci[4] else null
        val piece = board.getPieceAt(fromSquare) ?: return null
        if (piece.lowercaseChar() == 'k') {
            if (fromSquare[0] == 'e' && toSquare[0] == 'g') return "O-O"
            if (fromSquare[0] == 'e' && toSquare[0] == 'c') return "O-O-O"
        }
        val targetPiece = board.getPieceAt(toSquare)
        val isCapture = targetPiece != null ||
            (piece.lowercaseChar() == 'p' && fromSquare[0] != toSquare[0])
        val sb = StringBuilder()
        if (piece.lowercaseChar() != 'p') sb.append(piece.uppercaseChar())
        if (piece.lowercaseChar() == 'p' && isCapture) sb.append(fromSquare[0])
        if (isCapture) sb.append('x')
        sb.append(toSquare)
        if (promotion != null) { sb.append('='); sb.append(promotion.uppercaseChar()) }
        return sb.toString()
    }

    private fun sanToUci(board: ChessBoard, san: String): String? {
        val move = board.parseSanMove(san) ?: return null
        val from = indicesToSquare(move.fromRank, move.fromFile)
        val to   = indicesToSquare(move.toRank, move.toFile)
        val promo = move.promotion?.lowercaseChar()?.toString() ?: ""
        return "$from$to$promo"
    }
}
