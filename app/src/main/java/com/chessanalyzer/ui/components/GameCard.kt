package com.chessanalyzer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chessanalyzer.domain.model.*
import com.chessanalyzer.ui.theme.*

@Composable
fun GameCard(
    game: Game,
    onClick: () -> Unit,
    onAnalyze: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    analysisProgress: Float = 0f,
    isQueued: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Result indicator + game type badge
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            when (game.userWon) {
                                true -> ChessLightGreen
                                false -> BlunderColor
                                null -> Color.Gray
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (game.userWon) {
                            true -> "W"
                            false -> "L"
                            null -> "D"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = when (game.gameType.lowercase()) {
                        "bullet" -> "B"
                        "blitz" -> "Bz"
                        "rapid" -> "R"
                        "classical" -> "C"
                        else -> "O"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Game info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = game.opponent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = game.platform.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (game.totalMoves > 0) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${game.totalMoves} moves",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                game.opening?.let { opening ->
                    val cleanOpening = opening
                        .substringAfterLast("/")  // strip any URL prefix
                        .replace("-", " ")         // hyphens → spaces
                        .trim()
                    Text(
                        text = cleanOpening.ifBlank { opening },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Analysis badge / accuracy
            Column(
                horizontalAlignment = Alignment.End
            ) {
                when (game.analysisStatus) {
                    AnalysisStatus.DONE -> {
                        game.userAccuracy?.let { acc ->
                            Text(
                                text = "${acc.toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = getAccuracyColor(acc)
                            )
                            Text(
                                text = "accuracy",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isQueued) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = "Queued",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Queued",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (onCancel != null) {
                                    IconButton(
                                        onClick = onCancel,
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else if (onAnalyze != null) {
                            TextButton(
                                onClick = onAnalyze,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text(
                                    text = "Re-analyze",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    AnalysisStatus.ANALYZING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        val pct = (analysisProgress * 100).toInt()
                        Text(
                            text = if (pct > 0) "$pct%" else "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onCancel != null) {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cancel analysis",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    AnalysisStatus.CLOUD_DONE -> {
                        // Cloud phase complete; waiting for Stockfish deep analysis
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Deep…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnalysisStatus.PENDING -> {
                        if (isQueued) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Queued",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Queued",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (onCancel != null) {
                                IconButton(
                                    onClick = onCancel,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancel",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            AssistChip(
                                onClick = { onAnalyze?.invoke() },
                                label = {
                                    Text(
                                        "Analyze",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        // Thin progress bar at the bottom of the card while analyzing
        if (game.analysisStatus == AnalysisStatus.ANALYZING) {
            LinearProgressIndicator(
                progress = { analysisProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
        } // end Column
    }
}

@Composable
private fun getAccuracyColor(accuracy: Float): Color {
    return when {
        accuracy >= 90 -> BrilliantColor
        accuracy >= 75 -> GreatColor
        accuracy >= 60 -> GoodColor
        accuracy >= 40 -> InaccuracyColor
        accuracy >= 20 -> MistakeColor
        else -> BlunderColor
    }
}
