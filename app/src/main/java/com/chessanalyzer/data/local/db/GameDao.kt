package com.chessanalyzer.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    // ── Summary queries (no pgn column) ─────────────────────────────────────
    // These are used for list/card views and avoid loading large PGN strings.

    @Query("""SELECT id, platform, white, black, result, timeControl, playedAt,
              opening, analysisStatus, userColor, whiteAccuracy, blackAccuracy,
              totalMoves, gameType, userRating, opponentRating
              FROM games ORDER BY playedAt DESC""")
    fun getAllGames(): Flow<List<GameSummaryEntity>>

    @Query("""SELECT id, platform, white, black, result, timeControl, playedAt,
              opening, analysisStatus, userColor, whiteAccuracy, blackAccuracy,
              totalMoves, gameType, userRating, opponentRating
              FROM games WHERE platform = :platform ORDER BY playedAt DESC""")
    fun getGamesByPlatform(platform: String): Flow<List<GameSummaryEntity>>

    @Query("""SELECT id, platform, white, black, result, timeControl, playedAt,
              opening, analysisStatus, userColor, whiteAccuracy, blackAccuracy,
              totalMoves, gameType, userRating, opponentRating
              FROM games WHERE gameType = :gameType ORDER BY playedAt DESC""")
    fun getGamesByType(gameType: String): Flow<List<GameSummaryEntity>>

    @Query("""SELECT id, platform, white, black, result, timeControl, playedAt,
              opening, analysisStatus, userColor, whiteAccuracy, blackAccuracy,
              totalMoves, gameType, userRating, opponentRating
              FROM games WHERE platform = :platform AND gameType = :gameType ORDER BY playedAt DESC""")
    fun getGamesByPlatformAndType(platform: String, gameType: String): Flow<List<GameSummaryEntity>>

    /** One-shot: returns the most recent N games without starting a reactive flow. */
    @Query("""SELECT id, platform, white, black, result, timeControl, playedAt,
              opening, analysisStatus, userColor, whiteAccuracy, blackAccuracy,
              totalMoves, gameType, userRating, opponentRating
              FROM games ORDER BY playedAt DESC LIMIT :limit""")
    suspend fun getInitialGames(limit: Int): List<GameSummaryEntity>

    // ── Full-entity queries (includes pgn — only for analysis / detail screen) ─

    @Query("SELECT * FROM games WHERE id = :gameId")
    suspend fun getGameById(gameId: String): GameEntity?

    @Query("SELECT * FROM games WHERE id = :gameId")
    fun observeGameById(gameId: String): Flow<GameEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGames(games: List<GameEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGame(game: GameEntity)

    @Update
    suspend fun updateGame(game: GameEntity)

    @Query("UPDATE games SET analysisStatus = :status WHERE id = :gameId")
    suspend fun updateAnalysisStatus(gameId: String, status: String)

    @Query(
        "UPDATE games SET whiteAccuracy = :whiteAcc, blackAccuracy = :blackAcc, " +
        "analysisStatus = 'done' WHERE id = :gameId"
    )
    suspend fun updateAnalysisResult(gameId: String, whiteAcc: Float, blackAcc: Float)

    @Query("SELECT MAX(playedAt) FROM games WHERE platform = :platform")
    suspend fun getLatestGameTimestamp(platform: String): Long?

    @Query("SELECT COUNT(*) FROM games")
    suspend fun getGameCount(): Int

    @Query("SELECT COUNT(*) FROM games WHERE analysisStatus = 'done'")
    suspend fun getAnalyzedGameCount(): Int

    @Query("UPDATE games SET opening = :opening WHERE id = :gameId")
    suspend fun updateOpening(gameId: String, opening: String)

    /** Returns (playedAt, userRating) pairs ordered by time for a specific game type. */
    @Query("SELECT playedAt, userRating FROM games WHERE gameType = :gameType AND userRating IS NOT NULL ORDER BY playedAt ASC")
    suspend fun getRatingHistory(gameType: String): List<RatingPoint>

    /** Returns all games that haven't been analyzed yet, newest first. */
    @Query("SELECT * FROM games WHERE analysisStatus = 'pending' ORDER BY playedAt DESC")
    suspend fun getPendingGames(): List<GameEntity>

    /** Returns the next [limit] unanalyzed games, newest first. */
    @Query("SELECT * FROM games WHERE analysisStatus = 'pending' ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getNextPendingGames(limit: Int): List<GameEntity>

    /** Returns all pending game IDs only (no PGN). Used by CloudPhaseUseCase. */
    @Query("SELECT id FROM games WHERE analysisStatus = 'pending' ORDER BY playedAt DESC")
    suspend fun getPendingGameIds(): List<String>

    /** Returns up to [limit] cloud_done game IDs (cloud phase done, Stockfish pending). */
    @Query("SELECT id FROM games WHERE analysisStatus = 'cloud_done' ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getCloudDoneGameIds(limit: Int): List<String>

    @Query("DELETE FROM games")
    suspend fun deleteAllGames()
}
