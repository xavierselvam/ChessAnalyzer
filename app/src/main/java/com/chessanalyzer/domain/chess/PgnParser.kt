package com.chessanalyzer.domain.chess

/**
 * Parses PGN (Portable Game Notation) strings into structured game data.
 */
object PgnParser {

    data class Position(
        val fen: String,
        val lastMoveSan: String? = null, // the move that led to this position
        val lastMoveFrom: String? = null, // from-square in algebraic notation, e.g. "e2"
        val lastMoveTo: String? = null,   // to-square in algebraic notation, e.g. "e4"
        val moveNumber: Int = 0,
        val isWhiteMove: Boolean = true,
        /** Clock time remaining at this position ("h:mm:ss"), taken from PGN [%clk] comment. */
        val clock: String? = null
    )

    data class PgnGame(
        val headers: Map<String, String>,
        val moves: List<String>,
        val result: String
    )

    /**
     * Parse a PGN string into a list of positions (FEN) for each move.
     * The first position is the starting position.
     */
    fun parseToPositions(pgn: String): List<Position> {
        val game = parsePgn(pgn) ?: return emptyList()
        val positions = mutableListOf<Position>()
        // Per-move clocks from PGN comments: clocks[i] corresponds to game.moves[i]
        val clocks = extractPerMoveClocks(pgn)

        val startFen = game.headers["FEN"] ?: ChessBoard.STARTING_FEN
        var board = ChessBoard.fromFen(startFen)
        positions.add(Position(fen = board.toFen()))

        for ((index, moveSan) in game.moves.withIndex()) {
            val moveNumber = index / 2 + 1
            val isWhite = index % 2 == 0

            val newBoard = board.applyMoveSan(moveSan)
            if (newBoard.toFen() == board.toFen()) {
                // Move failed to apply — stop parsing
                break
            }

            // Extract from/to squares for board highlighting
            val parsedMove = board.parseSanMove(moveSan)
            val fromSq = parsedMove?.let { indicesToSquare(it.fromRank, it.fromFile) }
            val toSq = parsedMove?.let { indicesToSquare(it.toRank, it.toFile) }

            board = newBoard
            positions.add(
                Position(
                    fen = board.toFen(),
                    lastMoveSan = moveSan,
                    lastMoveFrom = fromSq,
                    lastMoveTo = toSq,
                    moveNumber = moveNumber,
                    isWhiteMove = isWhite,
                    clock = clocks.getOrNull(index)
                )
            )
        }

        return positions
    }

    /**
     * Extract per-move clock strings from PGN comments in document order.
     * Index 0 = after white's first move, index 1 = after black's first move, etc.
     * Returns null for moves that have no [%clk] annotation.
     */
    private fun extractPerMoveClocks(pgn: String): List<String?> {
        // Chess.com format: [%clk 0:00:59.7] — optional decimal on seconds
        val clkRegex = """\[%clk\s*(\d+:\d{2}:\d{2})(?:\.\d+)?\]""".toRegex()
        return """\{([^}]*)\}""".toRegex()
            .findAll(pgn)
            .map { clkRegex.find(it.groupValues[1])?.groupValues?.get(1) }
            .toList()
    }

    /**
     * Parse a PGN string into headers and move list.
     */
    fun parsePgn(pgn: String): PgnGame? {
        if (pgn.isBlank()) return null

        val headers = mutableMapOf<String, String>()
        val headerRegex = """\[(\w+)\s+"([^"]*)"?\]""".toRegex()

        val lines = pgn.lines()
        val moveTextBuilder = StringBuilder()
        var inMoveText = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (headers.isNotEmpty()) inMoveText = true
                continue
            }

            val headerMatch = headerRegex.find(trimmed)
            if (headerMatch != null && !inMoveText) {
                headers[headerMatch.groupValues[1]] = headerMatch.groupValues[2]
            } else {
                inMoveText = true
                moveTextBuilder.append(" ").append(trimmed)
            }
        }

        val moveText = moveTextBuilder.toString().trim()
        val moves = extractMoves(moveText)
        val result = headers["Result"] ?: extractResult(moveText) ?: "*"

        return PgnGame(headers = headers, moves = moves, result = result)
    }

    /**
     * Extract individual SAN moves from PGN move text.
     * Strips move numbers, comments, variations, and result.
     */
    private fun extractMoves(moveText: String): List<String> {
        var text = moveText

        // Remove comments { ... }
        text = text.replace("""\{[^}]*\}""".toRegex(), "")

        // Remove variations ( ... )
        var depth = 0
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '(' -> depth++
                ')' -> depth--
                else -> if (depth == 0) sb.append(c)
            }
        }
        text = sb.toString()

        // Remove NAGs ($1, $2, etc.)
        text = text.replace("""\$\d+""".toRegex(), "")

        // Remove move numbers (1. or 1...)
        text = text.replace("""\d+\.{1,3}\s*""".toRegex(), " ")

        // Remove result
        text = text.replace("""(1-0|0-1|1/2-1/2|\*)""".toRegex(), "")

        // Split and filter
        return text.split("""\s+""".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.matches("""[a-hKQRBNO0-9x+#=\-]+""".toRegex()) }
    }

    private fun extractResult(moveText: String): String? {
        return when {
            "1-0" in moveText -> "1-0"
            "0-1" in moveText -> "0-1"
            "1/2-1/2" in moveText -> "1/2-1/2"
            else -> null
        }
    }



    /**
     * Format a move list for display.
     */
    fun formatMoveList(moves: List<String>): String {
        val sb = StringBuilder()
        for (i in moves.indices) {
            if (i % 2 == 0) {
                sb.append("${i / 2 + 1}. ")
            }
            sb.append("${moves[i]} ")
        }
        return sb.toString().trim()
    }
}
