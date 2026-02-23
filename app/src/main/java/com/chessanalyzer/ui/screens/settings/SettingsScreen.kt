package com.chessanalyzer.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chessanalyzer.ui.screens.games.gameTypeOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showRefreshDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    // Intercept system back when there are unsaved changes
    BackHandler(enabled = uiState.pendingChanges) {
        showUnsavedDialog = true
    }

    LaunchedEffect(uiState.saveMessage) {
        uiState.saveMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    if (uiState.pendingChanges) {
                        TextButton(onClick = viewModel::saveSettings) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Save")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- Accounts Section ---
            Text(
                text = "Accounts",
                style = MaterialTheme.typography.headlineMedium
            )

            // Chess.com
            AccountField(
                label = "Chess.com Username",
                value = uiState.chessComUsername,
                onValueChange = viewModel::onChessComUsernameChange,
                onSave = viewModel::saveChessComUsername,
                isValidating = uiState.isValidatingChessCom,
                isValid = uiState.chessComValid
            )

            // Lichess
            AccountField(
                label = "Lichess Username",
                value = uiState.lichessUsername,
                onValueChange = viewModel::onLichessUsernameChange,
                onSave = viewModel::saveLichessUsername,
                isValidating = uiState.isValidatingLichess,
                isValid = uiState.lichessValid
            )

            HorizontalDivider()

            // --- Sync Filters Section ---
            Text(text = "Sync Filters", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Exclude game types from being imported during sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            gameTypeOptions
                .filter { (type, _) -> type != null }
                .forEach { (type, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = type!! !in uiState.excludedSyncTypes,
                            onCheckedChange = { viewModel.toggleExcludedSyncType(type) }
                        )
                    }
                }

            HorizontalDivider()

            // --- Analysis Section ---
            Text(
                text = "Analysis",
                style = MaterialTheme.typography.headlineMedium
            )

            // Engine Mode
            Column {
                Text("Engine", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Cloud: instant (Lichess DB). Hybrid: cloud for known positions, Stockfish fallback. Local: offline Stockfish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                listOf(
                    "cloud" to "☁️  Cloud (Lichess) — instant, requires internet",
                    "hybrid" to "⚡ Hybrid — cloud for openings, Stockfish fallback",
                    "local" to "📱 Local (Stockfish) — offline, slower on mobile"
                ).forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.engineMode == value,
                            onClick = { viewModel.onEngineModeChange(value) }
                        )
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 4.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Engine Depth (relevant for local and hybrid modes)
            if (uiState.engineMode == "local" || uiState.engineMode == "hybrid") {
                Text(
                    text = "Engine Depth: ${uiState.engineDepth}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Higher depth = more accurate but slower",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = uiState.engineDepth.toFloat(),
                    onValueChange = { viewModel.onEngineDepthChange(it.toInt()) },
                    valueRange = 10f..22f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Fast (10)", style = MaterialTheme.typography.bodySmall)
                    Text("Recommended (14)", style = MaterialTheme.typography.bodySmall)
                    Text("Deep (22)", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Auto-analyze
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-analyze new games", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Analyze all games in the background, even when the app is closed. Latest games are analyzed first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.autoAnalyze,
                    onCheckedChange = viewModel::onAutoAnalyzeChange
                )
            }

            HorizontalDivider()

            // --- Appearance Section ---
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.headlineMedium
            )

            // Theme selection
            Column {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.darkMode == value,
                            onClick = { viewModel.onDarkModeChange(value) }
                        )
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Save button shown whenever Analysis or Appearance has unsaved changes
            if (uiState.pendingChanges) {
                Button(
                    onClick = viewModel::saveSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save Settings")
                }
            }

            HorizontalDivider()

            // --- Data Section ---
            Text("Data", style = MaterialTheme.typography.headlineMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Games stored", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${uiState.analyzedGames} / ${uiState.totalGames} analyzed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (uiState.analysisStatus != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        uiState.analysisStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column {
                Text(
                    "Clear & re-sync all games",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Deletes all locally stored games and re-imports them from Chess.com / Lichess. " +
                           "Run this once to populate game type and rating data for the Stats screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showRefreshDialog = true },
                    enabled = !uiState.isRefreshing
                ) {
                    if (uiState.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Re-syncing…")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh All Game Data")
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showRefreshDialog) {
        AlertDialog(
            onDismissRequest = { showRefreshDialog = false },
            title = { Text("Refresh All Game Data?") },
            text = {
                Text(
                    "This will delete all locally stored games and re-import them from your " +
                    "Chess.com and Lichess accounts.\n\nAnalysis results will also be lost. " +
                    "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshDialog = false
                    viewModel.refreshAllGameData()
                }) { Text("Refresh", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved Analysis / Appearance changes. Save them before leaving?") },
            confirmButton = {
                Button(onClick = {
                    showUnsavedDialog = false
                    viewModel.saveSettings()
                }) { Text("Save") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showUnsavedDialog = false }) { Text("Stay") }
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        viewModel.discardChanges()
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}

@Composable
private fun AccountField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    isValidating: Boolean,
    isValid: Boolean?
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                when {
                    isValidating -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    isValid == true -> Icon(
                        Icons.Default.Check,
                        contentDescription = "Valid",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    isValid == false -> Icon(
                        Icons.Default.Close,
                        contentDescription = "Invalid",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onSave,
            enabled = !isValidating,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(if (isValidating) "Validating..." else "Save & Verify")
        }
    }
}
