package com.chessanalyzer.data.local.db

/** Lightweight projection returned by the rating-history DAO query. */
data class RatingPoint(
    val playedAt: Long,
    val userRating: Int
)
