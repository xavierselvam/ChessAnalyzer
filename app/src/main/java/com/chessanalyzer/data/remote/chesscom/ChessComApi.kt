package com.chessanalyzer.data.remote.chesscom

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface ChessComApi {

    companion object {
        const val BASE_URL = "https://api.chess.com/"
    }

    @GET("pub/player/{username}")
    suspend fun getPlayer(
        @Path("username") username: String
    ): Response<ChessComPlayerResponse>

    @GET("pub/player/{username}/games/archives")
    suspend fun getArchives(
        @Path("username") username: String
    ): Response<ChessComArchivesResponse>

    @GET("pub/player/{username}/games/{year}/{month}")
    suspend fun getGames(
        @Path("username") username: String,
        @Path("year") year: String,
        @Path("month") month: String
    ): Response<ChessComGamesResponse>
}
