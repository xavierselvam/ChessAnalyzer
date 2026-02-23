package com.chessanalyzer.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "move_evaluations",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("gameId"), Index("moveStatus")]
)
data class MoveEvaluationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val gameId: String,
    val moveNumber: Int,
    val color: String,                 // "white" | "black"
    val moveSan: String,               // e.g. "Nf3"
    val fen: String,                   // position after move
    val evalBefore: Int,               // centipawns before move (White's perspective)
    val evalAfter: Int,                // centipawns after actual move (White's perspective)
    val bestMove: String,              // engine best move SAN
    val bestMoveEval: Int = 0,         // eval of best move (White's perspective)
    val secondBestMoveEval: Int? = null, // eval of 2nd best move
    val classification: String,        // "brilliant"|"great"|"best"|"excellent"|"good"|"book"|"only_move"|"inaccuracy"|"mistake"|"blunder"
    val isMate: Boolean = false,
    val mateIn: Int? = null,
    val winProbBefore: Float = 0.5f,   // win probability before move
    val winProbAfter: Float = 0.5f,    // win probability after move
    val accuracyScore: Float = 100f,   // per-move accuracy (0-100%)
    val isKeyMoment: Boolean = false,
    val isTurningPoint: Boolean = false,
    val gamePhase: String = "middlegame",  // "opening"|"middlegame"|"endgame"
    val fenBefore: String = "",            // FEN of position before this move
    // ── Two-phase hybrid tracking ─────────────────────────────────────────────
    /** "cloud_hit" | "needs_stockfish" | "stockfish_done" */
    val moveStatus: String = "stockfish_done",
    /** Source of evalBefore: "cloud" | "stockfish" | "pending" */
    val evalBeforeSource: String = "stockfish",
    /** Source of evalAfter: "cloud" | "stockfish" | "pending" */
    val evalAfterSource: String = "stockfish",
    // ── Multi-PV alternative moves ────────────────────────────────────────────
    /** SAN of the engine's 2nd-best move (from MultiPV=3 before-move analysis). */
    val secondBestMove: String = "",
    /** SAN of the engine's 3rd-best move. */
    val thirdBestMove: String = "",
    /** Centipawn eval of the 3rd-best move (White's perspective). Null if <3 moves available. */
    val thirdBestMoveEval: Int? = null
)
