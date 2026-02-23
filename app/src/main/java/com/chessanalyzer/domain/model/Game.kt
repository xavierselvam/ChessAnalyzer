package com.chessanalyzer.domain.model

data class Game(
    val id: String,
    val platform: Platform,
    val pgn: String,
    val white: String,
    val black: String,
    val result: GameResult,
    val timeControl: String,
    val playedAt: Long,
    val opening: String?,
    val analysisStatus: AnalysisStatus,
    val userColor: PieceColor,
    val whiteAccuracy: Float?,
    val blackAccuracy: Float?,
    val totalMoves: Int,
    val gameType: String = "rapid",      // "bullet" | "blitz" | "rapid" | "classical" | "daily"
    val userRating: Int? = null,
    val opponentRating: Int? = null
) {
    val opponent: String
        get() = if (userColor == PieceColor.WHITE) black else white

    val userWon: Boolean?
        get() = when (result) {
            GameResult.WHITE_WINS -> userColor == PieceColor.WHITE
            GameResult.BLACK_WINS -> userColor == PieceColor.BLACK
            GameResult.DRAW -> null
        }

    val userAccuracy: Float?
        get() = if (userColor == PieceColor.WHITE) whiteAccuracy else blackAccuracy
}

enum class Platform(val displayName: String) {
    CHESS_COM("Chess.com"),
    LICHESS("Lichess");

    companion object {
        fun fromString(s: String): Platform = when (s) {
            "chesscom" -> CHESS_COM
            "lichess" -> LICHESS
            else -> CHESS_COM
        }
    }

    fun toDbString(): String = when (this) {
        CHESS_COM -> "chesscom"
        LICHESS -> "lichess"
    }
}

enum class GameResult(val display: String) {
    WHITE_WINS("1-0"),
    BLACK_WINS("0-1"),
    DRAW("½-½");

    companion object {
        fun fromString(s: String): GameResult = when (s) {
            "1-0" -> WHITE_WINS
            "0-1" -> BLACK_WINS
            else -> DRAW
        }
    }
}

enum class AnalysisStatus {
    PENDING, ANALYZING, DONE;

    companion object {
        fun fromString(s: String): AnalysisStatus = when (s) {
            "analyzing" -> ANALYZING
            "done"      -> DONE
            else        -> PENDING
        }
    }

    fun toDbString(): String = name.lowercase()
}

enum class PieceColor {
    WHITE, BLACK;

    companion object {
        fun fromString(s: String): PieceColor =
            if (s.equals("white", ignoreCase = true)) WHITE else BLACK
    }

    fun toDbString(): String = name.lowercase()
}
