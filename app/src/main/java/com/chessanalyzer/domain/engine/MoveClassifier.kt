package com.chessanalyzer.domain.engine

import com.chessanalyzer.domain.model.MoveClassification
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies chess moves using win-chance (sigmoid) thresholds,
 * mirroring the pawn-appetit / Lichess annotation algorithm from score.ts → getAnnotation().
 *
 * Key design:
 * - All evals are normalised to the MOVER's perspective and capped at ±CP_CEILING (1000 cp)
 *   before any win-chance comparison, so mate scores saturate instead of dominating.
 * - Negative annotation thresholds are win-chance based (like pawn-appetit), with CP-based
 *   fallbacks that are also position-aware (e.g. 200 cp loss only matters if you were already ahead).
 * - Hopeless positions (≤ −900 cp with no real escape) are not penalised.
 * - Sacrifice detection drives BRILLIANT and contributes to EXCELLENT.
 *
 * Annotation → MoveClassification mapping (matches pawn-appetit):
 *   !!   → BRILLIANT
 *   !    → GREAT
 *   Best → BEST
 *   !?   → EXCELLENT  (interesting / close-to-best)
 *   ""   → GOOD       (no annotation)
 *   ?!   → INACCURACY
 *   ?    → MISTAKE
 *   ??   → BLUNDER
 *   —    → ONLY_MOVE  (Android-only; best dramatically better than 2nd-best)
 *   —    → BOOK       (first 10 moves per side)
 */
@Singleton
class MoveClassifier @Inject constructor() {

