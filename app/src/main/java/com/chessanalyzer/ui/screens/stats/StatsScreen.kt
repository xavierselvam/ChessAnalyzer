package com.chessanalyzer.ui.screens.stats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chessanalyzer.data.local.db.RatingPoint
import com.chessanalyzer.domain.model.WeaknessInsight
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val GAME_TYPES = listOf(
    "bullet"    to "Bullet",
    "blitz"     to "Blitz",
    "rapid"     to "Rapid",
    "classical" to "Classical",
    "daily"     to "Daily"
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onOpeningClick: (String) -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    val initialPage = GAME_TYPES.indexOfFirst { it.first == state.selectedGameType }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { GAME_TYPES.size }

    // Swipe → ViewModel
    LaunchedEffect(pagerState.settledPage) {
        val type = GAME_TYPES[pagerState.settledPage].first
        if (type != state.selectedGameType) viewModel.onGameTypeSelected(type)
    }
    // Tab click → pager
    LaunchedEffect(state.selectedGameType) {
        val idx = GAME_TYPES.indexOfFirst { it.first == state.selectedGameType }.coerceAtLeast(0)
        if (pagerState.currentPage != idx) pagerState.animateScrollToPage(idx)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(title = { Text("Statistics") })

        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 16.dp,
            divider = {}
        ) {
            GAME_TYPES.forEachIndexed { index, (type, label) ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        viewModel.onGameTypeSelected(type)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = { Text(label, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TimeRangeSelector(state.selectedTimeRange, viewModel::onTimeRangeSelected)

                    val currentRating = state.currentRating
                    if (currentRating == null) {
                        EmptyStatsState(state.selectedGameType)
                    } else {
                        RatingBanner(rating = currentRating, delta = state.ratingDelta)
                        StatChipsRow(state.totalGames, state.wins, state.losses, state.draws)
                        if (state.ratingHistory.size >= 2) {
                            RatingChart(
                                history = state.ratingHistory,
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                            )
                        }
                        if (state.totalGames > 0) {
                            WinLossBar(state.wins, state.draws, state.losses, state.totalGames)
                        }
                        if (state.favoriteOpenings.isNotEmpty()) {
                            FavoriteOpeningsSection(state.favoriteOpenings, onOpeningClick = onOpeningClick)
                        }
                        if (state.struggleOpenings.isNotEmpty()) {
                            StruggleOpeningsSection(state.struggleOpenings, onOpeningClick = onOpeningClick)
                        }
                        WeaknessSection(state.weaknessInsights)
                    }
                }
            }
        }
    }
}

