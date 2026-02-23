package com.chessanalyzer.ui.screens.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chessanalyzer.domain.model.*
import com.chessanalyzer.ui.components.GameCard
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    onGameClick: (String) -> Unit,
    initialOpeningFilter: String? = null,
    onBackClick: (() -> Unit)? = null,
    viewModel: GamesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialOpeningFilter) {
        viewModel.setOpeningFilter(initialOpeningFilter)
    }

    LaunchedEffect(uiState.syncMessage) {
        uiState.syncMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(260.dp)) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp)
                )
                HorizontalDivider()
                Text(
                    "Platform",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(null to "All", Platform.CHESS_COM to "Chess.com", Platform.LICHESS to "Lichess")
                    .forEach { (platform, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onPlatformFilterChange(platform) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.selectedPlatform == platform,
                                onClick = { viewModel.onPlatformFilterChange(platform) }
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Game Type",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleGameTypeFilter(null) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = uiState.selectedGameTypes.isEmpty(),
                        onCheckedChange = { viewModel.toggleGameTypeFilter(null) }
                    )
                    Text("All", style = MaterialTheme.typography.bodyMedium)
                }
                gameTypeOptions.filter { it.first != null }.forEach { (type, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleGameTypeFilter(type) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = type in uiState.selectedGameTypes,
                            onCheckedChange = { viewModel.toggleGameTypeFilter(type) }
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.openingFilter != null) {
                        Text(uiState.openingFilter!!, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("Chess Analyzer")
                    }
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Filters")
                        }
                    }
                },
                actions = {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 12.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = viewModel::syncGames) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Opening filter chip shown inline when navigated from Stats
            uiState.openingFilter?.let { opening ->
                OpeningFilterChip(
                    opening = opening,
                    onClear = { viewModel.setOpeningFilter(null) }
                )
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.games.isEmpty() -> {
                    EmptyGamesState(
                        isSyncing = uiState.isSyncing,
                        onSyncClick = viewModel::syncGames
                    )
                }

                else -> {
                    GamesList(
                        games = uiState.games,
                        onGameClick = onGameClick,
                        onAnalyzeGame = viewModel::analyzeGame,
                        onCancelGame = viewModel::cancelGame,
                        analysisProgressMap = uiState.analysisProgressMap,
                        queuedGameIds = uiState.queuedGameIds,
                        autoQueuedIds = uiState.autoQueuedIds
                    )
                }
            }
        }
    }   // closes Scaffold
    }   // closes ModalNavigationDrawer
}

val gameTypeOptions = listOf(
    null to "All",
    "bullet" to "Bullet",
    "blitz" to "Blitz",
    "rapid" to "Rapid",
    "classical" to "Classical",
    "daily" to "Daily"
)

@Composable
private fun OpeningFilterChip(opening: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        FilterChip(
            selected = true,
            onClick = onClear,
            label = { Text("Opening: $opening", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(Icons.Default.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp))
            }
        )
    }
}

@Composable
private fun GamesList(
    games: List<Game>,
    onGameClick: (String) -> Unit,
    onAnalyzeGame: (String) -> Unit = {},
    onCancelGame: (String) -> Unit = {},
    analysisProgressMap: Map<String, Float> = emptyMap(),
    queuedGameIds: Set<String> = emptySet(),
    autoQueuedIds: Set<String> = emptySet()
) {
    // --- Build year → month structure ---
    val cal = Calendar.getInstance()
    val nowYear = cal.get(Calendar.YEAR)
    val nowMonth = cal.get(Calendar.MONTH) // 0-based

    data class MonthGroup(val year: Int, val month: Int, val label: String, val games: List<Game>)
    data class YearGroup(val year: Int, val months: List<MonthGroup>, val total: Int)

    val monthNames = listOf("January","February","March","April","May","June",
        "July","August","September","October","November","December")

    val yearGroups: List<YearGroup> = remember(games) {
        games
            .groupBy { game ->
                cal.timeInMillis = game.playedAt
                cal.get(Calendar.YEAR)
            }
            .entries
            .sortedByDescending { it.key }
            .map { (year, yearGames) ->
                val months = yearGames
                    .groupBy { game ->
                        cal.timeInMillis = game.playedAt
                        cal.get(Calendar.MONTH)
                    }
                    .entries
                    .sortedByDescending { it.key }
                    .map { (month, mGames) ->
                        MonthGroup(year, month, monthNames[month], mGames.sortedByDescending { it.playedAt })
                    }
                YearGroup(year, months, yearGames.size)
            }
    }

    // Expanded state: years and months. Default: current year + current month open.
    val expandedYears = remember { mutableStateMapOf<Int, Boolean>().also { it[nowYear] = true } }
    val expandedMonths = remember { mutableStateMapOf<String, Boolean>()
        .also { it["${nowYear}-${nowMonth}"] = true } }

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            yearGroups.forEach { yearGroup ->
                val yearExpanded = expandedYears[yearGroup.year] == true

                // --- Year header ---
                item(key = "year-${yearGroup.year}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedYears[yearGroup.year] = !yearExpanded }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (yearExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = yearGroup.year.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${yearGroup.total} games",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (yearExpanded) {
                    yearGroup.months.forEach { monthGroup ->
                        val monthKey = "${monthGroup.year}-${monthGroup.month}"
                        val monthExpanded = expandedMonths[monthKey] == true

                        // --- Month header ---
                        item(key = "month-$monthKey") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedMonths[monthKey] = !monthExpanded }
                                    .padding(start = 22.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (monthExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = monthGroup.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${monthGroup.games.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (monthExpanded) {
                            items(
                                items = monthGroup.games,
                                key = { it.id },
                                contentType = { "game-card" }
                            ) { game ->
                                GameCard(
                                    game = game,
                                    onClick = { onGameClick(game.id) },
                                    onAnalyze = { onAnalyzeGame(game.id) },
                                    onCancel = { onCancelGame(game.id) },
                                    analysisProgress = analysisProgressMap[game.id] ?: 0f,
                                    isQueued = game.id in queuedGameIds ||
                                        game.id in autoQueuedIds
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- Scrollbar ---
        val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        val layoutInfo by remember { derivedStateOf { listState.layoutInfo } }
        val totalItems = layoutInfo.totalItemsCount
        val visibleItems = layoutInfo.visibleItemsInfo
        if (totalItems > 0 && visibleItems.isNotEmpty()) {
            val thumbFraction = (visibleItems.size.toFloat() / totalItems).coerceIn(0.02f, 1f)
            if (thumbFraction < 1f) {
                val scrollFraction by remember {
                    derivedStateOf {
                        val first = listState.firstVisibleItemIndex
                        val itemH = visibleItems.firstOrNull()?.size?.toFloat() ?: 1f
                        val offset = listState.firstVisibleItemScrollOffset / itemH
                        ((first + offset) / (totalItems - visibleItems.size).toFloat()).coerceIn(0f, 1f)
                    }
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp)
                        .padding(vertical = 8.dp)
                ) {
                    val totalH = maxHeight
                    val thumbH = totalH * thumbFraction
                    val thumbTop = (totalH - thumbH) * scrollFraction
                    Box(
                        modifier = Modifier
                            .offset(y = thumbTop)
                            .width(4.dp)
                            .height(thumbH)
                            .clip(RoundedCornerShape(2.dp))
                            .background(thumbColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyGamesState(
    isSyncing: Boolean,
    onSyncClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "No games yet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Add your Chess.com or Lichess account in Settings,\nthen sync to import your games.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onSyncClick,
                enabled = !isSyncing
            ) {
                Text(if (isSyncing) "Syncing..." else "Sync Now")
            }
        }
    }
}
