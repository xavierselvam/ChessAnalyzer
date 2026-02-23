package com.chessanalyzer.domain.chess

/**
 * Represents the state of a chess board.
 * Uses a standard 8x8 array with FEN-compatible piece notation:
 *   Uppercase = White (P, N, B, R, Q, K)
 *   Lowercase = Black (p, n, b, r, q, k)
 *   null = empty square
 *
 * Board indexing: board[rank][file] where rank 0 = rank 8 (top), file 0 = a-file
 */
data class ChessBoard(
    val squares: Array<Array<Char?>> = initialBoard(),
    val activeColor: Char = 'w',         // 'w' or 'b'
    val castlingRights: String = "KQkq",
    val enPassantSquare: String? = null,  // e.g., "e3"
    val halfMoveClock: Int = 0,
    val fullMoveNumber: Int = 1
) {
    companion object {
        fun initialBoard(): Array<Array<Char?>> = arrayOf(
            arrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),  // rank 8
            arrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),  // rank 7
            arrayOfNulls(8),                                     // rank 6
            arrayOfNulls(8),                                     // rank 5
            arrayOfNulls(8),                                     // rank 4
            arrayOfNulls(8),                                     // rank 3
            arrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),  // rank 2
            arrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')   // rank 1
        )

        fun fromFen(fen: String): ChessBoard {
            val parts = fen.split(" ")
            val piecePlacement = parts[0]
            val activeColor = parts.getOrElse(1) { "w" }[0]
            val castling = parts.getOrElse(2) { "-" }
            val enPassant = parts.getOrElse(3) { "-" }.takeIf { it != "-" }
            val halfMove = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0
            val fullMove = parts.getOrElse(5) { "1" }.toIntOrNull() ?: 1

            val squares = Array(8) { arrayOfNulls<Char>(8) }
            val ranks = piecePlacement.split("/")

            for ((rankIdx, rank) in ranks.withIndex()) {
                var fileIdx = 0
                for (c in rank) {
                    if (c.isDigit()) {
                        fileIdx += c.digitToInt()
                    } else {
                        squares[rankIdx][fileIdx] = c
                        fileIdx++
                    }
                }
            }

            return ChessBoard(
                squares = squares,
                activeColor = activeColor,
                castlingRights = if (castling == "-") "" else castling,
                enPassantSquare = enPassant,
                halfMoveClock = halfMove,
                fullMoveNumber = fullMove
            )
        }

        val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }

    fun toFen(): String {
        val sb = StringBuilder()

        // Piece placement
        for (rank in 0 until 8) {
            var emptyCount = 0
            for (file in 0 until 8) {
                val piece = squares[rank][file]
                if (piece == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    sb.append(piece)
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (rank < 7) sb.append('/')
        }

        sb.append(" $activeColor")
        sb.append(" ${castlingRights.ifEmpty { "-" }}")
        sb.append(" ${enPassantSquare ?: "-"}")
        sb.append(" $halfMoveClock")
        sb.append(" $fullMoveNumber")

        return sb.toString()
    }

    fun getPiece(rank: Int, file: Int): Char? {
        if (rank !in 0..7 || file !in 0..7) return null
        return squares[rank][file]
    }

    fun getPieceAt(square: String): Char? {
        val (rank, file) = squareToIndices(square)
        return getPiece(rank, file)
    }

    /**
     * Apply a move in SAN notation and return the new board state.
     */
    fun applyMoveSan(san: String): ChessBoard {
        val move = parseSanMove(san) ?: return this
        return applyMove(move)
    }

    /**
     * Make a move and return a new ChessBoard state.
     */
    fun applyMove(move: Move): ChessBoard {
        val newSquares = squares.map { it.copyOf() }.toTypedArray()
        val piece = newSquares[move.fromRank][move.fromFile]

        // Clear source
        newSquares[move.fromRank][move.fromFile] = null

        // Handle castling
        if (piece?.lowercaseChar() == 'k' && kotlin.math.abs(move.toFile - move.fromFile) == 2) {
            // Kingside castling
            if (move.toFile > move.fromFile) {
                newSquares[move.fromRank][5] = newSquares[move.fromRank][7]
                newSquares[move.fromRank][7] = null
            }
            // Queenside castling
            else {
                newSquares[move.fromRank][3] = newSquares[move.fromRank][0]
                newSquares[move.fromRank][0] = null
            }
        }

        // Handle en passant capture
        if (piece?.lowercaseChar() == 'p' && move.toFile != move.fromFile &&
            squares[move.toRank][move.toFile] == null
        ) {
            newSquares[move.fromRank][move.toFile] = null
        }

        // Place piece (handle promotion)
        newSquares[move.toRank][move.toFile] = move.promotion ?: piece

        // Update en passant square
        val newEnPassant = if (piece?.lowercaseChar() == 'p' &&
            kotlin.math.abs(move.toRank - move.fromRank) == 2
        ) {
            val epRank = (move.fromRank + move.toRank) / 2
            indicesToSquare(epRank, move.fromFile)
        } else null

        // Update castling rights
        var newCastling = castlingRights
        if (piece == 'K') newCastling = newCastling.replace("K", "").replace("Q", "")
        if (piece == 'k') newCastling = newCastling.replace("k", "").replace("q", "")
        if (move.fromRank == 7 && move.fromFile == 0) newCastling = newCastling.replace("Q", "")
        if (move.fromRank == 7 && move.fromFile == 7) newCastling = newCastling.replace("K", "")
        if (move.fromRank == 0 && move.fromFile == 0) newCastling = newCastling.replace("q", "")
        if (move.fromRank == 0 && move.fromFile == 7) newCastling = newCastling.replace("k", "")

        // Halfmove clock
        val newHalfMove = if (piece?.lowercaseChar() == 'p' ||
            squares[move.toRank][move.toFile] != null
        ) 0 else halfMoveClock + 1

        // Fullmove number
        val newFullMove = if (activeColor == 'b') fullMoveNumber + 1 else fullMoveNumber

        return ChessBoard(
            squares = newSquares,
            activeColor = if (activeColor == 'w') 'b' else 'w',
            castlingRights = newCastling,
            enPassantSquare = newEnPassant,
            halfMoveClock = newHalfMove,
            fullMoveNumber = newFullMove
        )
    }

    /**
     * Parse a SAN move string to a Move object.
     */
    fun parseSanMove(san: String): Move? {
        var s = san.replace("+", "").replace("#", "").trim()

        // Castling
        if (s == "O-O" || s == "0-0") {
            val rank = if (activeColor == 'w') 7 else 0
            return Move(rank, 4, rank, 6)
        }
        if (s == "O-O-O" || s == "0-0-0") {
            val rank = if (activeColor == 'w') 7 else 0
            return Move(rank, 4, rank, 2)
        }

        // Promotion
        var promotion: Char? = null
        if ('=' in s) {
            val promChar = s.last()
            promotion = if (activeColor == 'w') promChar.uppercaseChar() else promChar.lowercaseChar()
            s = s.substringBefore('=')
        }

        // Remove capture symbol
        val isCapture = 'x' in s
        s = s.replace("x", "")

        // Determine piece type
        val pieceType: Char
        if (s[0].isUpperCase()) {
            pieceType = if (activeColor == 'w') s[0] else s[0].lowercaseChar()
            s = s.substring(1)
        } else {
            pieceType = if (activeColor == 'w') 'P' else 'p'
        }

        // Target square is always the last 2 chars
        if (s.length < 2) return null
        val targetSquare = s.takeLast(2)
        val (toRank, toFile) = squareToIndices(targetSquare)
        s = s.dropLast(2)

        // Disambiguation
        var disambigFile: Int? = null
        var disambigRank: Int? = null
        for (c in s) {
            if (c in 'a'..'h') disambigFile = c - 'a'
            else if (c in '1'..'8') disambigRank = 8 - (c - '0')
        }

        // Find the source square
        val candidates = findPieceCandidates(pieceType, toRank, toFile, disambigRank, disambigFile)
        val from = candidates.firstOrNull() ?: return null

        return Move(from.first, from.second, toRank, toFile, promotion)
    }

    private fun findPieceCandidates(
        piece: Char,
        toRank: Int,
        toFile: Int,
        disambigRank: Int?,
        disambigFile: Int?
    ): List<Pair<Int, Int>> {
        val candidates = mutableListOf<Pair<Int, Int>>()

        for (rank in 0..7) {
            for (file in 0..7) {
                if (squares[rank][file] != piece) continue
                if (disambigRank != null && rank != disambigRank) continue
                if (disambigFile != null && file != disambigFile) continue

                if (canMoveTo(piece, rank, file, toRank, toFile)) {
                    candidates.add(rank to file)
                }
            }
        }

        return candidates
    }

    private fun canMoveTo(piece: Char, fromRank: Int, fromFile: Int, toRank: Int, toFile: Int): Boolean {
        val dr = toRank - fromRank
        val df = toFile - fromFile

        return when (piece.lowercaseChar()) {
            'p' -> canPawnMove(piece, fromRank, fromFile, toRank, toFile)
            'n' -> (kotlin.math.abs(dr) == 2 && kotlin.math.abs(df) == 1) ||
                    (kotlin.math.abs(dr) == 1 && kotlin.math.abs(df) == 2)
            'b' -> kotlin.math.abs(dr) == kotlin.math.abs(df) && isPathClear(fromRank, fromFile, toRank, toFile)
            'r' -> (dr == 0 || df == 0) && isPathClear(fromRank, fromFile, toRank, toFile)
            'q' -> (dr == 0 || df == 0 || kotlin.math.abs(dr) == kotlin.math.abs(df)) &&
                    isPathClear(fromRank, fromFile, toRank, toFile)
            'k' -> kotlin.math.abs(dr) <= 1 && kotlin.math.abs(df) <= 1
            else -> false
        }
    }

    private fun canPawnMove(piece: Char, fromRank: Int, fromFile: Int, toRank: Int, toFile: Int): Boolean {
        val direction = if (piece.isUpperCase()) -1 else 1  // White moves up (-rank), Black moves down (+rank)
        val startRank = if (piece.isUpperCase()) 6 else 1

        val dr = toRank - fromRank
        val df = toFile - fromFile

        // Forward one
        if (df == 0 && dr == direction && squares[toRank][toFile] == null) return true

        // Forward two from start
        if (df == 0 && dr == 2 * direction && fromRank == startRank &&
            squares[fromRank + direction][fromFile] == null &&
            squares[toRank][toFile] == null
        ) return true

        // Capture (including en passant)
        if (kotlin.math.abs(df) == 1 && dr == direction) {
            if (squares[toRank][toFile] != null) return true
            if (enPassantSquare == indicesToSquare(toRank, toFile)) return true
        }

        return false
    }

    private fun isPathClear(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int): Boolean {
        val dr = Integer.signum(toRank - fromRank)
        val df = Integer.signum(toFile - fromFile)

        var r = fromRank + dr
        var f = fromFile + df
        while (r != toRank || f != toFile) {
            if (squares[r][f] != null) return false
            r += dr
            f += df
        }
        return true
    }

    /**
     * Returns all pseudo-legal destination squares for the piece on [square].
     * "Pseudo-legal" means it respects piece movement rules and own-piece blocking but does
     * NOT filter moves that leave the king in check — sufficient for UI highlighting.
     */
    fun legalTargetsFor(square: String): Set<String> {
        val (fromRank, fromFile) = try { squareToIndices(square) } catch (_: Exception) { return emptySet() }
        val piece = getPiece(fromRank, fromFile) ?: return emptySet()
        val isWhitePiece = piece.isUpperCase()
        if (isWhitePiece != (activeColor == 'w')) return emptySet()

        val targets = mutableSetOf<String>()
        for (rank in 0..7) {
            for (file in 0..7) {
                val occupant = squares[rank][file]
                if (occupant != null && occupant.isUpperCase() == isWhitePiece) continue // own piece
                if (canMoveTo(piece, fromRank, fromFile, rank, file)) {
                    targets.add(indicesToSquare(rank, file))
                }
            }
        }

        // Castling — king large-step handled separately from canMoveTo
        if (piece.lowercaseChar() == 'k') {
            if (activeColor == 'w' && fromRank == 7 && fromFile == 4) {
                if ('K' in castlingRights && squares[7][5] == null && squares[7][6] == null) targets.add("g1")
                if ('Q' in castlingRights && squares[7][3] == null && squares[7][2] == null && squares[7][1] == null) targets.add("c1")
            } else if (activeColor == 'b' && fromRank == 0 && fromFile == 4) {
                if ('k' in castlingRights && squares[0][5] == null && squares[0][6] == null) targets.add("g8")
                if ('q' in castlingRights && squares[0][3] == null && squares[0][2] == null && squares[0][1] == null) targets.add("c8")
            }
        }
        return targets
    }

    /**
     * Apply a move using from/to algebraic squares (e.g. "e2", "e4").
     * Pawns reaching the back rank auto-promote to queen.
     */
    fun applyMoveSquares(fromSquare: String, toSquare: String): ChessBoard {
        val (fromRank, fromFile) = squareToIndices(fromSquare)
        val (toRank, toFile) = squareToIndices(toSquare)
        val piece = getPiece(fromRank, fromFile) ?: return this
        val promotion = when {
            piece == 'P' && toRank == 0 -> 'Q'
            piece == 'p' && toRank == 7 -> 'q'
            else -> null
        }
        return applyMove(Move(fromRank, fromFile, toRank, toFile, promotion))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChessBoard) return false
        return toFen() == other.toFen()
    }

    override fun hashCode(): Int = toFen().hashCode()
}

data class Move(
    val fromRank: Int,
    val fromFile: Int,
    val toRank: Int,
    val toFile: Int,
    val promotion: Char? = null
)

fun squareToIndices(square: String): Pair<Int, Int> {
    val file = square[0] - 'a'
    val rank = 8 - (square[1] - '0')
    return rank to file
}

fun indicesToSquare(rank: Int, file: Int): String {
    return "${('a' + file)}${8 - rank}"
}

private inline fun <reified T> arrayOfNulls(size: Int): Array<T?> = kotlin.arrayOfNulls(size)
