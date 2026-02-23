package com.chessanalyzer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.chessanalyzer.domain.chess.ChessBoard
import com.chessanalyzer.domain.chess.squareToIndices
import com.chessanalyzer.ui.theme.LightBoardDark
import com.chessanalyzer.ui.theme.LightBoardLight

// Highlight tint for the last-played move (matches Lichess yellow)
private val LastMoveHighlight = Color(0xCCF6F669)  // translucent yellow
// Arrow color for the engine best-move suggestion (green)
private val BestMoveArrowColor = Color(0xCC22C55E) // translucent green
// Arrow color for actual played move (bad moves)
private val ActualMoveArrowColor = Color(0xCCEF4444) // red
// Selected square + legal dot color (blue)
private val SelectionColor = Color(0xAA3B82F4)

@Composable
fun ChessBoardView(
    fen: String,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    /** Algebraic square that was the FROM of the last played move, e.g. "e2" */
    lastMoveFrom: String? = null,
    /** Algebraic square that was the TO of the last played move, e.g. "e4" */
    lastMoveTo: String? = null,
    /** Algebraic square for the engine best-move FROM arrow (shown when different from played) */
    bestMoveFrom: String? = null,
    /** Algebraic square for the engine best-move TO arrow */
    bestMoveTo: String? = null,
    /** Actual played move FROM arrow — shown colored when it differs from best move */
    actualMoveFrom: String? = null,
    /** Actual played move TO arrow */
    actualMoveTo: String? = null,
    /** Color for the actual move arrow — pass classification color (red/orange) */
    actualMoveColor: Color = ActualMoveArrowColor,
    /** Explore mode: currently selected square (highlighted blue) */
    selectedSquare: String? = null,
    /** Explore mode: squares the selected piece can legally move to (dots/rings) */
    legalTargets: Set<String> = emptySet(),
    /** Explore mode: tap callback — returns the algebraic square that was tapped */
    onSquareTap: ((String) -> Unit)? = null
) {
    val board = ChessBoard.fromFen(fen)
    val textMeasurer = rememberTextMeasurer()

    val tapModifier = if (onSquareTap != null) {
        Modifier.pointerInput(flipped, onSquareTap) {
            detectTapGestures { offset ->
                val sqSize = size.width / 8f
                val col = (offset.x / sqSize).toInt().coerceIn(0, 7)
                val row = (offset.y / sqSize).toInt().coerceIn(0, 7)
                val displayFile = if (flipped) 7 - col else col
                val displayRank = if (flipped) 7 - row else row
                val square = "${'a' + displayFile}${8 - displayRank}"
                onSquareTap(square)
            }
        }
    } else Modifier

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(tapModifier)
    ) {
        val squareSize = size.width / 8f

        for (rank in 0 until 8) {
            for (file in 0 until 8) {
                val displayRank = if (flipped) 7 - rank else rank
                val displayFile = if (flipped) 7 - file else file

                val algebraic = "${'a' + displayFile}${8 - displayRank}"
                val isLastMove = algebraic == lastMoveFrom || algebraic == lastMoveTo
                val isSelected = algebraic == selectedSquare

                val isLight = (rank + file) % 2 == 0
                val squareColor = when {
                    isLastMove -> if (isLight) Color(0xFFEDD16A) else Color(0xFFB8AA2D)
                    else -> if (isLight) LightBoardLight else LightBoardDark
                }

                val x = file * squareSize
                val y = rank * squareSize

                // Draw square
                drawRect(color = squareColor, topLeft = Offset(x, y), size = Size(squareSize, squareSize))

                // Selected square highlight (blue overlay)
                if (isSelected) {
                    drawRect(
                        color = SelectionColor,
                        topLeft = Offset(x, y),
                        size = Size(squareSize, squareSize)
                    )
                }

                // Draw piece
                val piece = board.getPiece(displayRank, displayFile)
                if (piece != null) {
                    val pieceSymbol = getPieceUnicode(piece)
                    val textLayout = textMeasurer.measure(
                        text = pieceSymbol,
                        style = TextStyle(fontSize = (squareSize * 0.72f).toSp(), color = Color.Unspecified)
                    )
                    drawText(
                        textLayoutResult = textLayout,
                        color = if (piece.isUpperCase()) Color(0xFFFFFFFF) else Color(0xFF111111),
                        topLeft = Offset(
                            x + (squareSize - textLayout.size.width) / 2f,
                            y + (squareSize - textLayout.size.height) / 2f
                        )
                    )
                }

                // Legal move indicator — dot on empty, ring on occupied
                if (algebraic in legalTargets) {
                    val center = Offset(x + squareSize / 2, y + squareSize / 2)
                    if (piece != null) {
                        drawCircle(color = SelectionColor, radius = squareSize * 0.46f, center = center,
                            style = Stroke(width = squareSize * 0.09f))
                    } else {
                        drawCircle(color = SelectionColor, radius = squareSize * 0.16f, center = center)
                    }
                }
            }
        }

        // Draw rank/file labels on the border squares
        drawBoardLabels(flipped, squareSize, textMeasurer)

        // Draw actual-move arrow UNDER best-move so green is always on top
        if (actualMoveFrom != null && actualMoveTo != null) {
            val fromIdx = squareToVisualIndices(actualMoveFrom, flipped)
            val toIdx = squareToVisualIndices(actualMoveTo, flipped)
            if (fromIdx != null && toIdx != null) {
                drawArrow(
                    fromCenter = Offset((fromIdx.second + 0.5f) * squareSize, (fromIdx.first + 0.5f) * squareSize),
                    toCenter   = Offset((toIdx.second   + 0.5f) * squareSize, (toIdx.first   + 0.5f) * squareSize),
                    color = actualMoveColor,
                    squareSize = squareSize
                )
            }
        }

        // Draw best-move arrow on top of everything
        if (bestMoveFrom != null && bestMoveTo != null) {
            val fromIdx = squareToVisualIndices(bestMoveFrom, flipped)
            val toIdx = squareToVisualIndices(bestMoveTo, flipped)
            if (fromIdx != null && toIdx != null) {
                drawArrow(
                    fromCenter = Offset(
                        (fromIdx.second + 0.5f) * squareSize,
                        (fromIdx.first + 0.5f) * squareSize
                    ),
                    toCenter = Offset(
                        (toIdx.second + 0.5f) * squareSize,
                        (toIdx.first + 0.5f) * squareSize
                    ),
                    color = BestMoveArrowColor,
                    squareSize = squareSize
                )
            }
        }
    }
}

