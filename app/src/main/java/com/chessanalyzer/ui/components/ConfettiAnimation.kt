package com.chessanalyzer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.isActive

private val CONFETTI_COLORS = listOf(
    Color(0xFFFF6B6B), Color(0xFF4ECDC4), Color(0xFFFFE66D),
    Color(0xFF1A535C), Color(0xFFFF9F1C), Color(0xFF2EC4B6),
    Color(0xFFE71D36), Color(0xFF8BC34A), Color(0xFF9C27B0),
    Color(0xFF00BCD4), Color(0xFFFFC107), Color(0xFFFF5722)
)

private data class Particle(
    val startX: Float,   // 0-1 fraction of screen width
    val vx: Float,       // horizontal velocity (fraction/s)
    val vy0: Float,      // initial upward velocity (fraction/s, negative = up)
    val gravity: Float,  // downward acceleration (fraction/s²)
    val rotation0: Float,
    val spin: Float,     // degrees/s
    val width: Float,    // px
    val height: Float,   // px
    val color: Color,
    val startDelay: Float  // 0..0.4 s
) {
    fun xAt(t: Float, screenW: Float) = startX * screenW + vx * t * screenW
    fun yAt(t: Float, screenH: Float) = -0.05f * screenH + vy0 * t * screenH + 0.5f * gravity * t * t * screenH
    fun alphaAt(t: Float): Float = when {
        t < 0f   -> 0f
        t < 0.7f -> 1f
        else    -> ((1f - t) / 0.3f).coerceIn(0f, 1f)
    }
}

/**
 * Full-screen confetti burst animation. Plays for ~2.5 seconds then stops.
 * Place this in a Box that covers whatever should be celebrated.
 */
@Composable
fun ConfettiAnimation(modifier: Modifier = Modifier) {
    val particles = remember {
        val rng = kotlin.random.Random
        List(70) {
            Particle(
                startX     = rng.nextFloat() * 0.6f + 0.2f,
                vx         = (rng.nextFloat() - 0.5f) * 0.6f,
                vy0        = -(rng.nextFloat() * 0.25f + 0.1f),
                gravity    = rng.nextFloat() * 0.5f + 1.2f,
                rotation0  = rng.nextFloat() * 360f,
                spin       = (rng.nextFloat() - 0.5f) * 540f,
                width      = rng.nextFloat() * 18f + 8f,
                height     = rng.nextFloat() * 24f + 10f,
                color      = CONFETTI_COLORS[rng.nextInt(CONFETTI_COLORS.size)],
                startDelay = rng.nextFloat() * 0.4f
            )
        }
    }

    var elapsedSecs by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = withFrameMillis { it }
        while (isActive && elapsedSecs < 2.8f) {
            withFrameMillis { now ->
                elapsedSecs += (now - last) / 1000f
                last = now
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        particles.forEach { p ->
            val t = (elapsedSecs - p.startDelay).coerceAtLeast(0f)
            if (t <= 0f) return@forEach
            val x = p.xAt(t, size.width)
            val y = p.yAt(t, size.height)
            val alpha = p.alphaAt(t)
            if (alpha <= 0f || y > size.height + 50f) return@forEach
            withTransform({
                translate(x, y)
                rotate(p.rotation0 + p.spin * t)
            }) {
                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(-p.width / 2f, -p.height / 2f),
                    size = Size(p.width, p.height)
                )
            }
        }
    }
}
