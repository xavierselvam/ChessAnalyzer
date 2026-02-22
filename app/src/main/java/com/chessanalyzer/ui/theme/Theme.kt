package com.chessanalyzer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Chess-inspired color palette
val ChessDarkGreen = Color(0xFF1B5E20)
val ChessGreen = Color(0xFF2E7D32)
val ChessLightGreen = Color(0xFF4CAF50)
val ChessGold = Color(0xFFFFC107)
val ChessCream = Color(0xFFFFF8E1)

val BrilliantColor = Color(0xFF06B6D4)   // cyan  — matches pawn-appetit !!
val GreatColor = Color(0xFF3B82F6)       // blue  — matches pawn-appetit !
val BestColor = Color(0xFF22C55E)        // green — matches pawn-appetit Best
val ExcellentColor = Color(0xFF4ADE80)   // light-green
val OnlyMoveColor = Color(0xFF818CF8)    // indigo
val GoodColor = Color(0xFF97AF8B)        // muted green
val BookColor = Color(0xFFA88B65)        // brown
val InaccuracyColor = Color(0xFFFACC15)  // yellow — matches pawn-appetit ?!
val MistakeColor = Color(0xFFFB923C)     // orange — matches pawn-appetit ?
val BlunderColor = Color(0xFFEF4444)     // red  — matches pawn-appetit ??

val LightBoardLight = Color(0xFFF0D9B5)
val LightBoardDark = Color(0xFFB58863)
val DarkBoardLight = Color(0xFF9E9E9E)
val DarkBoardDark = Color(0xFF616161)

private val DarkColorScheme = darkColorScheme(
    primary = ChessLightGreen,
    onPrimary = Color.White,
    primaryContainer = ChessDarkGreen,
    onPrimaryContainer = Color.White,
    secondary = ChessGold,
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFCACACA),
    error = BlunderColor,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = ChessGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = ChessDarkGreen,
    secondary = ChessGold,
    onSecondary = Color.Black,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1C1C),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF555555),
    error = BlunderColor,
    onError = Color.White
)

@Composable
fun ChessAnalyzerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            bodyLarge = TextStyle(fontSize = 16.sp),
            bodyMedium = TextStyle(fontSize = 14.sp),
            bodySmall = TextStyle(fontSize = 12.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
        ),
        content = content
    )
}
