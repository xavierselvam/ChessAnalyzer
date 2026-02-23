package com.chessanalyzer.domain.model

data class MoveEvaluation(
    val id: Long = 0,
    val gameId: String,
    val moveNumber: Int,
    val color: PieceColor,
    val moveSan: String,
    val fen: String,
    val evalBefore: Int,           // centipawns (White's perspective)
    val evalAfter: Int,            // centipawns (White's perspective)
    val bestMove: String,          // engine best move SAN
    val bestMoveEval: Int,         // eval after best move (White's perspective)
    val secondBestMoveEval: Int?,  // eval of second-best move (for brilliant/great detection)
    val secondBestMove: String = "",    // SAN of engine's 2nd-best move
    val thirdBestMove: String = "",     // SAN of engine's 3rd-best move
    val thirdBestMoveEval: Int? = null, // centipawn eval of 3rd-best move
    val classification: MoveClassification,
    val isMate: Boolean = false,
    val mateIn: Int? = null,
    val winProbBefore: Float = 0.5f,   // win probability before move
    val winProbAfter: Float = 0.5f,    // win probability after move
    val accuracyScore: Float = 100f,   // per-move accuracy (0-100%)
    val isKeyMoment: Boolean = false,
    val isTurningPoint: Boolean = false,
    val gamePhase: GamePhase = GamePhase.MIDDLEGAME
) {
    /**
     * Eval loss = max(0, best_eval_for_player - played_eval_for_player)
     */
    val evalLoss: Int
        get() {
            val bestForPlayer = if (color == PieceColor.WHITE) bestMoveEval else -bestMoveEval
            val playedForPlayer = if (color == PieceColor.WHITE) evalAfter else -evalAfter
            return maxOf(0, bestForPlayer - playedForPlayer)
        }

    val centipawnLoss: Int
        get() = evalLoss
}

enum class MoveClassification(val displayName: String, val symbol: String) {
    BRILLIANT("Brilliant", "!!"),
    GREAT("Great", "!"),
    BEST("Best", "★"),
    EXCELLENT("Excellent", "✓"),
    GOOD("Good", ""),
    BOOK("Book", ""),
    ONLY_MOVE("Only Move", "□"),
    INACCURACY("Inaccuracy", "?!"),
    MISTAKE("Mistake", "?"),
    BLUNDER("Blunder", "??");

    companion object {
        fun fromString(s: String): MoveClassification = when (s.lowercase()) {
            "brilliant" -> BRILLIANT
            "great" -> GREAT
            "best" -> BEST
            "excellent" -> EXCELLENT
            "good" -> GOOD
            "book" -> BOOK
            "only_move" -> ONLY_MOVE
            "inaccuracy" -> INACCURACY
            "mistake" -> MISTAKE
            "blunder" -> BLUNDER
            else -> GOOD
        }
    }

    fun toDbString(): String = name.lowercase()
}

enum class GamePhase(val displayName: String) {
    OPENING("Opening"),
    MIDDLEGAME("Middlegame"),
    ENDGAME("Endgame");

    companion object {
        fun fromString(s: String): GamePhase = when (s.lowercase()) {
            "opening" -> OPENING
            "middlegame" -> MIDDLEGAME
            "endgame" -> ENDGAME
            else -> MIDDLEGAME
        }
    }

    fun toDbString(): String = name.lowercase()
}
