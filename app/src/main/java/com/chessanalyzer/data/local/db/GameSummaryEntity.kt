package com.chessanalyzer.data.local.db

/**
 * Lightweight projection of [GameEntity] that excludes the [pgn] column.
 *
 * All list/card queries use this so that large PGN strings are never loaded
 * into memory for the full game list. The full [GameEntity] (with PGN) is
 * only fetched when a specific game is needed for analysis or move display.
 */
data class GameSummaryEntity(
    val id: String,
    val platform: String,
    val white: String,
    val black: String,
    val result: String,
    val timeControl: String,
    val playedAt: Long,
    val opening: String?,
    val analysisStatus: String,
    val userColor: String,
    val whiteAccuracy: Float?,
    val blackAccuracy: Float?,
    val totalMoves: Int,
    val gameType: String,
    val userRating: Int?,
    val opponentRating: Int?
)
