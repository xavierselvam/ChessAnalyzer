package com.chessanalyzer.ui.screens.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chessanalyzer.ui.components.ChessBoardView
import com.chessanalyzer.ui.components.ConfettiAnimation
import com.chessanalyzer.ui.components.EvalBar

@Composable
fun PracticeScreen(
    onNavigateToGame: (String) -> Unit = {},
    viewModel: PracticeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier
        .statusBarsPadding()
        .navigationBarsPadding()
        .fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Train from Mistakes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = viewModel::toggleSettings,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (uiState.showSettings) Icons.Default.ExpandLess else Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── Settings panel ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = uiState.showSettings,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Games from last",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(7, 14, 30, 90).forEach { days ->
                                FilterChip(
                                    selected = uiState.settings.daysBack == days,
                                    onClick = { viewModel.setDaysBack(days) },
                                    label = { Text("$days d", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Show positions with",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "inaccuracy" to "Inaccuracy",
                                "mistake"    to "Mistake",
                                "blunder"    to "Blunder"
                            ).forEach { (key, label) ->
                                FilterChip(
                                    selected = key in uiState.settings.classifications,
                                    onClick  = { viewModel.toggleClassification(key) },
                                    label    = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }

            // ── Progress strip ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (uiState.allSolved)
                        "All positions solved!"
                    else
                        "${uiState.solvedThisSession} solved  •  ${uiState.queueSize} remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.allSolved) {
                    TextButton(
                        onClick = viewModel::resetProgress,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // ── Main content ───────────────────────────────────────────────
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.allSolved -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🎉", style = MaterialTheme.typography.displayMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "You've solved all positions!",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "Try extending the date range or adding more mistake types.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                            OutlinedButton(onClick = viewModel::resetProgress) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reset & Practice Again")
                            }
                            TextButton(onClick = viewModel::toggleSettings) {
                                Text("Change settings")
                            }
                        }
                    }
                }

                uiState.puzzle != null -> {
                    val puzzle = uiState.puzzle!!

                    // Opponent / context row
                    val opponent = if (puzzle.isUserWhite) puzzle.row.black else puzzle.row.white
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "vs $opponent  •  move ${puzzle.row.moveNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // Classification badge
                        val badgeColor = when (puzzle.row.classification) {
                            "blunder"    -> Color(0xFFEF4444)
                            "mistake"    -> Color(0xFFF97316)
                            "inaccuracy" -> Color(0xFFEAB308)
                            else         -> MaterialTheme.colorScheme.primary
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = puzzle.row.classification.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        // View game button
                        IconButton(
                            onClick = { onNavigateToGame(puzzle.row.gameId) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "View game",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Board with wrong-move red tint
                    val boardTint by animateColorAsState(
                        targetValue = if (uiState.showWrong) Color(0x33EF4444) else Color.Transparent,
                        animationSpec = tween(300),
                        label = "wrongTint"
                    )

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val evalBarWidth = 10.dp
                        val gap = 2.dp
                        val boardWidth = maxWidth - evalBarWidth - gap
                        Row {
                            // Vertical eval bar
                            EvalBar(
                                centipawns = puzzle.row.evalBefore,
                                isMate = puzzle.row.isMate,
                                vertical = true,
                                modifier = Modifier
                                    .width(evalBarWidth)
                                    .height(boardWidth)
                            )
                            Spacer(modifier = Modifier.width(gap))
                            Box(modifier = Modifier.width(boardWidth)) {
                                ChessBoardView(
                                    fen = puzzle.boardFen,
                                    modifier = Modifier.fillMaxWidth(),
                                    flipped = !puzzle.isUserWhite,
                                    bestMoveFrom = if (uiState.showHint) uiState.hintFromSquare else null,
                                    bestMoveTo   = if (uiState.showHint) uiState.hintToSquare   else null,
                                    selectedSquare = uiState.selectedSquare,
                                    legalTargets   = uiState.legalTargets,
                                    onSquareTap    = viewModel::onSquareTapped
                                )
                                // Red flash overlay on wrong move
                                if (uiState.showWrong) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .background(boardTint)
                                    )
                                }
                            }
                        }
                    }

                    // Status row
                    val (statusText, statusColor) = when {
                        uiState.showSuccess -> "\u2713  Correct!  Well done!" to Color(0xFF22C55E)
                        uiState.showWrong   -> "\u2717  Not quite \u2014 try again" to Color(0xFFEF4444)
                        uiState.showHint    -> "Hint: ${puzzle.row.bestMove}" to Color(0xFFF97316)
                        else                -> "Find the best move" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                        if (!uiState.showSuccess) {
                            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                // Hint button — disabled once hint is already shown
                                IconButton(
                                    onClick = viewModel::showHint,
                                    enabled = !uiState.showHint,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Lightbulb,
                                        contentDescription = "Hint",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (uiState.showHint)
                                            MaterialTheme.colorScheme.outlineVariant
                                        else
                                            Color(0xFFF97316)
                                    )
                                }
                                TextButton(
                                    onClick = viewModel::skipPuzzle,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Skip \u2192", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // Confetti overlay — sits on top of everything
        if (uiState.showSuccess) {
            ConfettiAnimation(modifier = Modifier.fillMaxSize())
        }
    }
}
