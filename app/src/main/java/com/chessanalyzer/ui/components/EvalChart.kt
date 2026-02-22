package com.chessanalyzer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chessanalyzer.domain.model.MoveClassification
import com.chessanalyzer.domain.model.MoveEvaluation
import kotlin.math.exp

/**
 * Evaluation chart that mirrors the area-chart in pawn-appetit's EvalChart.tsx.
 *
 * - Converts centipawns → sigmoid win-chance: `1 / (1 + exp(-0.004 * cp))`
 *   so the scale is natural (not clamped-linear) and matches Lichess/pawn-appetit visuals.
 * - White-advantage region (> 0.5) fills with a light colour; black-advantage fills dark.
 * - A coloured dot marks each move, coloured by its [MoveClassification].
 * - A vertical reference line tracks the [currentMoveIndex].
 * - Tapping anywhere on the chart calls [onMoveClick] with the nearest move index (1-based).
 */
@Composable
fun EvalChart(
    evaluations: List<MoveEvaluation>,
    currentMoveIndex: Int,       // 1-based; 0 = starting position (no dot shown)
    onMoveClick: (Int) -> Unit,  // 1-based move index
    modifier: Modifier = Modifier
) {
    if (evaluations.isEmpty()) {
        Box(
            modifier = modifier.height(60.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No evaluation data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // Pre-compute sigmoid y-values (0 = black wins, 1 = white wins, 0.5 = even)
    val yValues: List<Float?> = remember(evaluations) {
        evaluations.map { eval ->
            when {
                eval.isMate && eval.mateIn != null ->
                    if (eval.mateIn > 0) 1f else 0f
                else -> (1.0 / (1.0 + exp(-0.004 * eval.evalAfter))).toFloat()
            }
        }
    }

    val dotColors: List<Color> = remember(evaluations) {
        evaluations.map { classificationColor(it.classification) }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(70.dp)
            .pointerInput(evaluations) {
                detectTapGestures { offset ->
                    if (evaluations.isEmpty()) return@detectTapGestures
                    val stepX = size.width.toFloat() / evaluations.size
                    val idx = (offset.x / stepX).toInt().coerceIn(0, evaluations.lastIndex)
                    onMoveClick(idx + 1)  // convert to 1-based
                }
            }
    ) {
        val n = evaluations.size
        if (n == 0) return@Canvas

        val w = size.width
        val h = size.height
        val midY = h / 2f
        val stepX = w / n.toFloat()

        // Helper: x-center of move i (0-based)
        fun xOf(i: Int) = (i + 0.5f) * stepX

        // Helper: y position from sigmoid value (0-1)  → canvas y (0=top)
        fun yOf(v: Float) = h - v * h

        // --- Build filled area paths ---
        // White area (above mid) & Black area (below mid)
        val whitePath = Path()
        val blackPath = Path()

        // Collect contiguous segments for white and black fills
        var firstPoint = true
        for (i in 0 until n) {
            val yVal = yValues[i] ?: continue
            val x = xOf(i)
            val y = yOf(yVal)

            if (firstPoint) {
                whitePath.moveTo(x, midY)
                blackPath.moveTo(x, midY)
                firstPoint = false
            }

            // White fill path: clamp y at midY (only fill above center)
            whitePath.lineTo(x, y.coerceAtMost(midY))
            // Black fill path: clamp y at midY (only fill below center)
            blackPath.lineTo(x, y.coerceAtLeast(midY))
        }
        // Close white path at bottom-right → bottom-left → back to midY
        val lastX = xOf(n - 1)
        whitePath.lineTo(lastX, midY)
        whitePath.close()

        blackPath.lineTo(lastX, midY)
        blackPath.close()

        // Draw fills
        clipRect {
            drawPath(whitePath, color = Color(0xFFEFEFEF))  // light fill for white advantage
            drawPath(blackPath, color = Color(0xFF2A2A2A))  // dark fill for black advantage
        }

        // --- Draw the eval line ---
        var prevX = Float.NaN
        var prevY = Float.NaN
        for (i in 0 until n) {
            val yVal = yValues[i] ?: continue
            val x = xOf(i)
            val y = yOf(yVal)
            if (!prevX.isNaN()) {
                drawLine(
                    color = Color(0xFF444444),
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 2f
                )
            }
            prevX = x
            prevY = y
        }

        // --- Draw classification dots ---
        for (i in 0 until n) {
            val yVal = yValues[i] ?: continue
            val classification = evaluations[i].classification
            // Only draw notable dots (don't clutter with GOOD/BOOK)
            if (classification in setOf(
                    MoveClassification.BRILLIANT,
                    MoveClassification.GREAT,
                    MoveClassification.BEST,
                    MoveClassification.INACCURACY,
                    MoveClassification.MISTAKE,
                    MoveClassification.BLUNDER
                )
            ) {
                drawCircle(
                    color = dotColors[i],
                    radius = 4f,
                    center = Offset(xOf(i), yOf(yVal))
                )
                drawCircle(
                    color = Color.White,
                    radius = 2f,
                    center = Offset(xOf(i), yOf(yVal))
                )
            }
        }

        // --- Current position reference line ---
        if (currentMoveIndex in 1..n) {
            val refX = xOf(currentMoveIndex - 1)
            drawLine(
                color = Color(0xFF4CAF50),  // green reference line
                start = Offset(refX, 0f),
                end = Offset(refX, h),
                strokeWidth = 2f
            )
        }

        // --- Centre line ---
        drawLine(
            color = Color(0x44888888),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 0.8f
        )
    }
}

/** Returns the Compose [Color] for a given [MoveClassification], matching the theme. */
private fun classificationColor(c: MoveClassification): Color = when (c) {
    MoveClassification.BRILLIANT -> Color(0xFF06B6D4)
    MoveClassification.GREAT     -> Color(0xFF3B82F6)
    MoveClassification.BEST      -> Color(0xFF22C55E)
    MoveClassification.EXCELLENT -> Color(0xFF4ADE80)
    MoveClassification.ONLY_MOVE -> Color(0xFF818CF8)
    MoveClassification.GOOD      -> Color(0xFF97AF8B)
    MoveClassification.BOOK      -> Color(0xFFA88B65)
    MoveClassification.INACCURACY-> Color(0xFFFACC15)
    MoveClassification.MISTAKE   -> Color(0xFFFB923C)
    MoveClassification.BLUNDER   -> Color(0xFFEF4444)
}
