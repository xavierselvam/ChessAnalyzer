package com.chessanalyzer.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent cross-session FEN evaluation cache.
 *
 * The [cacheKey] mirrors the in-memory [PositionEvaluator] cache key:
 *   "{fen}|cloud|mpv3"   — Lichess cloud eval, MultiPV=3
 *   "{fen}|cloud|mpv1"   — Lichess cloud eval, MultiPV=1
 *   "{fen}|d14|mpv3"     — Local Stockfish depth 14, MultiPV=3
 *   "{fen}|d14|mpv1"     — Local Stockfish depth 14, MultiPV=1
 *
 * Once a FEN is evaluated (regardless of which game triggered it), subsequent
 * games that reach the same position skip the engine entirely.
 */
@Entity(tableName = "position_eval_cache")
data class PositionEvalCacheEntity(
    @PrimaryKey val cacheKey: String,
    /** Evaluation in centipawns from White's perspective. */
    val cpWhite: Int,
    /** Best move in UCI notation (e.g. "e2e4"), empty string if unknown. */
    val bestMoveUci: String,
    /** Second-best cp from White's perspective; null when cached from a MultiPV=1 call. */
    val secondCpWhite: Int?,
    val isMate: Boolean,
    val mateIn: Int?
)