    companion object {
        internal const val BOOK_MOVE_THRESHOLD = 10  // moves per side → 20 half-moves

        /** Mate scores from effectiveCp can reach ±9900; cap here before win-chance math. */
        private const val CP_CEILING = 1000

        // "Hopeless" — player's perspective, already capped to ±1000
        private const val HOPELESS_CP = -900
        private const val HOPELESS_MARGIN = 50

        // A clearly better alternative exists if the best move is this many capped-cp better
        private const val BETTER_ALT_CP_GAP = 100

        // Win-chance thresholds for NEGATIVE annotations (mirrors pawn-appetit exactly)
        private const val BLUNDER_WIN_CHANCE   = 20.0
        private const val MISTAKE_WIN_CHANCE   = 10.0
        private const val INACCURACY_WIN_CHANCE =  5.0

        // CP-based FALLBACK thresholds (only fire when the position was already reasonable)
        private const val BLUNDER_CP_FALLBACK    = 400   // prevCP > 0
        private const val MISTAKE_CP_FALLBACK    = 200   // prevCP > 100
        private const val INACCURACY_CP_FALLBACK = 100   // prevCP >= 0

        // GREAT move thresholds
        private const val GREAT_WIN_CHANCE_GAP =  10.0  // best vs 2nd-best win-chance gap
        private const val GREAT_CP_GAP         = 150    // best vs 2nd-best cp gap
        private const val GREAT_IMPROVEMENT    =   5.0  // win-chance improvement over prev position

        // BRILLIANT move thresholds
        private const val BRILLIANT_WIN_CHANCE_GAP         = 10.0  // best vs 2nd-best
        private const val BRILLIANT_CP_GAP                 = 300   // best vs 2nd-best cp gap
        private const val BRILLIANT_IMPROVEMENT_WIN_CHANCE = 15.0  // vs previous position

        // ONLY_MOVE: best move > 2nd best by this win-chance gap (Android-only annotation)
        private const val ONLY_MOVE_WIN_CHANCE_GAP = 20.0

        // EXCELLENT / interesting: non-best close to best (pawn-appetit's !?)
        private const val EXCELLENT_MAX_GAP         = 5.0   // bestWinChance – playedWinChance
        private const val EXCELLENT_MIN_WIN_CHANCE  = 45.0  // played move still keeps game alive
        private const val EXCELLENT_SAC_MIN_CP      = -200  // sacrifice still playable

        // ──────────────────────────────────────────────────────────────────────────

        /**
         * Normalise a raw White-perspective cp to the mover's perspective and cap to ±1000.
         * Raw values from effectiveCp can be ±9900 for mate (Stockfish encoding).
         */
        fun normalizeAndCap(rawCp: Int, isWhite: Boolean): Int {
            val playerCp = if (isWhite) rawCp else -rawCp
            return playerCp.coerceIn(-CP_CEILING, CP_CEILING)
        }

        /** True when the raw cp indicates a WINNING mate for the mover (> 9000 player-cp). */
        private fun isWinningMateRaw(rawCp: Int, isWhite: Boolean): Boolean =
            (if (isWhite) rawCp else -rawCp) > 9000

        /** True when the raw cp indicates a LOSING mate for the mover (< −9000 player-cp). */
        private fun isLosingMateRaw(rawCp: Int, isWhite: Boolean): Boolean =
            (if (isWhite) rawCp else -rawCp) < -9000

        /**
         * True if a clearly better alternative existed.
         * Mirrors pawn-appetit's hasClearlyBetterAlternative():
         *  - Best delivers mate but played doesn't              → true
         *  - Played allows losing mate but best doesn't         → true
         *  - Played position hopeless AND best also hopeless    → false
         *  - bestCP > playedCP + BETTER_ALT_CP_GAP             → true
         */
        fun hasBetterAlternative(
            bestMoveEvalRaw: Int,
            playedEvalRaw: Int,
            isWhite: Boolean
        ): Boolean {
            if (isLosingMateRaw(playedEvalRaw, isWhite) && !isLosingMateRaw(bestMoveEvalRaw, isWhite)) return true
            if (isWinningMateRaw(bestMoveEvalRaw, isWhite) && !isWinningMateRaw(playedEvalRaw, isWhite)) return true

            val playedCapped = normalizeAndCap(playedEvalRaw, isWhite)
            val bestCapped   = normalizeAndCap(bestMoveEvalRaw, isWhite)

            // Both hopeless — no meaningful escape
            if (playedCapped <= HOPELESS_CP && bestCapped <= HOPELESS_CP + HOPELESS_MARGIN) return false

            return bestCapped > playedCapped + BETTER_ALT_CP_GAP
        }

        /**
         * Backward-compatible raw centipawn loss.
         * Still stored in the DB / used for display; not used for classification decisions.
         */
        fun computeEvalLoss(bestMoveEval: Int, playedMoveEval: Int, isWhite: Boolean): Int {
            val bestForPlayer   = if (isWhite) bestMoveEval   else -bestMoveEval
            val playedForPlayer = if (isWhite) playedMoveEval else -playedMoveEval
            return maxOf(0, bestForPlayer - playedForPlayer)
        }
    }

