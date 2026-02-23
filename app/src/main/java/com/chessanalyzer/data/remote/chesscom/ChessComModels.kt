package com.chessanalyzer.data.remote.chesscom

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChessComPlayerResponse(
    @Json(name = "username") val username: String,
    @Json(name = "player_id") val playerId: Long,
    @Json(name = "url") val url: String
)

@JsonClass(generateAdapter = true)
data class ChessComArchivesResponse(
    @Json(name = "archives") val archives: List<String>
)

@JsonClass(generateAdapter = true)
data class ChessComGamesResponse(
    @Json(name = "games") val games: List<ChessComGame>
)

@JsonClass(generateAdapter = true)
data class ChessComGame(
    @Json(name = "url") val url: String,
    @Json(name = "pgn") val pgn: String? = null,
    @Json(name = "end_time") val endTime: Long,
    @Json(name = "time_control") val timeControl: String,
    @Json(name = "rated") val rated: Boolean = true,
    @Json(name = "rules") val rules: String = "chess",
    @Json(name = "white") val white: ChessComPlayer,
    @Json(name = "black") val black: ChessComPlayer
)

@JsonClass(generateAdapter = true)
data class ChessComPlayer(
    @Json(name = "username") val username: String,
    @Json(name = "rating") val rating: Int,
    @Json(name = "result") val result: String   // "win", "checkmated", "timeout", etc.
)
