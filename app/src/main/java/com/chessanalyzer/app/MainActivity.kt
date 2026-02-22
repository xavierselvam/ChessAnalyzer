package com.chessanalyzer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.chessanalyzer.data.local.preferences.UserPreferences
import com.chessanalyzer.ui.navigation.ChessAnalyzerNavHost
import com.chessanalyzer.ui.theme.ChessAnalyzerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read the saved dark-mode pref synchronously so the very first frame
        // already uses the correct scheme — avoids a light-mode flash on rotation.
        val initialDarkMode = runBlocking { userPreferences.darkMode.first() }
        setContent {
            val darkModePref by userPreferences.darkMode.collectAsState(initial = initialDarkMode)
            val darkTheme = when (darkModePref) {
                "dark"  -> true
                "light" -> false
                else    -> isSystemInDarkTheme()
            }
            ChessAnalyzerTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChessAnalyzerNavHost()
                }
            }
        }
    }
}
