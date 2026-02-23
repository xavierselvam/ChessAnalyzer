package com.chessanalyzer.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records a practice puzzle that the user has successfully solved.
 * Key is the move_evaluations row id (as a string) so it survives re-analysis.
 */
@Entity(tableName = "practice_solved")
data class PracticeSolvedEntity(
    /** "moveEvalId_${MoveEvaluationEntity.id}" */
    @PrimaryKey val positionKey: String,
    val solvedAt: Long = System.currentTimeMillis()
)
