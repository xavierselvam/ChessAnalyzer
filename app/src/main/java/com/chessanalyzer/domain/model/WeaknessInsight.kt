package com.chessanalyzer.domain.model

/**
 * Represents a recurring positional weakness: a position (identified by FEN before the move)
 * where the user repeatedly played a blunder/mistake and lost.
 *
 * @param fenBefore  FEN string of the board position BEFORE the user's bad move.
 * @param userMove   SAN of the move the user played (the mistake/blunder).
 * @param bestMove   SAN of what the engine recommends instead.
 * @param occurrences Number of times this exact (position, bad-move) combination appeared.
 */
data class WeaknessInsight(
    val fenBefore: String,
    val userMove: String,
    val bestMove: String,
    val occurrences: Int
)