/**
 * Converts an algebraic square ("e4") to visual board indices (rank, file)
 * taking board orientation (flipped) into account.
 */
private fun squareToVisualIndices(square: String, flipped: Boolean): Pair<Int, Int>? {
    if (square.length < 2) return null
    val (logicalRank, logicalFile) = try { squareToIndices(square) } catch (_: Exception) { return null }
    val visRank = if (flipped) 7 - logicalRank else logicalRank
    val visFile = if (flipped) 7 - logicalFile else logicalFile
    return visRank to visFile
}

/**
 * Draws a board-style arrow from [fromCenter] to [toCenter] with an arrowhead.
 * Shaft width and head size scale with [squareSize] so they look good at any resolution.
 */
private fun DrawScope.drawArrow(
    fromCenter: Offset,
    toCenter: Offset,
    color: Color,
    squareSize: Float
) {
    val shaftWidth = squareSize * 0.18f
    val headLength = squareSize * 0.38f
    val headWidth = squareSize * 0.34f

    val dx = toCenter.x - fromCenter.x
    val dy = toCenter.y - fromCenter.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    if (length < 1f) return

    val ux = dx / length
    val uy = dy / length

    // Shorten the arrow so it starts/ends at square edges, not piece centres
    val shorten = squareSize * 0.22f
    val startX = fromCenter.x + ux * shorten
    val startY = fromCenter.y + uy * shorten
    val endX = toCenter.x - ux * shorten
    val endY = toCenter.y - uy * shorten

    // Point where arrowhead base meets shaft
    val headBaseX = endX - ux * headLength
    val headBaseY = endY - uy * headLength

    // Perpendicular unit vector
    val px = -uy
    val py = ux

    // Shaft rectangle as a path
    val path = Path().apply {
        moveTo(startX + px * shaftWidth / 2, startY + py * shaftWidth / 2)
        lineTo(headBaseX + px * shaftWidth / 2, headBaseY + py * shaftWidth / 2)
        lineTo(headBaseX + px * headWidth / 2, headBaseY + py * headWidth / 2)
        lineTo(endX, endY)
        lineTo(headBaseX - px * headWidth / 2, headBaseY - py * headWidth / 2)
        lineTo(headBaseX - px * shaftWidth / 2, headBaseY - py * shaftWidth / 2)
        lineTo(startX - px * shaftWidth / 2, startY - py * shaftWidth / 2)
        close()
    }

    drawPath(path = path, color = color)
}

/** Draw thin rank/file coordinate labels on the board edges. */
private fun DrawScope.drawBoardLabels(
    flipped: Boolean,
    squareSize: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val labelStyle = TextStyle(fontSize = (squareSize * 0.18f).toSp(), color = Color(0x99FFFFFF))

    for (i in 0 until 8) {
        // File labels along the bottom edge
        val fileChar = if (flipped) ('h' - i) else ('a' + i)
        val fileLabel = textMeasurer.measure(fileChar.toString(), labelStyle)
        drawText(
            textLayoutResult = fileLabel,
            topLeft = Offset(
                x = i * squareSize + squareSize - fileLabel.size.width - 2f,
                y = 8 * squareSize - fileLabel.size.height - 2f
            )
        )

        // Rank labels along the left edge
        val rankNum = if (flipped) (i + 1) else (8 - i)
        val rankLabel = textMeasurer.measure(rankNum.toString(), labelStyle)
        drawText(
            textLayoutResult = rankLabel,
            topLeft = Offset(x = 2f, y = i * squareSize + 2f)
        )
    }
}

private fun getPieceUnicode(piece: Char): String {
    return when (piece) {
        'K' -> "\u2654"
        'Q' -> "\u2655"
        'R' -> "\u2656"
        'B' -> "\u2657"
        'N' -> "\u2658"
        'P' -> "\u2659"
        'k' -> "\u265A"
        'q' -> "\u265B"
        'r' -> "\u265C"
        'b' -> "\u265D"
        'n' -> "\u265E"
        'p' -> "\u265F"
        else -> ""
    }
}

private fun Float.toSp(): androidx.compose.ui.unit.TextUnit = (this / 3f).sp

