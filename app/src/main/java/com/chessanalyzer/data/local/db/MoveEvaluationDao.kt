package com.chessanalyzer.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MoveEvaluationDao {

    // white=0, black=1 so the ordering mirrors the actual half-move sequence:
    // (1,white), (1,black), (2,white), (2,black), ...
    @Query("SELECT * FROM move_evaluations WHERE gameId = :gameId " +
           "ORDER BY moveNumber ASC, CASE WHEN color = 'white' THEN 0 ELSE 1 END ASC")
    fun getEvaluationsForGame(gameId: String): Flow<List<MoveEvaluationEntity>>

    @Query("SELECT * FROM move_evaluations WHERE gameId = :gameId " +
           "ORDER BY moveNumber ASC, CASE WHEN color = 'white' THEN 0 ELSE 1 END ASC")
    suspend fun getEvaluationsForGameSync(gameId: String): List<MoveEvaluationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvaluations(evaluations: List<MoveEvaluationEntity>)

    @Query("DELETE FROM move_evaluations WHERE gameId = :gameId")
    suspend fun deleteEvaluationsForGame(gameId: String)

    @Query(
        "SELECT classification, COUNT(*) as count FROM move_evaluations " +
        "WHERE gameId = :gameId AND color = :color GROUP BY classification"
    )
    suspend fun getClassificationCounts(gameId: String, color: String): List<ClassificationCount>

    @Query(
        "SELECT * FROM move_evaluations WHERE gameId = :gameId " +
        "ORDER BY moveNumber"
    )
    suspend fun getKeyMoments(gameId: String): List<MoveEvaluationEntity>

    /**
     * Returns positions where the user repeatedly played a blunder or mistake
     * in games they ultimately lost, grouped by (fen, moveSan, bestMove).
     * Uses the post-move FEN (already populated for all analyzed games).
     * Minimum 2 occurrences; sorted by frequency descending.
     */
    @Query(
        "SELECT me.fen AS fenBefore, me.moveSan, me.bestMove, " +
        "COUNT(*) AS occurrences, COUNT(*) AS lossCount " +
        "FROM move_evaluations me " +
        "JOIN games g ON g.id = me.gameId " +
        "WHERE me.classification IN ('blunder', 'mistake') " +
        "  AND ( (g.result = '0-1' AND g.userColor = 'white') " +
        "     OR (g.result = '1-0' AND g.userColor = 'black') ) " +
        "GROUP BY me.fen, me.moveSan, me.bestMove " +
        "HAVING COUNT(*) >= 2 " +
        "ORDER BY COUNT(*) DESC " +
        "LIMIT 8"
    )
    suspend fun getWeaknessInsights(): List<WeaknessRow>

}

data class ClassificationCount(
    val classification: String,
    val count: Int
)

/** One row returned by the weakness-radar query. */
data class WeaknessRow(
    val fenBefore: String,
    val moveSan: String,
    val bestMove: String,
    val occurrences: Int,
    val lossCount: Int
)


