package com.chessanalyzer.domain.usecase

import android.util.Log
import com.chessanalyzer.data.local.db.MoveEvaluationEntity
import com.chessanalyzer.data.local.db.PositionEvalCacheDao
import com.chessanalyzer.data.local.db.PositionEvalCacheEntity
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.domain.chess.ChessBoard
import com.chessanalyzer.domain.chess.PgnParser
import com.chessanalyzer.domain.chess.indicesToSquare
import com.chessanalyzer.domain.engine.*
import com.chessanalyzer.domain.model.AnalysisStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/**
 * Phase 1 of the two-phase hybrid analysis.
 *
 * Calls Lichess cloud evaluation for every move of every pending game,
 * parallelising up to [CLOUD_CONCURRENCY] simultaneous HTTP requests.
 *
 * After all positions for a game are attempted:
 *  - Moves where both cloud evals succeeded → moveStatus = "cloud_hit"  (fully classified)
 *  - Moves with any missing cloud eval      → moveStatus = "needs_stockfish" (partial placeholder)
 *  - Game status is updated to "cloud_done" so Phase 2 can pick it up.
 *
 * Cloud results are also inserted into [PositionEvalCacheDao] so subsequent
 * re-analyses skip the network entirely.
 *
 * Design: all cloud calls run in parallel; rows are collected in memory, then
 * written to DB in one chunked batch at the end.  App-kill safety: the stuck-check
 * in [GamesViewModel] resets "analyzing" games back to "pending" on next launch,
 * so a killed cloud phase is cleanly retried at no data loss.
 */
