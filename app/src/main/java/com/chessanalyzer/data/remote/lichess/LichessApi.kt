package com.chessanalyzer.data.remote.lichess

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface LichessApi {

    companion object {
        const val BASE_URL = "https://lichess.org/"
    }

    @GET("api/user/{username}")
    suspend fun getUser(
        @Path("username") username: String
    ): Response<LichessUserResponse>

    /**
     * Export games of a user. Returns NDJSON.
     * We use pgnInJson=true so each game JSON object includes a "pgn" field.
     * Accept: application/x-ndjson
     */
    @GET("api/games/user/{username}")
    suspend fun getGames(
        @Path("username") username: String,
        @Query("max") max: Int = 300,
        @Query("pgnInJson") pgnInJson: Boolean = true,
        @Query("opening") opening: Boolean = true,
        @Query("since") since: Long? = null,
        @Query("until") until: Long? = null,
        @Header("Accept") accept: String = "application/x-ndjson"
    ): Response<okhttp3.ResponseBody>
}
