package com.chessanalyzer.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    indices = [
        Index(value = ["playedAt"]),
        Index(value = ["gameType"]),
        Index(value = ["platform"]),
        Index(value = ["gameType", "playedAt"]),
        Index(value = ["platform", "playedAt"]),
        Index(value = ["platform", "gameType", "playedAt"])
    ]
)
data class GameEntity(
    @PrimaryKey
    val id: String,                    // platform_gameId
    val platform: String,              // "chesscom" | "lichess"
    val pgn: String,
    val white: String,
    val black: String,
    val result: String,                // "1-0", "0-1", "1/2-1/2"
    val timeControl: String,
    val playedAt: Long,                // epoch millis
    val opening: String? = null,
    val analysisStatus: String = "pending",  // "pending" | "analyzing" | "done"
    val userColor: String,             // "white" | "black"
    val whiteAccuracy: Float? = null,
    val blackAccuracy: Float? = null,
    val totalMoves: Int = 0,
    val gameType: String = "rapid",      // "bullet" | "blitz" | "rapid" | "classical" | "daily"
    val userRating: Int? = null,
    val opponentRating: Int? = null
)