// ── Time range selector ────────────────────────────────────────────────────
@Composable
private fun TimeRangeSelector(selected: StatTimeRange, onSelect: (StatTimeRange) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTimeRange.entries.forEach { range ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(range.label, fontSize = 11.sp, maxLines = 1) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Rating banner ──────────────────────────────────────────────────────────
@Composable
private fun RatingBanner(rating: Int, delta: Int?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                rating.toString(),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (delta != null && delta != 0) {
                val sign = if (delta > 0) "+" else ""
                Text(
                    "$sign$delta",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (delta > 0) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error
                )
            }
            Text(
                "Current Rating",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

// ── Stat chips ─────────────────────────────────────────────────────────────
@Composable
private fun StatChipsRow(total: Int, wins: Int, losses: Int, draws: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatChip("Total",  total.toString(),   Modifier.weight(1f))
        StatChip("Wins",   wins.toString(),    Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
        StatChip("Losses", losses.toString(),  Modifier.weight(1f), MaterialTheme.colorScheme.error)
        StatChip("Draws",  draws.toString(),   Modifier.weight(1f))
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = valueColor)
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

// ── Rating chart ───────────────────────────────────────────────────────────
@Composable
fun RatingChart(history: List<RatingPoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillTop   = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val fillBot   = MaterialTheme.colorScheme.primary.copy(alpha = 0.0f)

    var scrubX by remember { mutableStateOf(-1f) }

    Card(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                scrubX = down.position.x
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.also { scrubX = it.position.x }
                } while (event.changes.any { it.pressed })
                scrubX = -1f
            }
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 16.dp)) {
            if (history.size < 2) return@Canvas
            val minR = history.minOf { it.userRating }.toFloat()
            val maxR = history.maxOf { it.userRating }.toFloat()
            val rRange = if (maxR == minR) 1f else maxR - minR
            val minT = history.first().playedAt.toFloat()
            val maxT = history.last().playedAt.toFloat()
            val tRange = if (maxT == minT) 1f else maxT - minT
            val yPad = size.height * 0.08f
            val drawH = size.height - yPad * 2f

            fun px(ts: Long) = (ts.toFloat() - minT) / tRange * size.width
            fun py(r: Int)   = yPad + drawH - ((r.toFloat() - minR) / rRange) * drawH

            val linePath = Path(); val fillPath = Path()
            history.forEachIndexed { i, pt ->
                val x = px(pt.playedAt); val y = py(pt.userRating)
                if (i == 0) { linePath.moveTo(x, y); fillPath.moveTo(x, y) }
                else        { linePath.lineTo(x, y); fillPath.lineTo(x, y) }
            }
            fillPath.lineTo(size.width, size.height)
            fillPath.lineTo(0f, size.height)
            fillPath.close()

            drawPath(fillPath, Brush.verticalGradient(listOf(fillTop, fillBot), 0f, size.height))
            drawPath(linePath, lineColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val ex = px(history.last().playedAt); val ey = py(history.last().userRating)
            drawCircle(lineColor, 5.dp.toPx(), Offset(ex, ey))
            drawCircle(Color.White, 3.dp.toPx(), Offset(ex, ey))

            // ── Scrub crosshair ───────────────────────────────────────────
            if (scrubX >= 0f) {
                val hPad = 8.dp.toPx()
                val adjustedX = (scrubX - hPad).coerceIn(0f, size.width.coerceAtLeast(1f))
                val fraction = adjustedX / size.width.coerceAtLeast(1f)
                val idx = (fraction * (history.size - 1)).roundToInt().coerceIn(0, history.size - 1)
                val pt = history[idx]
                val cx = px(pt.playedAt)
                val cy = py(pt.userRating)

                // Vertical dashed line
                drawLine(
                    color = lineColor.copy(alpha = 0.45f),
                    start = Offset(cx, yPad),
                    end = Offset(cx, size.height - yPad),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                )
                // Highlight circle
                drawCircle(lineColor, 6.dp.toPx(), Offset(cx, cy))
                drawCircle(Color.White, 4.dp.toPx(), Offset(cx, cy))

                // Rating pill via native canvas
                val labelText = "${pt.userRating}"
                val nativeCanvas = drawContext.canvas.nativeCanvas
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val bgPaint = android.graphics.Paint().apply {
                    color = lineColor.copy(alpha = 0.92f).toArgb()
                    isAntiAlias = true
                }
                val tb = android.graphics.Rect()
                textPaint.getTextBounds(labelText, 0, labelText.length, tb)
                val lx = cx.coerceIn(tb.width() / 2f + 10f, size.width - tb.width() / 2f - 10f)
                val ly = (cy - 14.dp.toPx()).coerceIn(yPad + tb.height() + 8f, size.height - yPad)
                val rect = android.graphics.RectF(
                    lx - tb.width() / 2f - 8f, ly - tb.height() - 4f,
                    lx + tb.width() / 2f + 8f, ly + 4f
                )
                nativeCanvas.drawRoundRect(rect, 8f, 8f, bgPaint)
                nativeCanvas.drawText(labelText, lx, ly, textPaint)
            }
        }
    }
}

// ── Win/Loss/Draw bar ──────────────────────────────────────────────────────
@Composable
private fun WinLossBar(wins: Int, draws: Int, losses: Int, total: Int) {
    val winF  = wins.toFloat()   / total
    val drawF = draws.toFloat()  / total
    val lossF = losses.toFloat() / total
    val winClr  = MaterialTheme.colorScheme.tertiary
    val drawClr = MaterialTheme.colorScheme.outlineVariant
    val lossClr = MaterialTheme.colorScheme.error

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Results",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val wW = winF * w; val dW = drawF * w; val lW = lossF * w
                drawRect(winClr,  size = androidx.compose.ui.geometry.Size(wW, h))
                drawRect(drawClr, topLeft = Offset(wW, 0f),       size = androidx.compose.ui.geometry.Size(dW, h))
                drawRect(lossClr, topLeft = Offset(wW + dW, 0f),  size = androidx.compose.ui.geometry.Size(lW, h))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LegendItem(winClr,  "$wins W")
            LegendItem(drawClr, "$draws D")
            LegendItem(lossClr, "$losses L")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ── Favorite openings ─────────────────────────────────────────────────────
@Composable
private fun FavoriteOpeningsSection(openings: List<OpeningStat>, onOpeningClick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Favorite Openings",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            openings.forEach { opening ->
                OpeningRow(opening, highlightColor = MaterialTheme.colorScheme.tertiary, onOpeningClick = onOpeningClick)
            }
        }
    }
}

