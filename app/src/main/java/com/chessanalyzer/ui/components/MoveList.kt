package com.chessanalyzer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chessanalyzer.domain.model.MoveClassification
import com.chessanalyzer.domain.model.MoveEvaluation
import com.chessanalyzer.ui.theme.BestColor
import com.chessanalyzer.ui.theme.BlunderColor
import com.chessanalyzer.ui.theme.BookColor
import com.chessanalyzer.ui.theme.BrilliantColor
import com.chessanalyzer.ui.theme.ExcellentColor
import com.chessanalyzer.ui.theme.GoodColor
import com.chessanalyzer.ui.theme.GreatColor
import com.chessanalyzer.ui.theme.InaccuracyColor
import com.chessanalyzer.ui.theme.MistakeColor
import com.chessanalyzer.ui.theme.OnlyMoveColor

/**
 * Vertically scrolling move list in standard chess notation style:
 *
 *   1.  e4 ★   e5
 *   2.  Nf3     Nc6 ??
 *   3.  Bb5 !   a6
 *   …
 *
 * - Pairs white and black moves on the same row, with move-number on the left.
 * - Tints each move chip with a subtle background derived from its [MoveClassification] color.
 * - Appends the classification symbol after the SAN text (e.g. "!!", "?", "★").
 * - Highlights the currently selected move with the primary theme colour.
 * - Auto-scrolls to keep the current move in view.
 *
 * [currentMoveIndex]: 1-based position index (0 = start, 1 = after first move, …)
 */
@Composable
fun MoveList(
    moves: List<String>,
    evaluations: List<MoveEvaluation>,
    currentMoveIndex: Int,
    onMoveClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val pairCount = (moves.size + 1) / 2

    // Auto-scroll so the current move row is visible
    LaunchedEffect(currentMoveIndex) {
        if (currentMoveIndex > 0) {
            val rowIndex = (currentMoveIndex - 1) / 2
            listState.animateScrollToItem(rowIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(pairCount) { pairIdx ->
            val whiteIdx = pairIdx * 2          // 0-based move list index
            val blackIdx = whiteIdx + 1

            // position indices (1-based)
            val whitePosIdx = whiteIdx + 1
            val blackPosIdx = blackIdx + 1

            val whiteEval = evaluations.getOrNull(whiteIdx)
            val blackEval = evaluations.getOrNull(blackIdx)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Move number
                Text(
                    text = "${pairIdx + 1}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp)
                )

                // White move chip
                MoveChip(
                    san = moves.getOrNull(whiteIdx) ?: "",
                    eval = whiteEval,
                    isSelected = currentMoveIndex == whitePosIdx,
                    onClick = { onMoveClick(whitePosIdx) },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Black move chip (may not exist on last move of an odd-count game)
                if (blackIdx < moves.size) {
                    MoveChip(
                        san = moves.getOrNull(blackIdx) ?: "",
                        eval = blackEval,
                        isSelected = currentMoveIndex == blackPosIdx,
                        onClick = { onMoveClick(blackPosIdx) },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MoveChip(
    san: String,
    eval: MoveEvaluation?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val classColor = eval?.let { getClassificationColor(it.classification) } ?: Color.Transparent
    val symbol = eval?.classification?.symbol?.takeIf { it.isNotBlank() } ?: ""

    val bgColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        eval != null -> classColor.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val symbolColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        eval != null -> classColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = san,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
        if (symbol.isNotBlank()) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = symbol,
                color = symbolColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun getClassificationColor(classification: MoveClassification): Color {
    return when (classification) {
        MoveClassification.BRILLIANT -> BrilliantColor
        MoveClassification.GREAT -> GreatColor
        MoveClassification.BEST -> BestColor
        MoveClassification.EXCELLENT -> ExcellentColor
        MoveClassification.ONLY_MOVE -> OnlyMoveColor
        MoveClassification.GOOD -> GoodColor
        MoveClassification.BOOK -> BookColor
        MoveClassification.INACCURACY -> InaccuracyColor
        MoveClassification.MISTAKE -> MistakeColor
        MoveClassification.BLUNDER -> BlunderColor
    }
}

