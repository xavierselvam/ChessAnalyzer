package com.chessanalyzer.domain.engine

import kotlin.math.exp

/**
 * Accuracy and win probability calculations matching the pawn-appetit / Lichess formula.
 *
 * Win probability:  50 + 50 * (2 / (1 + exp(-0.00368208 * cp)) - 1)
 *                   ≡  100 / (1 + exp(-0.00368208 * cp))
 *                   Coefficient 0.00368208 is the Lichess standard.
 *
 * Per-move accuracy (Lichess formula):
 *   clamp(103.1668 * exp(-0.04354 * winChanceDiff) - 3.1669 + 1, 0, 100)
 *   where winChanceDiff = getWinChance(prevCP) - getWinChance(nextCP)  [both player-perspective]
 *   Both prevCP and nextCP are capped to ±1000 cp (mate → saturated to ceiling).
 *
 * Overall accuracy: harmonic mean of per-move accuracies (same as pawn-appetit).
 *
 * Performance rating: player_rating + (accuracy - 50) × 20
 */
object GameMetrics {

    /** Centipawn ceiling — mate scores are saturated to this value before the formula. */
    private const val CP_CEILING = 1000

    /**
     * Win chance as a percentage [0, 100] for a given evaluation from the player's perspective.
     * Uses the Lichess coefficient 0.00368208.
     *
     * Examples:
     *   0 cp  → 50.0%
     *   +100  → 59.1%
     *   +500  → 86.3%
     *   +1000 → 97.3%
     */
    fun getWinChance(cp: Int): Double {
        val capped = cp.coerceIn(-CP_CEILING, CP_CEILING).toDouble()
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * capped)) - 1.0)
    }

    /**
     * Per-move accuracy using the Lichess / pawn-appetit formula.
     * Both arguments are from the mover's perspective and are capped at ±1000 cp internally.
     *
     * @param prevCpForPlayer eval before the move (player's perspective, White-perspective if White)
     * @param nextCpForPlayer eval after the move  (player's perspective)
     *
     * Examples (equal position, White):
     *   prevCp = 0,   nextCp = 0   → 100%
     *   prevCp = 0,   nextCp = -100 → ~87%
     *   prevCp = 0,   nextCp = -500 → ~19%
     *   prevCp = 100, nextCp = -400 → ~0%  (blunder from slight advantage)
     */
    fun moveAccuracy(prevCpForPlayer: Int, nextCpForPlayer: Int): Float {
        val winChanceDiff = getWinChance(prevCpForPlayer) - getWinChance(nextCpForPlayer)
        val acc = 103.1668 * exp(-0.04354 * winChanceDiff) - 3.1669 + 1.0
        return acc.coerceIn(0.0, 100.0).toFloat()
    }

    /**
     * Overall accuracy as the harmonic mean of per-move accuracies.
     * Matches pawn-appetit's `harmonicMean(accuracies)`.
     * Moves with 0% accuracy are excluded (they would dominate the harmonic mean unfairly).
     */
    fun overallAccuracy(accuracies: List<Float>): Float {
        val valid = accuracies.filter { it > 0f }
        if (valid.isEmpty()) return 0f
        val sumOfReciprocals = valid.sumOf { 1.0 / it.toDouble() }
        return (valid.size.toDouble() / sumOfReciprocals).toFloat()
    }

    /**
     * Win probability as a fraction [0, 1] using the Lichess coefficient.
     *
     * Examples:
     *   0 cp  → 0.500
     *   +100  → 0.591
     *   +500  → 0.863
     *   +1000 → 0.973
     */
    fun winProbability(evalCp: Int): Float {
        return (getWinChance(evalCp) / 100.0).toFloat()
    }

    /** Win probability as percentage (0-100). */
    fun winProbabilityPercent(evalCp: Int): Float {
        return getWinChance(evalCp).toFloat()
    }

    /**
     * Performance rating calculation.
     * performance = player_rating + (accuracy - 50) × 20
     * Capped to [100, 3500].
     */
    fun performanceRating(playerRating: Int, accuracy: Float): Int {
        val raw = playerRating + ((accuracy - 50f) * 20f).toInt()
        return raw.coerceIn(100, 3500)
    }

    /**
     * Key moment detection.
     * Key moment: abs(eval_after - eval_before) ≥ 200 cp
     */
    fun isKeyMoment(evalBefore: Int, evalAfter: Int): Boolean {
        return kotlin.math.abs(evalAfter - evalBefore) >= 200
    }

    /**
     * Turning point detection.
     * sign(eval_before) != sign(eval_after)
     */
    fun isTurningPoint(evalBefore: Int, evalAfter: Int): Boolean {
        if (evalBefore == 0 || evalAfter == 0) return false
        return (evalBefore > 0) != (evalAfter > 0)
    }

    /**
     * Blunder moment: eval_loss ≥ 300 cp
     */
    fun isBlunderMoment(evalLoss: Int): Boolean = evalLoss >= 300
}