// ── Struggle openings ──────────────────────────────────────────────────────
@Composable
private fun StruggleOpeningsSection(openings: List<OpeningStat>, onOpeningClick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Openings to Work On",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            openings.forEach { opening ->
                OpeningRow(opening, highlightColor = MaterialTheme.colorScheme.error, onOpeningClick = onOpeningClick)
            }
        }
    }
}

@Composable
private fun OpeningRow(opening: OpeningStat, highlightColor: Color, onOpeningClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onOpeningClick(opening.name) }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = opening.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${opening.played}g",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${opening.wins}W",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    "${opening.draws}D",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${opening.losses}L",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        // Mini W/D/L bar
        if (opening.played > 0) {
            val wF = opening.wins.toFloat() / opening.played
            val dF = opening.draws.toFloat() / opening.played
            val lF = opening.losses.toFloat() / opening.played
            val winBarColor = highlightColor.copy(alpha = if (opening.wins >= opening.losses) 1f else 0.4f)
            val drawBarColor = Color.Gray.copy(alpha = 0.3f)
            val lossBarColor = MaterialTheme.colorScheme.error.copy(alpha = if (opening.losses > opening.wins) 1f else 0.4f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    val wW = wF * w; val dW = dF * w; val lW = lF * w
                    drawRect(winBarColor, size = androidx.compose.ui.geometry.Size(wW, h))
                    drawRect(drawBarColor,
                        topLeft = Offset(wW, 0f),
                        size = androidx.compose.ui.geometry.Size(dW, h))
                    drawRect(lossBarColor,
                        topLeft = Offset(wW + dW, 0f),
                        size = androidx.compose.ui.geometry.Size(lW, h))
                }
            }
        }
    }
}

// ── Weakness radar ─────────────────────────────────────────────────────────
@Composable
private fun WeaknessSection(insights: List<WeaknessInsight>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Recurring Mistakes",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (insights.isEmpty()) {
                Text(
                    "No repeating patterns found yet. Analyze more games to surface recurring mistakes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Positions from analyzed games where you repeatedly played a blunder or mistake.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                insights.forEach { insight ->
                    WeaknessRow(insight)
                }
            }
        }
    }
}

@Composable
private fun WeaknessRow(insight: WeaknessInsight) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "You played",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    insight.userMove,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Better was",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    insight.bestMove,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        // Occurrence badge
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Text(
                "×${insight.occurrences}",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// ── Empty state ────────────────────────────────────────────────────────────
@Composable
private fun EmptyStatsState(gameType: String) {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "No ${gameType.replaceFirstChar { it.uppercase() }} games yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Sync your games to see rating statistics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