    data class ClassifyInput(
        val evalBefore: Int,           // raw cp before move (White's perspective)
        val evalAfter: Int,            // raw cp after actual move (White's perspective)
        val bestMoveEval: Int,         // raw cp after best move (White's perspective)
        val secondBestMoveEval: Int?,  // raw cp after 2nd-best move; null if only 1 PV line
        val isWhite: Boolean,
        val isBestMove: Boolean,
        val moveIndex: Int,            // 1-based half-move index in game
        val materialBefore: Int,       // total material before move (sacrifice detection)
        val materialAfter: Int         // total material after move
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Entry point
    // ═══════════════════════════════════════════════════════════════════════════

    fun classify(input: ClassifyInput): MoveClassification {

        // ── 1. Book ──────────────────────────────────────────────────────────
        if (input.moveIndex <= BOOK_MOVE_THRESHOLD * 2) return MoveClassification.BOOK

        // ── Normalised, capped cp from the mover's perspective ───────────────
        val prevCP = normalizeAndCap(input.evalBefore,    input.isWhite)
        val nextCP = normalizeAndCap(input.evalAfter,     input.isWhite)
        val bestCP = normalizeAndCap(input.bestMoveEval,  input.isWhite)
        val secondCP = input.secondBestMoveEval?.let { normalizeAndCap(it, input.isWhite) }

        val winChancePrev   = GameMetrics.getWinChance(prevCP)
        val winChanceNext   = GameMetrics.getWinChance(nextCP)
        val winChanceBest   = GameMetrics.getWinChance(bestCP)
        val winChanceSecond = secondCP?.let { GameMetrics.getWinChance(it) }

        // Loss in win-chance percentage (positive = player lost winning chances)
        val winChanceDiff = winChancePrev - winChanceNext

        // ── 2. "No real escape" guard ─────────────────────────────────────────
        // If the position was already hopeless AND all engine alternatives are also hopeless,
        // do not assign negative annotations (mirrors pawn-appetit noRealEscape check).
        val wasHopeless = prevCP <= HOPELESS_CP || isLosingMateRaw(input.evalBefore, input.isWhite)
        val allAltsHopeless = bestCP <= HOPELESS_CP + HOPELESS_MARGIN
                && (secondCP == null || secondCP <= HOPELESS_CP + HOPELESS_MARGIN)
        val noRealEscape = wasHopeless && allAltsHopeless

        // ── 3. Better-alternative and sacrifice flags ─────────────────────────
        val isSacrifice  = input.materialAfter < input.materialBefore
        val hasBetterAlt = !noRealEscape && hasBetterAlternative(
            bestMoveEvalRaw  = input.bestMoveEval,
            playedEvalRaw    = input.evalAfter,
            isWhite          = input.isWhite
        )

        // ── 4. NEGATIVE ANNOTATIONS (??, ?, ?!) ──────────────────────────────
        if (!noRealEscape && hasBetterAlt) {
            val prevWasMate    = isWinningMateRaw(input.evalBefore, input.isWhite)
            val prevWasDecisive = prevCP >= 500
            val nextClearlyLosing = nextCP < -300 || isLosingMateRaw(input.evalAfter, input.isWhite)

            // Threw away a decisive/mate advantage into a clearly losing position
            if ((prevWasMate || prevWasDecisive) && nextClearlyLosing) {
                return MoveClassification.BLUNDER
            }

            if (winChanceDiff > BLUNDER_WIN_CHANCE
                || (prevCP - nextCP > BLUNDER_CP_FALLBACK && prevCP > 0)) {
                return MoveClassification.BLUNDER
            }

            if (winChanceDiff > MISTAKE_WIN_CHANCE
                || (prevCP - nextCP > MISTAKE_CP_FALLBACK && prevCP > 100)) {
                return MoveClassification.MISTAKE
            }

            if (winChanceDiff > INACCURACY_WIN_CHANCE
                || (prevCP - nextCP > INACCURACY_CP_FALLBACK && prevCP >= 0)) {
                return MoveClassification.INACCURACY
            }
        }

        // ── 5. ONLY MOVE ──────────────────────────────────────────────────────
        // Best move is dramatically better than 2nd-best (win-chance gap).
        val onlyMoveGap = if (winChanceSecond != null) winChanceBest - winChanceSecond else 0.0
        val isOnlyMove  = onlyMoveGap >= ONLY_MOVE_WIN_CHANCE_GAP

        // ── 6. POSITIVE ANNOTATIONS for the best move ─────────────────────────
        if (input.isBestMove) {

            // BRILLIANT: sacrifice + best move matching pawn-appetit criteria
            if (isSacrifice && checkBrilliant(input, nextCP, bestCP, secondCP,
                    winChanceBest, winChanceSecond, prevCP)) {
                return MoveClassification.BRILLIANT
            }

            // GREAT: best move far better than alternatives, or significantly improves position
            if (checkGreat(bestCP, secondCP, winChanceBest, winChanceSecond, prevCP)) {
                return MoveClassification.GREAT
            }

            // ONLY MOVE (best move AND only real option)
            if (isOnlyMove) return MoveClassification.ONLY_MOVE

            // BEST: it's the engine's top choice, nothing flashier applies
            return MoveClassification.BEST
        }

        // ── 7. Non-best move positive annotations ─────────────────────────────

        // ONLY MOVE even when the player didn't play it (marks the situation)
        if (isOnlyMove) return MoveClassification.ONLY_MOVE

        // EXCELLENT (!?) — non-best but very close to best (pawn-appetit's "!?")
        //   • bestWinChance − playedWinChance ≤ 5%  AND  played still ≥ 45%  AND nextCP > −100
        //   • OR: playable sacrifice (nextCP > −200)
        val bestVsPlayedGap = winChanceBest - winChanceNext
        if (bestVsPlayedGap <= EXCELLENT_MAX_GAP
            && winChanceNext > EXCELLENT_MIN_WIN_CHANCE
            && nextCP > -100) {
            return MoveClassification.EXCELLENT
        }
        if (isSacrifice && nextCP > EXCELLENT_SAC_MIN_CP) {
            return MoveClassification.EXCELLENT
        }

        // GOOD: everything else that didn't qualify as a negative annotation
        return MoveClassification.GOOD
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Mirrors the pawn-appetit BRILLIANT (!!) conditions for a sacrifice that is the best move.
     * Called only when isSacrifice && isBestMove.
     */
    private fun checkBrilliant(
        input: ClassifyInput,
        nextCP: Int,
        bestCP: Int,
        secondCP: Int?,
        winChanceBest: Double,
        winChanceSecond: Double?,
        prevCP: Int
    ): Boolean {
        // Delivers mate or reaches decisive advantage
        if (isWinningMateRaw(input.evalAfter, input.isWhite) || nextCP >= 500) return true

        if (secondCP != null && winChanceSecond != null) {
            // Best much better than 2nd-best by win-chance or raw cp
            if (winChanceBest - winChanceSecond > BRILLIANT_WIN_CHANCE_GAP) return true
            if (bestCP - secondCP > BRILLIANT_CP_GAP)                       return true

            // Best gives mate, 2nd-best doesn't
            val bestIsMate   = isWinningMateRaw(input.bestMoveEval,          input.isWhite)
            val secondIsMate = isWinningMateRaw(input.secondBestMoveEval!!, input.isWhite)
            if (bestIsMate && !secondIsMate)  return true
            if (bestIsMate && secondIsMate) {
                // Delivers mate faster
                val bm = if (input.isWhite) input.bestMoveEval         else -input.bestMoveEval
                val sm = if (input.isWhite) input.secondBestMoveEval   else -input.secondBestMoveEval
                if (bm > sm) return true
            }
            if (bestCP >= 500 && secondCP < 500) return true
        }

        // Significantly improves the position beyond what it was before the move
        val improvementWinChance = winChanceBest - GameMetrics.getWinChance(prevCP)
        if (improvementWinChance > BRILLIANT_IMPROVEMENT_WIN_CHANCE) return true
        if (prevCP <= 0 && bestCP >= 500)                             return true

        return false
    }

    /**
     * Mirrors the pawn-appetit GREAT (!) conditions.
     * Called only when isBestMove.
     */
    private fun checkGreat(
        bestCP: Int,
        secondCP: Int?,
        winChanceBest: Double,
        winChanceSecond: Double?,
        prevCP: Int
    ): Boolean {
        if (secondCP != null && winChanceSecond != null) {
            if (winChanceBest - winChanceSecond > GREAT_WIN_CHANCE_GAP) return true
            if (bestCP - secondCP > GREAT_CP_GAP)                       return true
        }
        // Move significantly improves position
        val improvementWinChance = winChanceBest - GameMetrics.getWinChance(prevCP)
        if (improvementWinChance > GREAT_IMPROVEMENT)   return true
        if (prevCP < -100 && bestCP >= 0)               return true
        // Move gives mate or decisive advantage
        if (bestCP >= 500)                              return true
        return false
    }
}

