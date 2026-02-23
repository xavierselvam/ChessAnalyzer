package com.chessanalyzer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp

/**
 * Evaluation bar showing engine evaluation from White's perspective.
 *
 * Uses a sigmoid win-chance conversion identical to pawn-appetit / Lichess:
 *   winChance(cp) = 1 / (1 + exp(-0.004 * cp))
 * so ±500cp ≈ 85%/15% and ±100cp ≈ 60%/40%, matching real engine assessments.
 *
 * The bar animates smoothly as the position changes (200ms ease-in-out).
 *
 * @param vertical  When true renders as a vertical bar (black top, white bottom),
 *                  Lichess-style, suitable for placement alongside the chessboard.
 *                  The caller controls the exact size via [modifier].
 */
@Composable
fun EvalBar(
    centipawns: Int,
    isMate: Boolean = false,
    mateIn: Int? = null,
    vertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    val evalText = when {
        isMate && mateIn != null -> if (mateIn > 0) "M$mateIn" else "M${-mateIn}"
        else -> {
            val pawns = centipawns / 100f
            if (pawns >= 0) "+%.1f".format(pawns) else "%.1f".format(pawns)
        }
    }

    // Sigmoid win-chance formula (matches pawn-appetit / Lichess)
    val targetFraction = when {
        isMate && mateIn != null -> if (mateIn > 0) 1f else 0f
        else -> (1.0 / (1.0 + exp(-0.004 * centipawns))).toFloat()
    }

    val whiteFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 200),
        label = "evalBarFraction"
    )

    if (vertical) {
        // ── Vertical bar: black on top, white on bottom ───────────────────
        Column(
            modifier = modifier.clip(RoundedCornerShape(3.dp))
        ) {
            // Black segment (top)
            if (1f - whiteFraction > 0.001f) {
                Box(
                    modifier = Modifier
                        .weight(1f - whiteFraction)
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (whiteFraction < 0.35f) {
                        Text(
                            text = evalText,
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
            }
            // White segment (bottom)
            if (whiteFraction > 0.001f) {
                Box(
                    modifier = Modifier
                        .weight(whiteFraction)
                        .fillMaxWidth()
                        .background(Color(0xFFF0F0F0)),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (whiteFraction >= 0.35f) {
                        Text(
                            text = evalText,
                            color = Color(0xFF1A1A1A),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    } else {
        // ── Horizontal bar (original behaviour) ──────────────────────────
        Row(
            modifier = modifier
                .height(22.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (1f - whiteFraction > 0.001f) {
                Box(
                    modifier = Modifier
                        .weight(1f - whiteFraction)
                        .fillMaxHeight()
                        .background(Color(0xFF1A1A1A)),
                    contentAlignment = Alignment.Center
                ) {
                    if (whiteFraction < 0.38f) {
                        Text(text = evalText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (whiteFraction > 0.001f) {
                Box(
                    modifier = Modifier
                        .weight(whiteFraction)
                        .fillMaxHeight()
                        .background(Color(0xFFF0F0F0)),
                    contentAlignment = Alignment.Center
                ) {
                    if (whiteFraction >= 0.38f) {
                        Text(text = evalText, color = Color(0xFF1A1A1A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