class CloudPhaseUseCase @Inject constructor(
    private val gameRepository: GameRepository,
    private val cloudEngine: LichessCloudEngine,
    private val moveClassifier: MoveClassifier,
    private val positionEvalCacheDao: PositionEvalCacheDao
) {
    companion object {
        private const val TAG = "CloudPhaseUseCase"

        /** Max simultaneous Lichess HTTP requests. */
        private const val CLOUD_CONCURRENCY = 8

        /** Rows flushed to DB in one batch. */
        private const val BATCH_SIZE = 500
    }

    data class Progress(val processed: Int, val total: Int, val percentage: Float)

    private data class PositionTask(
        val gameId: String,
        val moveIndex: Int,     // 1-based half-move index within the game (for BOOK detection)
        val totalInGame: Int,
        val prevFen: String,
        val currentFen: String,
        val position: PgnParser.Position
    )

    /**
     * Run the cloud phase for [pendingGameIds].
     * [onProgress] is called after each position is resolved.
     */
    suspend fun invoke(
        pendingGameIds: List<String>,
        onProgress: suspend (Progress) -> Unit = {}
    ) {
        val tasks = mutableListOf<PositionTask>()
        val openingMoveLists = HashMap<String, List<String>>()

        // ── 1. Build task list (load PGN, mark games as ANALYZING) ───────────
        for (gameId in pendingGameIds) {
            gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.ANALYZING)
            gameRepository.deleteEvaluationsForGame(gameId)  // clear any stale data

            val game = gameRepository.getGameById(gameId)
            if (game == null) {
                Log.w(TAG, "Game $gameId not found, skipping")
                continue
            }

            val positions = PgnParser.parseToPositions(game.pgn)
            if (positions.size < 2) {
                Log.w(TAG, "Game $gameId has no parseable moves — marking cloud_done")
                gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.CLOUD_DONE)
                continue
            }

            val pgnGame = PgnParser.parsePgn(game.pgn)
            openingMoveLists[gameId] = pgnGame?.moves ?: emptyList()

            val totalMoves = positions.size - 1
            for (i in 1 until positions.size) {
                tasks.add(
                    PositionTask(
                        gameId    = gameId,
                        moveIndex = i,
                        totalInGame = totalMoves,
                        prevFen   = positions[i - 1].fen,
                        currentFen = positions[i].fen,
                        position  = positions[i]
                    )
                )
            }
        }

        if (tasks.isEmpty()) {
            Log.d(TAG, "No tasks to process — cloud phase done immediately")
            return
        }

        Log.i(TAG, "Starting cloud phase: ${tasks.size} positions across ${pendingGameIds.size} game(s)")
        val startMs = System.currentTimeMillis()

        // ── 2. Process all positions in parallel ─────────────────────────────
        val semaphore = Semaphore(CLOUD_CONCURRENCY)
        val rows = java.util.Collections.synchronizedList(mutableListOf<MoveEvaluationEntity>())
        val progressMutex = Mutex()
        var processedCount = 0

        coroutineScope {
            for (task in tasks) {
                launch {
                    semaphore.withPermit {
                        val row = processTask(task)
                        rows.add(row)
                        val count: Int
                        progressMutex.withLock {
                            processedCount++
                            count = processedCount
                        }
                        onProgress(Progress(count, tasks.size, count.toFloat() / tasks.size))
                    }
                }
            }
        }

        // ── 3. Bulk DB write (batched) ────────────────────────────────────────
        rows.chunked(BATCH_SIZE).forEach { chunk ->
            gameRepository.saveEvaluations(chunk)
        }
        Log.i(TAG, "Saved ${rows.size} evaluation rows")

        // ── 4. Per-game post-processing ───────────────────────────────────────
        val processedGameIds = tasks.map { it.gameId }.distinct()
        for (gameId in processedGameIds) {
            val opening = openingMoveLists[gameId]?.let { OpeningDetector.detect(it) }
            if (opening != null) gameRepository.updateOpening(gameId, opening)
            gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.CLOUD_DONE)
            Log.i(TAG, "[cloud] game $gameId → cloud_done")
        }

        Log.i(TAG, "Cloud phase complete in ${System.currentTimeMillis() - startMs}ms")
    }

    // ── Task processing ───────────────────────────────────────────────────────

    private suspend fun processTask(task: PositionTask): MoveEvaluationEntity {
        val startMs = System.currentTimeMillis()

        val beforeResult = try { cloudEngine.evaluate(task.prevFen, 3) } catch (_: Exception) { null }
        val afterResult  = try { cloudEngine.evaluate(task.currentFen, 1) } catch (_: Exception) { null }

        val elapsedMs = System.currentTimeMillis() - startMs
        val pos = task.position
        val isWhite = pos.isWhiteMove
        val color = if (isWhite) "white" else "black"

        return if (beforeResult != null && afterResult != null) {
            Log.d(TAG,
                "[hybrid] ☁️ cloud hit game=${task.gameId} move=${pos.moveNumber} " +
                "${pos.lastMoveSan} ${elapsedMs}ms")

            // Populate eval cache for both positions
            cacheResult(task.prevFen, 3, beforeResult)
            cacheResult(task.currentFen, 1, afterResult)

            buildFullRow(task, color, isWhite, beforeResult, afterResult)
        } else {
            Log.d(TAG,
                "[hybrid] ☁️ cloud miss → 🔧 Stockfish game=${task.gameId} " +
                "move=${pos.moveNumber} ${elapsedMs}ms")
            buildPartialRow(task, color, beforeResult, afterResult)
        }
    }

    private suspend fun cacheResult(
        fen: String,
        multiPv: Int,
        result: StockfishEngine.EvalResult
    ) {
        val mpvTag = if (multiPv == 1) "mpv1" else "mpv3"
        val secondCp = if (multiPv >= 3) {
            result.pvLines.getOrNull(1)?.let { pv ->
                if (pv.isMate && pv.mateIn != null) {
                    val sign = if (pv.mateIn > 0) 1 else -1
                    sign * (10000 - kotlin.math.abs(pv.mateIn) * 100)
                } else pv.centipawns
            }
        } else null

        positionEvalCacheDao.insert(
            PositionEvalCacheEntity(
                cacheKey    = "$fen|cloud|$mpvTag",
                cpWhite     = result.effectiveCp,
                bestMoveUci = result.bestMoveUci,
                secondCpWhite = secondCp,
                isMate      = result.isMate,
                mateIn      = result.mateIn
            )
        )
    }

    /** Build a fully classified row — both cloud evals available. */
    private fun buildFullRow(
        task: PositionTask,
        color: String,
        isWhite: Boolean,
        beforeResult: StockfishEngine.EvalResult,
        afterResult: StockfishEngine.EvalResult
    ): MoveEvaluationEntity {
        val pos = task.position
        val moveSan = pos.lastMoveSan ?: ""
        val bestEval = beforeResult.effectiveCp        // White's perspective
        val actualEval = afterResult.effectiveCp       // White's perspective

        val secondBestEval = beforeResult.pvLines.getOrNull(1)?.let { pv ->
            if (pv.isMate && pv.mateIn != null) {
                val sign = if (pv.mateIn > 0) 1 else -1
                sign * (10000 - kotlin.math.abs(pv.mateIn) * 100)
            } else pv.centipawns
        }

        val bestMoveUci = beforeResult.bestMoveUci
        val bestMoveSan = try {
            uciToSan(ChessBoard.fromFen(task.prevFen), bestMoveUci) ?: bestMoveUci
        } catch (_: Exception) { bestMoveUci }

        val isBestMove = bestMoveUci == sanToUci(ChessBoard.fromFen(task.prevFen), moveSan)
        val materialBefore = GamePhaseDetector.totalMaterialFromFen(task.prevFen)
        val gamePhase = GamePhaseDetector.detectPhase(materialBefore)

        val classification = moveClassifier.classify(
            MoveClassifier.ClassifyInput(
                evalBefore       = bestEval,
                evalAfter        = actualEval,
                bestMoveEval     = bestEval,
                secondBestMoveEval = secondBestEval,
                isWhite          = isWhite,
                isBestMove       = isBestMove,
                moveIndex        = task.moveIndex,
                materialBefore   = if (isWhite)
                    GamePhaseDetector.playerMaterialFromFen(task.prevFen, true)
                else
                    GamePhaseDetector.playerMaterialFromFen(task.prevFen, false),
                materialAfter    = if (isWhite)
                    GamePhaseDetector.playerMaterialFromFen(task.currentFen, true)
                else
                    GamePhaseDetector.playerMaterialFromFen(task.currentFen, false)
            )
        )

        val prevCpForPlayer = if (isWhite) bestEval else -bestEval
        val nextCpForPlayer = if (isWhite) actualEval else -actualEval
        val accuracy = GameMetrics.moveAccuracy(prevCpForPlayer, nextCpForPlayer)
        val winProbBefore = GameMetrics.winProbability(bestEval)
        val winProbAfter  = GameMetrics.winProbability(actualEval)
        val isKeyMoment   = GameMetrics.isKeyMoment(bestEval, actualEval)
        val isTurningPoint = GameMetrics.isTurningPoint(bestEval, actualEval)

        return MoveEvaluationEntity(
            gameId             = task.gameId,
            moveNumber         = pos.moveNumber,
            color              = color,
            moveSan            = moveSan,
            fen                = task.currentFen,
            fenBefore          = task.prevFen,
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
            moveStatus         = "cloud_hit",
            evalBeforeSource   = "cloud",
            evalAfterSource    = "cloud"
        )
    }

    /**
     * Build a partial row where at least one cloud eval is missing.
     * Placeholders are used; Phase 2 will overwrite with Stockfish results.
     */
    private fun buildPartialRow(
        task: PositionTask,
        color: String,
        beforeResult: StockfishEngine.EvalResult?,
        afterResult: StockfishEngine.EvalResult?
    ): MoveEvaluationEntity {
        val pos = task.position
        val evalBefore = beforeResult?.effectiveCp ?: 0
        val evalAfter  = afterResult?.effectiveCp  ?: 0
        val evalBeforeSource = if (beforeResult != null) "cloud" else "pending"
        val evalAfterSource  = if (afterResult  != null) "cloud" else "pending"
        val materialBefore = GamePhaseDetector.totalMaterialFromFen(task.prevFen)

        return MoveEvaluationEntity(
            gameId             = task.gameId,
            moveNumber         = pos.moveNumber,
            color              = color,
            moveSan            = pos.lastMoveSan ?: "",
            fen                = task.currentFen,
            fenBefore          = task.prevFen,
            evalBefore         = evalBefore,
            evalAfter          = evalAfter,
            bestMove           = "",               // filled by Phase 2
            bestMoveEval       = evalBefore,
            secondBestMoveEval = null,
            classification     = "good",           // placeholder; overwritten by Phase 2
            isMate             = afterResult?.isMate ?: false,
            mateIn             = afterResult?.mateIn,
            winProbBefore      = GameMetrics.winProbability(evalBefore),
            winProbAfter       = GameMetrics.winProbability(evalAfter),
            accuracyScore      = 50f,              // placeholder
            isKeyMoment        = false,
            isTurningPoint     = false,
            gamePhase          = GamePhaseDetector.detectPhase(materialBefore).toDbString(),
            moveStatus         = "needs_stockfish",
            evalBeforeSource   = evalBeforeSource,
            evalAfterSource    = evalAfterSource
        )
    }

    // ── UCI / SAN helpers (mirrors AnalyzeGameUseCase) ────────────────────────

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
