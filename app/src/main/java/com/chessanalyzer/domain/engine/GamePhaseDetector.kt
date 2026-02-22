package com.chessanalyzer.domain.engine

import com.chessanalyzer.domain.chess.ChessBoard
import com.chessanalyzer.domain.model.GamePhase

/**
 * Game phase detection based on total material on the board.
 *
 * Piece values: pawn=1, knight=3, bishop=3, rook=5, queen=9
 *
 * Phases:
 *   OPENING:    total_material ≥ 60
 *   MIDDLEGAME: 30 ≤ total_material < 60
 *   ENDGAME:    total_material < 30
 */
object GamePhaseDetector {

    private fun pieceValue(piece: Char): Int = when (piece.lowercaseChar()) {
        'p' -> 1
        'n' -> 3
        'b' -> 3
        'r' -> 5
        'q' -> 9
        else -> 0  // king has no material value for phase detection
    }

    /** Compute total material value (excluding kings) from a ChessBoard. */
    fun totalMaterial(board: ChessBoard): Int {
        var total = 0
        for (rank in 0..7) {
            for (file in 0..7) {
                board.getPiece(rank, file)?.let { piece ->
                    total += pieceValue(piece)
                }
            }
        }
        return total
    }

    /** Compute total material from a FEN string. */
    fun totalMaterialFromFen(fen: String): Int {
        val piecePlacement = fen.split(" ").firstOrNull() ?: return 0
        var total = 0
        for (c in piecePlacement) {
            if (c.isLetter()) {
                total += pieceValue(c)
            }
        }
        return total
    }

    /** Detect game phase from total material. */
    fun detectPhase(totalMaterial: Int): GamePhase = when {
        totalMaterial >= 60 -> GamePhase.OPENING
        totalMaterial >= 30 -> GamePhase.MIDDLEGAME
        else -> GamePhase.ENDGAME
    }

    /** Detect game phase directly from FEN. */
    fun detectPhaseFromFen(fen: String): GamePhase {
        return detectPhase(totalMaterialFromFen(fen))
    }

    /**
     * Compute player's material (for sacrifice detection in brilliant move classification).
     * @param isWhite true for white pieces
     */
    fun playerMaterial(board: ChessBoard, isWhite: Boolean): Int {
        var total = 0
        for (rank in 0..7) {
            for (file in 0..7) {
                board.getPiece(rank, file)?.let { piece ->
                    val isPlayerPiece = if (isWhite) piece.isUpperCase() else piece.isLowerCase()
                    if (isPlayerPiece) {
                        total += pieceValue(piece)
                    }
                }
            }
        }
        return total
    }

    /** Compute player's material from FEN. */
    fun playerMaterialFromFen(fen: String, isWhite: Boolean): Int {
        val piecePlacement = fen.split(" ").firstOrNull() ?: return 0
        var total = 0
        for (c in piecePlacement) {
            if (!c.isLetter()) continue
            val isPlayerPiece = if (isWhite) c.isUpperCase() else c.isLowerCase()
            if (isPlayerPiece) {
                total += pieceValue(c)
            }
        }
        return total
    }
}
