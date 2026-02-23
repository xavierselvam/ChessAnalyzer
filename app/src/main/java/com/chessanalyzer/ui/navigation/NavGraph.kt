package com.chessanalyzer.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chessanalyzer.ui.screens.gamedetail.GameDetailScreen
import com.chessanalyzer.ui.screens.games.GamesScreen
import com.chessanalyzer.ui.screens.review.ReviewScreen
import com.chessanalyzer.ui.screens.settings.SettingsScreen
import com.chessanalyzer.ui.screens.stats.StatsScreen

sealed class Screen(val route: String, val title: String) {
    object Games : Screen("games", "Games")
    object Stats : Screen("stats", "Stats")
    object Settings : Screen("settings", "Settings")
    object GameDetail : Screen("game/{gameId}", "Game") {
        fun createRoute(gameId: String) = "game/$gameId"
    }
    object GamesFiltered : Screen("games/opening/{opening}", "Games") {
        fun createRoute(opening: String) = "games/opening/${Uri.encode(opening)}"
    }
    object Review : Screen("review/{gameId}", "Review") {
        fun createRoute(gameId: String) = "review/$gameId"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessAnalyzerNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomBarScreens = listOf(Screen.Games, Screen.Stats, Screen.Settings)
    val showBottomBar = currentDestination?.hierarchy?.any { dest ->
        bottomBarScreens.any { it.route == dest.route }
    } == true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.List, contentDescription = "Games") },
                        label = { Text("Games") },
                        selected = currentDestination?.route == Screen.Games.route,
                        onClick = {
                            navController.navigate(Screen.Games.route) {
                                popUpTo(Screen.Games.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.BarChart, contentDescription = "Stats") },
                        label = { Text("Stats") },
                        selected = currentDestination?.route == Screen.Stats.route,
                        onClick = {
                            navController.navigate(Screen.Stats.route) {
                                popUpTo(Screen.Games.route)
                                launchSingleTop = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentDestination?.route == Screen.Settings.route,
                        onClick = {
                            navController.navigate(Screen.Settings.route) {
                                popUpTo(Screen.Games.route)
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Games.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Games.route) {
                GamesScreen(
                    onGameClick = { gameId ->
                        navController.navigate(Screen.GameDetail.createRoute(gameId))
                    }
                )
            }

            composable(Screen.Stats.route) {
                StatsScreen(
                    onOpeningClick = { opening ->
                        navController.navigate(Screen.GamesFiltered.createRoute(opening))
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen()
            }

            composable(
                route = Screen.GamesFiltered.route,
                arguments = listOf(navArgument("opening") { type = NavType.StringType })
            ) { backStackEntry ->
                val opening = backStackEntry.arguments?.getString("opening") ?: return@composable
                GamesScreen(
                    onGameClick = { gameId ->
                        navController.navigate(Screen.GameDetail.createRoute(gameId))
                    },
                    initialOpeningFilter = opening,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.GameDetail.route,
                arguments = listOf(navArgument("gameId") { type = NavType.StringType })
            ) { backStackEntry ->
                val gameId = backStackEntry.arguments?.getString("gameId") ?: return@composable
                GameDetailScreen(
                    gameId = gameId,
                    onBackClick = { navController.popBackStack() },
                    onReviewClick = { id ->
                        navController.navigate(Screen.Review.createRoute(id))
                    }
                )
            }

            composable(
                route = Screen.Review.route,
                arguments = listOf(navArgument("gameId") { type = NavType.StringType })
            ) { backStackEntry ->
                val gameId = backStackEntry.arguments?.getString("gameId") ?: return@composable
                ReviewScreen(
                    gameId = gameId,
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
