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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Intent
import com.chessanalyzer.domain.engine.OpeningExplorerResult
import com.chessanalyzer.domain.engine.TablebaseResult
import com.chessanalyzer.domain.model.AnalysisStatus
import com.chessanalyzer.domain.model.MoveClassification
import com.chessanalyzer.ui.components.ChessBoardView
import com.chessanalyzer.ui.components.EngineLinesPanel
import com.chessanalyzer.ui.components.EvalBar
import com.chessanalyzer.ui.components.MoveList
import com.chessanalyzer.ui.components.getClassificationColor

@Composable
fun GameDetailScreen(
    gameId: String,
    onBackClick: () -> Unit,
    onReviewClick: (String) -> Unit,
    viewModel: GameDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val context = LocalContext.current

    LaunchedEffect(gameId) {
        viewModel.loadGame(gameId)
    }

    // Fetch opening explorer when navigating positions (opening phase ≤ move 25)
    LaunchedEffect(uiState.currentPositionIndex, uiState.positions.size) {
        val pos = uiState.positions.getOrNull(uiState.currentPositionIndex)
        if (pos != null && (pos.moveNumber) <= 25 && !uiState.isExploreMode) {
            viewModel.fetchOpeningContinuations(uiState.currentFen)
        }
    }

    // One-shot PGN share when export is ready
    LaunchedEffect(uiState.pgnExportText) {
        val pgn = uiState.pgnExportText ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, pgn)
            putExtra(Intent.EXTRA_SUBJECT, "Annotated Chess PGN")
        }
        context.startActivity(Intent.createChooser(intent, "Share PGN"))
        viewModel.consumePgnExport()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .fillMaxSize()
        ) {
            // ── Opponent row (with Review tucked in on the right) + eval bars ─
            uiState.game?.let { game ->
                val isUserWhite = game.userColor.name == "WHITE"
                val opponentName = if (isUserWhite) game.black else game.white
                val opponentClock = if (isUserWhite) uiState.currentBlackClock else uiState.currentWhiteClock
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = opponentName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (game.opponentRating != null) {
                        Text(
                            text = "${game.opponentRating}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                    if (opponentClock != null) {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = opponentClock.removePrefix("0:"),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (game.analysisStatus == AnalysisStatus.DONE) {
                        TextButton(
                            onClick = { onReviewClick(gameId) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .padding(start = 4.dp)
                        ) {
                            Text(
                                "Review",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            // ── Eval centipawns (used for vertical bar + WDL text) ──────────
            val evalCpForBars: Int? = run {
                val eval = uiState.currentMoveEval
                when {
                    uiState.isExploreMode && uiState.liveBoardEvalCp != null -> uiState.liveBoardEvalCp
                    uiState.evaluations.isNotEmpty() ->
                        eval?.evalAfter ?: uiState.evaluations.first().evalBefore
                    else -> null
                }
            }

            // ── Engine lines ABOVE board ───────────────────────────────────
            val engineLines = if (uiState.isExploreMode) uiState.explorePvLines
                              else uiState.reviewEngineLines
            if (engineLines.isNotEmpty() || uiState.isExploreAnalyzing) {
                EngineLinesPanel(
                    lines = engineLines,
                    isLoading = uiState.isExploreAnalyzing && engineLines.isEmpty(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            // ── Explore-mode banner ────────────────────────────────────────
            if (uiState.isExploreMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    color = if (uiState.isPracticeMode)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                if (uiState.isPracticeMode) Icons.Default.SportsEsports else Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (uiState.isPracticeMode)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = when {
                                    uiState.isEngineThinking -> "Engine thinking…"
                                    uiState.isExploreAnalyzing -> "Analysing…"
                                    uiState.isPracticeMode -> "Practice mode"
                                    else -> "Explore mode"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isPracticeMode)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            if (uiState.isExploreAnalyzing || uiState.isEngineThinking) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            TextButton(
                                onClick = { viewModel.togglePracticeMode(playerIsWhite = true) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                enabled = !uiState.isEngineThinking
                            ) {
                                Text(
                                    if (uiState.isPracticeMode) "Free" else "Practice",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(
                                onClick = viewModel::exitExploreMode,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("← Game", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // ── Chess board + vertical eval bar ────────────────────────────
            val actualArrowColor = uiState.actualMoveClassification?.let {
                when (it) {
                    MoveClassification.BLUNDER, MoveClassification.MISTAKE ->
                        Color(0xEFEF4444.toInt())
                    MoveClassification.INACCURACY ->
                        Color(0xEFF97316.toInt())
                    else -> Color(0xEFEF4444.toInt())
                }
            } ?: Color(0xEFEF4444.toInt())

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val evalBarWidth = if (evalCpForBars != null) 10.dp else 0.dp
                val gap = if (evalCpForBars != null) 2.dp else 0.dp
                val boardWidth = maxWidth - evalBarWidth - gap
                Row {
                    if (evalCpForBars != null) {
                        val eval = uiState.currentMoveEval
                        EvalBar(
                            centipawns = evalCpForBars,
                            isMate = eval?.isMate ?: false,
                            mateIn = eval?.mateIn,
                            vertical = true,
                            modifier = Modifier.width(evalBarWidth).height(boardWidth)
                        )
                        Spacer(modifier = Modifier.width(gap))
                    }
                    ChessBoardView(
                        fen = uiState.boardFen,
                        modifier = Modifier.width(boardWidth),
                        flipped = uiState.game?.userColor?.name == "BLACK",
                        lastMoveFrom = if (uiState.isExploreMode) null else uiState.lastMoveFrom,
                        lastMoveTo   = if (uiState.isExploreMode) null else uiState.lastMoveTo,
                        bestMoveFrom = uiState.boardBestArrowFrom,
                        bestMoveTo   = uiState.boardBestArrowTo,
                        actualMoveFrom = uiState.actualArrowFrom,
                        actualMoveTo   = uiState.actualArrowTo,
                        actualMoveColor = actualArrowColor,
                        selectedSquare = uiState.selectedSquare,
                        legalTargets   = uiState.legalTargets,
                        onSquareTap    = viewModel::onSquareTapped
                    )
                }
            }

            // ── User info row (below board) + WDL text ─────────────────────
            uiState.game?.let { game ->
                val isUserWhite = game.userColor.name == "WHITE"
                val userName = if (isUserWhite) game.white else game.black
                val userClock = if (isUserWhite) uiState.currentWhiteClock else uiState.currentBlackClock
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Thin left spacer to align with board (eval bar width + gap)
                    if (evalCpForBars != null) Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        PlayerInfoRow(name = userName, rating = game.userRating, clock = userClock, isUser = true)
                    }
                    // WDL compact text
                    if (evalCpForBars != null) {
                        val w = (1.0 / (1.0 + kotlin.math.exp(-0.004 * evalCpForBars))).toFloat()
                        val d = maxOf(0f, 0.45f - 0.6f * kotlin.math.abs(w - 0.5f))
                        val wPct = ((w - d / 2f).coerceIn(0f, 1f) * 100).toInt()
                        val dPct = (d.coerceIn(0f, 1f) * 100).toInt()
                        val bPct = 100 - wPct - dPct
                        Text(
                            text = "W$wPct D$dPct B$bPct",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }

            // ── Analysis progress ──────────────────────────────────────────
            if (uiState.isAnalyzing || uiState.isQueued) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.isAnalyzing)
                                "Analyzing… ${(uiState.analysisProgress * 100).toInt()}%"
                            else "Queued…",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (uiState.isAnalyzing) {
                            LinearProgressIndicator(
                                progress = { uiState.analysisProgress },
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                        }
                    }
                    IconButton(onClick = viewModel::cancelAnalysis, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel",
                            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ── Fixed bottom frame (fills remaining space, nav pinned at bottom) ──
            Column(modifier = Modifier.weight(1f)) {

                // Classification badge + best-move hint
                uiState.currentMoveEval?.let { eval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildString {
                                append(eval.classification.displayName)
                                if (eval.classification.symbol.isNotBlank())
                                    append(" ${eval.classification.symbol}")
                            },
                            style = MaterialTheme.typography.bodySmall,
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
                        if (eval.centipawnLoss > 30) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(−${eval.centipawnLoss}cp)",
                                style = MaterialTheme.typography.labelSmall,
                                color = getClassificationColor(eval.classification).copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // Opening name label (compact single line)
                val openingName = uiState.openingResult?.opening
                if (openingName != null) {
                    Text(
                        text = openingName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.dp)
                    )
                }

                // Tablebase compact row
                uiState.tablebaseResult?.let { tb ->
                    TablebaseCard(
                        result = tb,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                // Move list — fills remaining height in this frame
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

                // Navigation controls — pinned at bottom of frame
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = viewModel::goToStart, enabled = uiState.canGoBack) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Start")
                    }
                    IconButton(onClick = viewModel::goBack, enabled = uiState.canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous")
                    }
                    Text(
                        text = "${uiState.currentPositionIndex} / ${uiState.totalPositions - 1}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = viewModel::goForward, enabled = uiState.canGoForward) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
                    }
                    IconButton(onClick = viewModel::goToEnd, enabled = uiState.canGoForward) {
                        Icon(Icons.Default.SkipNext, contentDescription = "End")
                    }
                }

                // Action buttons — pinned at bottom
                val game = uiState.game
                if (game != null && !uiState.isAnalyzing && !uiState.isQueued) {
                    when {
                        game.analysisStatus == AnalysisStatus.PENDING -> Button(
                            onClick = viewModel::analyzeGame,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Analyze with Stockfish")
                        }
                        game.analysisStatus == AnalysisStatus.DONE -> Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = viewModel::analyzeGame, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Re-analyze", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(onClick = viewModel::exportAnnotatedPgn, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share PGN", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        else -> {}
                    }
                }
            } // end fixed bottom frame
        }
    }
}

@Composable
private fun OpeningExplorerCard(
    result: OpeningExplorerResult,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (result.opening != null) {
                Text(
                    text = result.opening,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            result.moves.take(5).forEach { move ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = move.san,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(40.dp)
                    )
                    // Mini WDL bar
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .padding(horizontal = 4.dp)
                    ) {
                        if (move.winPct > 0) {
                            Surface(
                                modifier = Modifier
                                    .weight(move.winPct)
                                    .fillMaxHeight(),
                                color = Color(0xFFF0EDE5),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {}
                        }
                        if (move.drawPct > 0) {
                            Surface(
                                modifier = Modifier
                                    .weight(move.drawPct)
                                    .fillMaxHeight(),
                                color = Color(0xFF9E9E9E)
                            ) {}
                        }
                        if (move.lossPct > 0) {
                            Surface(
                                modifier = Modifier
                                    .weight(move.lossPct)
                                    .fillMaxHeight(),
                                color = Color(0xFF1A1A1A),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {}
                        }
                    }
                    Text(
                        text = "${move.total / 1000}k",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
private fun TablebaseCard(
    result: TablebaseResult,
    modifier: Modifier = Modifier
) {
    val (bgColor, label, icon) = when (result.category) {
        "win"          -> Triple(Color(0xFF4CAF50).copy(alpha = 0.18f), "Winning",       Icons.Default.EmojiEvents)
        "cursed-win"   -> Triple(Color(0xFF8BC34A).copy(alpha = 0.18f), "Cursed Win",    Icons.Default.EmojiEvents)
        "draw"         -> Triple(Color(0xFF9E9E9E).copy(alpha = 0.18f), "Draw",          Icons.Default.Balance)
        "loss"         -> Triple(Color(0xFFF44336).copy(alpha = 0.18f), "Losing",        Icons.Default.SentimentDissatisfied)
        "blessed-loss" -> Triple(Color(0xFFFF9800).copy(alpha = 0.18f), "Blessed Loss",  Icons.Default.Balance)
        else           -> Triple(Color(0xFF9E9E9E).copy(alpha = 0.18f), "Unknown",       Icons.Default.Help)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Column {
                Text(
                    text = "Tablebase: $label",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (result.dtz != null) {
                    Text(
                        text = if (result.dtm != null) "DTM ${result.dtm}  •  DTZ ${result.dtz}" else "DTZ ${result.dtz}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
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
