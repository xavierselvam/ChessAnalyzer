package com.chessanalyzer.ui.screens.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chessanalyzer.domain.model.*
import com.chessanalyzer.ui.components.getClassificationColor
import com.chessanalyzer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    gameId: String,
    onBackClick: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(gameId) {
        viewModel.loadReview(gameId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game Review") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
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

        val result = uiState.analysisResult
        val game = uiState.game

        if (result == null || game == null) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = uiState.errorMessage ?: "No analysis data",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Accuracy scores
            item {
                AccuracyCard(
                    white = game.white,
                    black = game.black,
                    whiteAccuracy = result.whiteAccuracy,
                    blackAccuracy = result.blackAccuracy,
                    userColor = game.userColor
                )
            }

            // Game info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Game Info", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Result", game.result.display)
                        InfoRow("Platform", game.platform.displayName)
                        InfoRow("Time Control", game.timeControl)
                        game.opening?.let { InfoRow("Opening", it) }
                        InfoRow("Moves", "${game.totalMoves}")
                    }
                }
            }

            // Move classification breakdown
            item {
                ClassificationBreakdown(
                    title = "${game.white} (White)",
                    classifications = result.whiteClassifications,
                    totalMoves = result.evaluations.count { it.color == PieceColor.WHITE }
                )
            }

            item {
                ClassificationBreakdown(
                    title = "${game.black} (Black)",
                    classifications = result.blackClassifications,
                    totalMoves = result.evaluations.count { it.color == PieceColor.BLACK }
                )
            }

            // Key moments
            if (result.keyMoments.isNotEmpty()) {
                item {
                    Text(
                        text = "Key Moments",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(result.keyMoments) { moment ->
                    KeyMomentCard(moment)
                }
            }
        }
    }
}

@Composable
private fun AccuracyCard(
    white: String,
    black: String,
    whiteAccuracy: Float,
    blackAccuracy: Float,
    userColor: PieceColor
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Accuracy",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AccuracyCircle(
                    playerName = white,
                    accuracy = whiteAccuracy,
                    isUser = userColor == PieceColor.WHITE,
                    pieceColor = "White"
                )
                AccuracyCircle(
                    playerName = black,
                    accuracy = blackAccuracy,
                    isUser = userColor == PieceColor.BLACK,
                    pieceColor = "Black"
                )
            }
        }
    }
}

@Composable
private fun AccuracyCircle(
    playerName: String,
    accuracy: Float,
    isUser: Boolean,
    pieceColor: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    when {
                        accuracy >= 90 -> BrilliantColor
                        accuracy >= 75 -> GreatColor
                        accuracy >= 60 -> GoodColor
                        accuracy >= 40 -> InaccuracyColor
                        else -> MistakeColor
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${accuracy.toInt()}%",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isUser) "$playerName (You)" else playerName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isUser) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            text = pieceColor,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ClassificationBreakdown(
    title: String,
    classifications: Map<MoveClassification, Int>,
    totalMoves: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            val orderedClassifications = listOf(
                MoveClassification.BRILLIANT,
                MoveClassification.GREAT,
                MoveClassification.GOOD,
                MoveClassification.BOOK,
                MoveClassification.INACCURACY,
                MoveClassification.MISTAKE,
                MoveClassification.BLUNDER
            )

            for (classification in orderedClassifications) {
                val count = classifications[classification] ?: 0
                if (count > 0 || classification in listOf(
                        MoveClassification.INACCURACY,
                        MoveClassification.MISTAKE,
                        MoveClassification.BLUNDER
                    )
                ) {
                    ClassificationRow(
                        classification = classification,
                        count = count,
                        total = totalMoves
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassificationRow(
    classification: MoveClassification,
    count: Int,
    total: Int
) {
    val fraction = if (total > 0) count.toFloat() / total else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Color dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(getClassificationColor(classification))
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "${classification.displayName} ${classification.symbol}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp)
        )

        // Bar
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(7.dp))
                    .background(getClassificationColor(classification))
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(30.dp)
        )
    }
}

@Composable
private fun KeyMomentCard(moment: MoveEvaluation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = getClassificationColor(moment.classification).copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(getClassificationColor(moment.classification)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = moment.classification.symbol,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "Move ${moment.moveNumber}. ${moment.moveSan} (${moment.color.name.lowercase()})",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${moment.classification.displayName} — " +
                            "Best was ${moment.bestMove} (${moment.centipawnLoss}cp loss)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
