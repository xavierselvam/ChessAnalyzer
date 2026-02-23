package com.chessanalyzer.data.repository

import com.chessanalyzer.data.local.db.GameDao
import com.chessanalyzer.data.local.db.GameEntity
import com.chessanalyzer.data.local.db.GameSummaryEntity
import com.chessanalyzer.data.local.db.MoveEvaluationDao
import com.chessanalyzer.data.local.db.MoveEvaluationEntity
import com.chessanalyzer.data.local.db.RatingPoint
import com.chessanalyzer.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val moveEvaluationDao: MoveEvaluationDao
) {

    fun getAllGames(): Flow<List<Game>> =
        gameDao.getAllGames().map { entities -> entities.map { it.toDomain() } }

    fun getGamesByPlatform(platform: Platform): Flow<List<Game>> =
        gameDao.getGamesByPlatform(platform.toDbString()).map { entities ->
            entities.map { it.toDomain() }
        }

    fun getGamesByType(gameType: String): Flow<List<Game>> =
        gameDao.getGamesByType(gameType).map { entities -> entities.map { it.toDomain() } }

    fun getGamesByPlatformAndType(platform: Platform, gameType: String): Flow<List<Game>> =
        gameDao.getGamesByPlatformAndType(platform.toDbString(), gameType).map { entities ->
            entities.map { it.toDomain() }
        }

    /** Returns the most recent [limit] games instantly (no reactive flow). */
    suspend fun getInitialGames(limit: Int = 50): List<Game> =
        gameDao.getInitialGames(limit).map { it.toDomain() }

    suspend fun getRatingHistory(gameType: String): List<RatingPoint> =
        gameDao.getRatingHistory(gameType)

    suspend fun getGameById(gameId: String): Game? =
        gameDao.getGameById(gameId)?.toDomain()

    fun observeGameById(gameId: String): Flow<Game?> =
        gameDao.observeGameById(gameId).map { it?.toDomain() }

    suspend fun insertGames(games: List<GameEntity>) =
        gameDao.insertGames(games)

    suspend fun updateAnalysisStatus(gameId: String, status: AnalysisStatus) =
        gameDao.updateAnalysisStatus(gameId, status.toDbString())

    suspend fun updateAnalysisResult(gameId: String, whiteAcc: Float, blackAcc: Float) =
        gameDao.updateAnalysisResult(gameId, whiteAcc, blackAcc)

    suspend fun getLatestGameTimestamp(platform: Platform): Long? =
        gameDao.getLatestGameTimestamp(platform.toDbString())

    // Move evaluations

    fun getEvaluationsForGame(gameId: String): Flow<List<MoveEvaluation>> =
        moveEvaluationDao.getEvaluationsForGame(gameId).map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun getEvaluationsForGameSync(gameId: String): List<MoveEvaluation> =
        moveEvaluationDao.getEvaluationsForGameSync(gameId).map { it.toDomain() }

    suspend fun saveEvaluations(evaluations: List<MoveEvaluationEntity>) =
        moveEvaluationDao.insertEvaluations(evaluations)

    suspend fun deleteEvaluationsForGame(gameId: String) =
        moveEvaluationDao.deleteEvaluationsForGame(gameId)

    suspend fun getKeyMoments(gameId: String): List<MoveEvaluation> =
        moveEvaluationDao.getKeyMoments(gameId).map { it.toDomain() }

    suspend fun updateOpening(gameId: String, opening: String) =
        gameDao.updateOpening(gameId, opening)

    suspend fun deleteAllGames() = gameDao.deleteAllGames()

    /** All games not yet analyzed, newest first. */
    suspend fun getPendingGames(): List<Game> =
        gameDao.getPendingGames().map { it.toDomain() }

    /** Next [limit] unanalyzed games, newest first. */
    suspend fun getNextPendingGames(limit: Int): List<Game> =
        gameDao.getNextPendingGames(limit).map { it.toDomain() }

    suspend fun getGameCount(): Int = gameDao.getGameCount()

    suspend fun getAnalyzedGameCount(): Int = gameDao.getAnalyzedGameCount()

    /** Recurring positions where the user made mistakes/blunders in lost games. */
    suspend fun getWeaknessInsights(): List<WeaknessInsight> =
        moveEvaluationDao.getWeaknessInsights().map { row ->
            WeaknessInsight(
                fenBefore = row.fenBefore,
                userMove = row.moveSan,
                bestMove = row.bestMove,
                occurrences = row.occurrences
            )
        }

}

// Extension functions for mapping

/** Maps a summary row (no PGN) to a [Game] with an empty pgn — sufficient for list/card display. */
fun GameSummaryEntity.toDomain(): Game = Game(
    id = id,
    platform = Platform.fromString(platform),
    pgn = "",          // not loaded in list queries to avoid OOM
    white = white,
    black = black,
    result = GameResult.fromString(result),
    timeControl = timeControl,
    playedAt = playedAt,
    opening = opening,
    analysisStatus = AnalysisStatus.fromString(analysisStatus),
    userColor = PieceColor.fromString(userColor),
    whiteAccuracy = whiteAccuracy,
    blackAccuracy = blackAccuracy,
    totalMoves = totalMoves,
    gameType = gameType,
    userRating = userRating,
    opponentRating = opponentRating
)

fun GameEntity.toDomain(): Game = Game(
    id = id,
    platform = Platform.fromString(platform),
    pgn = pgn,
    white = white,
    black = black,
    result = GameResult.fromString(result),
    timeControl = timeControl,
    playedAt = playedAt,
    opening = opening,
    analysisStatus = AnalysisStatus.fromString(analysisStatus),
    userColor = PieceColor.fromString(userColor),
    whiteAccuracy = whiteAccuracy,
    blackAccuracy = blackAccuracy,
    totalMoves = totalMoves,
    gameType = gameType,
    userRating = userRating,
    opponentRating = opponentRating
)

fun MoveEvaluationEntity.toDomain(): MoveEvaluation = MoveEvaluation(
    id = id,
    gameId = gameId,
    moveNumber = moveNumber,
    color = PieceColor.fromString(color),
    moveSan = moveSan,
    fen = fen,
    evalBefore = evalBefore,
    evalAfter = evalAfter,
    bestMove = bestMove,
    bestMoveEval = bestMoveEval,
    secondBestMoveEval = secondBestMoveEval,
    secondBestMove = secondBestMove,
    thirdBestMove = thirdBestMove,
    thirdBestMoveEval = thirdBestMoveEval,
    classification = MoveClassification.fromString(classification),
    isMate = isMate,
    mateIn = mateIn,
    winProbBefore = winProbBefore,
    winProbAfter = winProbAfter,
    accuracyScore = accuracyScore,
    isKeyMoment = isKeyMoment,
    isTurningPoint = isTurningPoint,
    gamePhase = GamePhase.fromString(gamePhase)
)
