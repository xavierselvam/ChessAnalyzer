package com.chessanalyzer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.exp

/**
 * Win / Draw / Loss bar computed from centipawns using Lichess-style WDL model.
 *
 * White segment (wins for white) | Grey segment (draws) | Dark segment (wins for black)
 *
 * Formula:
 *   winChance(cp) = 1 / (1 + exp(-0.004 * cp))      (White's win probability)
 *   drawChance    = max(0,  0.45 - 0.6 * |winChance - 0.5|)
 *   whiteWin      = winChance - drawChance / 2
 *   blackWin      = 1 - winChance - drawChance / 2
 *
 * At equal position: ~28% white, ~44% draw, ~28% black.
 * At +300cp / -300cp the winning side exceeds 60%.
 */
@Composable
fun WdlBar(
    centipawns: Int,
    isMate: Boolean = false,
    mateIn: Int? = null,
    modifier: Modifier = Modifier
) {
    val (targetWhite, targetDraw, targetBlack) = remember(centipawns, isMate, mateIn) {
        if (isMate && mateIn != null) {
            if (mateIn > 0) Triple(1f, 0f, 0f) else Triple(0f, 0f, 1f)
        } else {
            val w = (1.0 / (1.0 + exp(-0.004 * centipawns))).toFloat()
            val d = maxOf(0f, 0.45f - 0.6f * abs(w - 0.5f))
            val whiteW = w - d / 2f
            val blackW = 1f - w - d / 2f
            Triple(whiteW.coerceIn(0f, 1f), d.coerceIn(0f, 1f), blackW.coerceIn(0f, 1f))
        }
    }

    val whiteW by animateFloatAsState(targetValue = targetWhite, animationSpec = tween(250), label = "wdlWhite")
    val drawW  by animateFloatAsState(targetValue = targetDraw,  animationSpec = tween(250), label = "wdlDraw")
    val blackW by animateFloatAsState(targetValue = targetBlack, animationSpec = tween(250), label = "wdlBlack")

    // Build percentage labels
    val wPct = (targetWhite * 100).toInt()
    val dPct = (targetDraw  * 100).toInt()
    val bPct = 100 - wPct - dPct

    Row(
        modifier = modifier
            .height(18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
    ) {
        // White wins segment
        if (whiteW > 0.01f) {
            Box(
                modifier = Modifier
                    .weight(whiteW)
                    .fillMaxHeight()
                    .background(Color(0xFFF0EDE5)),
                contentAlignment = Alignment.Center
            ) {
                if (wPct >= 15) {
                    Text(
                        text = "$wPct%",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF333333)
                    )
                }
            }
        }
        // Draw segment
        if (drawW > 0.01f) {
            Box(
                modifier = Modifier
                    .weight(drawW)
                    .fillMaxHeight()
                    .background(Color(0xFF9E9E9E)),
                contentAlignment = Alignment.Center
            ) {
                if (dPct >= 15) {
                    Text(
                        text = "$dPct%",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
        // Black wins segment
        if (blackW > 0.01f) {
            Box(
                modifier = Modifier
                    .weight(blackW)
                    .fillMaxHeight()
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                if (bPct >= 15) {
                    Text(
                        text = "$bPct%",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
    }
}
