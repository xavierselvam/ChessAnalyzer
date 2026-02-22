package com.chessanalyzer.ui.screens.gamedetail

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chessanalyzer.domain.model.AnalysisStatus
import com.chessanalyzer.ui.components.ChessBoardView
import com.chessanalyzer.ui.components.EvalBar
import com.chessanalyzer.ui.components.EvalChart
import com.chessanalyzer.ui.components.MoveList
import com.chessanalyzer.ui.components.getClassificationColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    gameId: String,
    onBackClick: () -> Unit,
    onReviewClick: (String) -> Unit,
    viewModel: GameDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(gameId) {
        viewModel.loadGame(gameId)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val game = uiState.game
                    if (game != null) {
                        Text("vs ${game.opponent}")
                    } else {
                        Text("Game")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val game = uiState.game
                    if (game?.analysisStatus == AnalysisStatus.DONE) {
                        TextButton(onClick = { onReviewClick(gameId) }) {
                            Text("Review")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ── Analysis progress / queued (always visible, includes Cancel) ──
            if (uiState.isAnalyzing || uiState.isQueued) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.isAnalyzing)
                                "Analyzing\u2026 ${(uiState.analysisProgress * 100).toInt()}%"
                            else
                                "Queued for analysis\u2026",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.isAnalyzing) {
                            LinearProgressIndicator(
                                progress = { uiState.analysisProgress },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    IconButton(onClick = viewModel::cancelAnalysis) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel analysis",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ── Evaluation bar ──────────────────────────────────────────────
            // At position 0 (start) currentMoveEval is null; use evalBefore of the
            // first move to show the initial position's Stockfish eval instead of 0.
            if (uiState.evaluations.isNotEmpty()) {
                val eval = uiState.currentMoveEval
                val cp = eval?.evalAfter ?: uiState.evaluations.first().evalBefore
                EvalBar(
                    centipawns = cp,
                    isMate = eval?.isMate ?: false,
                    mateIn = eval?.mateIn,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ── Opponent info row (above board) ────────────────────────────
            uiState.game?.let { game ->
                val isUserWhite = game.userColor.name == "WHITE"
                val opponentName = if (isUserWhite) game.black else game.white
                val opponentClock = if (isUserWhite) uiState.currentBlackClock else uiState.currentWhiteClock
                PlayerInfoRow(
                    name = opponentName,
                    rating = game.opponentRating,
                    clock = opponentClock,
                    isUser = false
                )
            }

            // ── Chess board with last-move highlight and best-move arrow ────
            ChessBoardView(
                fen = uiState.currentFen,
                modifier = Modifier.padding(horizontal = 16.dp),
                flipped = uiState.game?.userColor?.name == "BLACK",
                lastMoveFrom = uiState.lastMoveFrom,
                lastMoveTo = uiState.lastMoveTo,
                bestMoveFrom = uiState.bestMoveFrom,
                bestMoveTo = uiState.bestMoveTo
            )

            // ── User info row (below board) ────────────────────────────────
            uiState.game?.let { game ->
                val isUserWhite = game.userColor.name == "WHITE"
                val userName = if (isUserWhite) game.white else game.black
                val userClock = if (isUserWhite) uiState.currentWhiteClock else uiState.currentBlackClock
                PlayerInfoRow(
                    name = userName,
                    rating = game.userRating,
                    clock = userClock,
                    isUser = true
                )
            }

            // ── Move classification badge ───────────────────────────────────
            uiState.currentMoveEval?.let { eval ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildString {
                            append(eval.classification.displayName)
                            if (eval.classification.symbol.isNotBlank())
                                append(" ${eval.classification.symbol}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = getClassificationColor(eval.classification)
                    )
                    if (eval.bestMove.isNotBlank() && eval.bestMove != eval.moveSan) {
                        Text(
                            text = "  Best: ${eval.bestMove}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Centipawn loss indicator for mistakes and above
                    if (eval.centipawnLoss > 30) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(−${eval.centipawnLoss}cp)",
                            style = MaterialTheme.typography.bodySmall,
                            color = getClassificationColor(eval.classification).copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // ── Eval chart ─────────────────────────────────────────────────
            if (uiState.evaluations.isNotEmpty()) {
                EvalChart(
                    evaluations = uiState.evaluations,
                    currentMoveIndex = uiState.currentPositionIndex,
                    onMoveClick = viewModel::goToPosition,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ── Move list ──────────────────────────────────────────────────
            if (uiState.moveList.isNotEmpty()) {
                MoveList(
                    moves = uiState.moveList,
                    evaluations = uiState.evaluations,
                    currentMoveIndex = uiState.currentPositionIndex,
                    onMoveClick = viewModel::goToPosition,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // ── Navigation controls ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = viewModel::goToStart,
                    enabled = uiState.canGoBack
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Start")
                }
                IconButton(
                    onClick = viewModel::goBack,
                    enabled = uiState.canGoBack
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous")
                }

                Text(
                    text = "${uiState.currentPositionIndex} / ${uiState.totalPositions - 1}",
                    style = MaterialTheme.typography.bodyMedium
                )

                IconButton(
                    onClick = viewModel::goForward,
                    enabled = uiState.canGoForward
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
                }
                IconButton(
                    onClick = viewModel::goToEnd,
                    enabled = uiState.canGoForward
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "End")
                }
            }

            // ── Analyze button (only when idle) ──────────────────────────
            val game = uiState.game
            if (game != null && !uiState.isAnalyzing && !uiState.isQueued) {
                when {
                    game.analysisStatus == AnalysisStatus.PENDING -> Button(
                        onClick = viewModel::analyzeGame,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Psychology, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyze with Stockfish")
                    }
                    game.analysisStatus == AnalysisStatus.DONE -> OutlinedButton(
                        onClick = viewModel::analyzeGame,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Re-analyze")
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlayerInfoRow(
    name: String,
    rating: Int?,
    clock: String?,
    isUser: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isUser) FontWeight.SemiBold else FontWeight.Normal),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rating != null) {
                Text(
                    text = "$rating",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (clock != null) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = clock.removePrefix("0:"),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
