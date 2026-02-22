package com.chessanalyzer.data.remote.lichess

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LichessUserResponse(
    @Json(name = "id") val id: String,
    @Json(name = "username") val username: String
)

@JsonClass(generateAdapter = true)
data class LichessGame(
    @Json(name = "id") val id: String,
    @Json(name = "rated") val rated: Boolean = true,
    @Json(name = "variant") val variant: String = "standard",
    @Json(name = "speed") val speed: String = "rapid",
    @Json(name = "status") val status: String = "",
    @Json(name = "players") val players: LichessPlayers,
    @Json(name = "opening") val opening: LichessOpening? = null,
    @Json(name = "moves") val moves: String? = null,
    @Json(name = "pgn") val pgn: String? = null,
    @Json(name = "createdAt") val createdAt: Long = 0,
    @Json(name = "lastMoveAt") val lastMoveAt: Long = 0,
    @Json(name = "clock") val clock: LichessClock? = null,
    @Json(name = "winner") val winner: String? = null  // "white" | "black" | null for draw
)

@JsonClass(generateAdapter = true)
data class LichessPlayers(
    @Json(name = "white") val white: LichessPlayer,
    @Json(name = "black") val black: LichessPlayer
)

@JsonClass(generateAdapter = true)
data class LichessPlayer(
    @Json(name = "user") val user: LichessPlayerUser? = null,
    @Json(name = "rating") val rating: Int? = null
)

@JsonClass(generateAdapter = true)
data class LichessPlayerUser(
    @Json(name = "name") val name: String,
    @Json(name = "id") val id: String
)

@JsonClass(generateAdapter = true)
data class LichessOpening(
    @Json(name = "eco") val eco: String? = null,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class LichessClock(
    @Json(name = "initial") val initial: Int = 0,
    @Json(name = "increment") val increment: Int = 0
)
