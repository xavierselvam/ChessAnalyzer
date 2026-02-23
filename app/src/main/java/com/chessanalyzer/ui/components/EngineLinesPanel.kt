package com.chessanalyzer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Represents a single engine PV line for display.
 *
 * @param rank        1-based rank (1 = best move)
 * @param moveSan     SAN of the first move of this line (e.g. "Nf3")
 * @param evalCp      Centipawns evaluation from White's perspective
 * @param isMate      True if this line leads to forced mate
 * @param mateIn      Moves to mate (positive = White wins, negative = Black wins)
 * @param continuation Remaining PV moves as space-separated SAN/UCI string (greyed out)
 */
data class EngineLine(
    val rank: Int,
    val moveSan: String,
    val evalCp: Int = 0,
    val isMate: Boolean = false,
    val mateIn: Int? = null,
    val continuation: String = ""
)

/**
 * Panel showing up to 3 engine PV lines.
 * Used in both explore mode (live engine output) and review mode (stored evaluations).
 *
 * Each row shows: rank chip | move | eval badge | faded continuation moves
 */
@Composable
fun EngineLinesPanel(
    lines: List<EngineLine>,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty() && !isLoading) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Analysing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                lines.forEach { line ->
                    EngineLineRow(line = line, isTopLine = line.rank == 1)
                    if (line.rank != lines.last().rank) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineLineRow(line: EngineLine, isTopLine: Boolean) {
    val evalText = when {
        line.isMate && line.mateIn != null ->
            if (line.mateIn > 0) "M${line.mateIn}" else "M${-line.mateIn}"
        else -> {
            val p = line.evalCp / 100f
            if (p >= 0) "+%.1f".format(p) else "%.1f".format(p)
        }
    }

    val evalColor = when {
        line.isMate && line.mateIn != null && line.mateIn > 0 -> Color(0xFF4CAF50)
        line.isMate && line.mateIn != null && line.mateIn < 0 -> Color(0xFFF44336)
        line.evalCp > 50  -> Color(0xFF4CAF50)
        line.evalCp < -50 -> Color(0xFFF44336)
        else              -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank chip
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = if (isTopLine)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            else
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "${line.rank}",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTopLine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Move SAN
        Text(
            text = line.moveSan.ifBlank { "—" },
            fontWeight = if (isTopLine) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 36.dp)
        )

        Spacer(Modifier.width(6.dp))

        // Faded continuation
        if (line.continuation.isNotBlank()) {
            Text(
                text = line.continuation,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.width(6.dp))

        // Eval badge
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = evalColor.copy(alpha = 0.12f)
        ) {
            Text(
                text = evalText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = evalColor,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}
