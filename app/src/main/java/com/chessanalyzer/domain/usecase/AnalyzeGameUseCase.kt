package com.chessanalyzer.domain.usecase

import android.util.Log
import com.chessanalyzer.data.local.db.MoveEvaluationEntity
import com.chessanalyzer.data.local.preferences.UserPreferences
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.domain.chess.ChessBoard
import com.chessanalyzer.domain.chess.PgnParser
import com.chessanalyzer.domain.engine.*
import com.chessanalyzer.domain.model.AnalysisStatus
import com.chessanalyzer.domain.model.PieceColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Complete game analysis flow per spec §15-16:
 *
 * FOR each move:
 *   1. Analyze position_before with MultiPV=3 → best_move, best_eval, second_eval
 *   2. Apply actual_move
 *   3. Analyze position_after with MultiPV=1 → actual_eval
 *   4. Compute eval_loss, classify move, compute accuracy, win probability
 *
 * Then aggregate accuracy, compute performance rating, detect key moments.
 */
class AnalyzeGameUseCase @Inject constructor(
    private val gameRepository: GameRepository,
    private val stockfishEngine: StockfishEngine,
    private val positionEvaluator: PositionEvaluator,
    private val moveClassifier: MoveClassifier,
    private val userPreferences: UserPreferences
) {
    companion object {
        /** Ensures only one AnalyzeGameUseCase invocation uses Stockfish at a time,
         *  regardless of which WorkManager queue triggered it. */
        val ENGINE_MUTEX = Mutex()
    }
    data class AnalysisProgress(
        val currentMove: Int,
        val totalMoves: Int,
        val percentage: Float
    )

    fun invoke(gameId: String): Flow<AnalysisProgress> = flow {
        val game = gameRepository.getGameById(gameId) ?: return@flow

        gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.ANALYZING)

        ENGINE_MUTEX.withLock {

        val depth = userPreferences.engineDepth.first()
        val mode = userPreferences.engineMode.first() // "local" | "cloud" | "hybrid"

        try {
            val positions = PgnParser.parseToPositions(game.pgn)

            if (positions.isEmpty()) {
                gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.PENDING)
                return@flow
            }

            // Detect opening from move list
            val pgnGame = PgnParser.parsePgn(game.pgn)
            val moveList = pgnGame?.moves ?: emptyList()
            val openingName = OpeningDetector.detect(moveList)

            // Start Stockfish for local and hybrid modes (hybrid uses it as fallback)
            if (mode != "cloud") stockfishEngine.start()
            positionEvaluator.clearCache()

            val evaluations = mutableListOf<MoveEvaluationEntity>()
            val totalMoves = positions.size - 1
            val gameStartMs = System.currentTimeMillis()
            Log.i("ChessAccuracy", "=== START game=$gameId mode=$mode moves=$totalMoves ===")

            for (i in 1 until positions.size) {
                // Emit "start of move" progress so the bar advances immediately
                val moveStart = (i - 1).toFloat() / totalMoves
                emit(AnalysisProgress(i, totalMoves, moveStart))

                val prevFen = positions[i - 1].fen
                val currentFen = positions[i].fen
                val moveSan = positions[i].lastMoveSan ?: continue
                val isWhite = positions[i].isWhiteMove
                val moveNumber = positions[i].moveNumber
                val color = if (isWhite) "white" else "black"

                // Step 1: Analyze position BEFORE with MultiPV=3
                val beforeEval = positionEvaluator.evaluateMultiPV(prevFen, depth, mode)

                // Emit mid-move progress (halfway through this move's two engine calls)
                val moveMid = (i - 0.5f) / totalMoves
                emit(AnalysisProgress(i, totalMoves, moveMid))

                // Step 2: Analyze position AFTER actual move with MultiPV=1
                val afterEval = positionEvaluator.evaluateSingle(currentFen, depth, mode)

                // Extract PV line evaluations
                val bestEval = beforeEval.effectiveCp          // White's perspective
                val secondBestEval = beforeEval.pvLines.getOrNull(1)?.let { pvLine ->
                    if (pvLine.isMate && pvLine.mateIn != null) {
                        val sign = if (pvLine.mateIn > 0) 1 else -1
                        sign * (10000 - kotlin.math.abs(pvLine.mateIn) * 100)
                    } else pvLine.centipawns
                }
                val actualEval = afterEval.effectiveCp         // White's perspective

                // Best move UCI from engine
                val bestMoveUci = beforeEval.bestMoveUci
                // Convert UCI to SAN (best effort — use UCI as fallback)
                val bestMoveSan = try {
                    val board = ChessBoard.fromFen(prevFen)
                    uciToSan(board, bestMoveUci) ?: bestMoveUci
                } catch (_: Exception) { bestMoveUci }

                val isBestMove = bestMoveUci == sanToUci(ChessBoard.fromFen(prevFen), moveSan)

                // Game phase detection per spec §9
                val materialBefore = GamePhaseDetector.totalMaterialFromFen(prevFen)
                val gamePhase = GamePhaseDetector.detectPhase(materialBefore)

                // Classify move per spec §4-7
                val classification = moveClassifier.classify(
                    MoveClassifier.ClassifyInput(
                        evalBefore = bestEval,
                        evalAfter = actualEval,
                        bestMoveEval = bestEval,
                        secondBestMoveEval = secondBestEval,
                        isWhite = isWhite,
                        isBestMove = isBestMove,
                        moveIndex = i,
                        materialBefore = if (isWhite) GamePhaseDetector.playerMaterialFromFen(prevFen, true)
                                        else GamePhaseDetector.playerMaterialFromFen(prevFen, false),
                        materialAfter = if (isWhite) GamePhaseDetector.playerMaterialFromFen(currentFen, true)
                                       else GamePhaseDetector.playerMaterialFromFen(currentFen, false)
                    )
                )

                // Accuracy per move — Lichess formula (same as pawn-appetit).
                // Convert to player's perspective; effectiveCp for mate can be large (±9900),
                // but getWinChance() caps internally at ±1000 cp.
                val prevCpForPlayer = if (isWhite) bestEval else -bestEval
                val nextCpForPlayer = if (isWhite) actualEval else -actualEval
                val accuracy = GameMetrics.moveAccuracy(prevCpForPlayer, nextCpForPlayer)

                Log.d("ChessAccuracy",
                    "[$color] move $moveNumber $moveSan: " +
                    "bestEval(white)=$bestEval actualEval(white)=$actualEval | " +
                    "playerPrev=$prevCpForPlayer playerNext=$nextCpForPlayer | " +
                    "acc=${"%.1f".format(accuracy)}%")

                // Win probability per spec §12
                val winProbBefore = GameMetrics.winProbability(bestEval)
                val winProbAfter = GameMetrics.winProbability(actualEval)

                // Key moment / turning point per spec §11
                val isKeyMoment = GameMetrics.isKeyMoment(bestEval, actualEval)
                val isTurningPoint = GameMetrics.isTurningPoint(bestEval, actualEval)

                evaluations.add(
                    MoveEvaluationEntity(
                        gameId = gameId,
                        moveNumber = moveNumber,
                        color = color,
                        moveSan = moveSan,
                        fen = currentFen,
                        fenBefore = prevFen,
                        evalBefore = bestEval,
                        evalAfter = actualEval,
                        bestMove = bestMoveSan,
                        bestMoveEval = bestEval,
                        secondBestMoveEval = secondBestEval,
                        classification = classification.toDbString(),
                        isMate = afterEval.isMate,
                        mateIn = afterEval.mateIn,
                        winProbBefore = winProbBefore,
                        winProbAfter = winProbAfter,
                        accuracyScore = accuracy,
                        isKeyMoment = isKeyMoment,
                        isTurningPoint = isTurningPoint,
                        gamePhase = gamePhase.toDbString()
                    )
                )
            }

            if (mode != "cloud") stockfishEngine.stop()

            // Save evaluations
            gameRepository.deleteEvaluationsForGame(gameId)
            gameRepository.saveEvaluations(evaluations)

            // Calculate overall accuracy per spec §8 (weighted)
            val whiteEvals = evaluations.filter { it.color == "white" }
            val blackEvals = evaluations.filter { it.color == "black" }

            // Overall accuracy = harmonic mean of per-move accuracies (same as pawn-appetit).
            val whiteAccuracy = GameMetrics.overallAccuracy(
                whiteEvals.map { it.accuracyScore }
            )
            val blackAccuracy = GameMetrics.overallAccuracy(
                blackEvals.map { it.accuracyScore }
            )

            Log.i("ChessAccuracy",
                "=== GAME $gameId === " +
                "white: ${"%.1f".format(whiteAccuracy)}% (${whiteEvals.size} moves) | " +
                "black: ${"%.1f".format(blackAccuracy)}% (${blackEvals.size} moves) | " +
                "total ${System.currentTimeMillis() - gameStartMs}ms mode=$mode")

            gameRepository.updateAnalysisResult(gameId, whiteAccuracy, blackAccuracy)

            // Update opening if detected
            if (openingName != null) {
                gameRepository.updateOpening(gameId, openingName)
            }

            emit(AnalysisProgress(totalMoves, totalMoves, 1f))
        } catch (e: Exception) {
            if (mode != "cloud") stockfishEngine.stop()
            gameRepository.updateAnalysisStatus(gameId, AnalysisStatus.PENDING)
            throw e
        }
        } // end ENGINE_MUTEX.withLock
    } // end flow

    /** Convert UCI move (e.g. "e2e4") to SAN on the given board. Best effort. */
    private fun uciToSan(board: ChessBoard, uci: String): String? {
        if (uci.length < 4) return null
        val fromSquare = uci.substring(0, 2)
        val toSquare = uci.substring(2, 4)
        val promotion = if (uci.length > 4) uci[4] else null

        val piece = board.getPieceAt(fromSquare) ?: return null

        // Castling
        if (piece.lowercaseChar() == 'k') {
            val fromFile = fromSquare[0]
            val toFile = toSquare[0]
            if (fromFile == 'e' && toFile == 'g') return "O-O"
            if (fromFile == 'e' && toFile == 'c') return "O-O-O"
        }

        val targetPiece = board.getPieceAt(toSquare)
        val isCapture = targetPiece != null ||
            (piece.lowercaseChar() == 'p' && fromSquare[0] != toSquare[0])

        val sb = StringBuilder()

        // Piece letter (pawns have none)
        if (piece.lowercaseChar() != 'p') {
            sb.append(piece.uppercaseChar())
        }

        // Disambiguation (simplified: use file for captures with pawns)
        if (piece.lowercaseChar() == 'p' && isCapture) {
            sb.append(fromSquare[0])
        }

        if (isCapture) sb.append('x')
        sb.append(toSquare)

        if (promotion != null) {
            sb.append('=')
            sb.append(promotion.uppercaseChar())
        }

        return sb.toString()
    }

    /** Convert SAN move to UCI on the given board. Best effort. */
    private fun sanToUci(board: ChessBoard, san: String): String? {
        val move = board.parseSanMove(san) ?: return null
        val from = com.chessanalyzer.domain.chess.indicesToSquare(move.fromRank, move.fromFile)
        val to = com.chessanalyzer.domain.chess.indicesToSquare(move.toRank, move.toFile)
        val promo = move.promotion?.lowercaseChar()?.toString() ?: ""
        return "$from$to$promo"
    }
}
