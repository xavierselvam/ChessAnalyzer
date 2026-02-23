package com.chessanalyzer.domain.engine

import android.util.Log
import com.chessanalyzer.data.local.db.PositionEvalCacheDao
import com.chessanalyzer.data.local.db.PositionEvalCacheEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Evaluates chess positions using either the local Stockfish engine or
 * the Lichess cloud eval API, with two-layer FEN-based caching:
 *
 *   Layer 1 — in-memory [ConcurrentHashMap]: zero-latency hits within the same session.
 *   Layer 2 — Room DB [PositionEvalCacheDao]: persists across sessions and games.
 *             If game B's opening matches game A's opening, all shared positions are
 *             served from the DB and the engine is never invoked for those moves.
 *
 * Cloud mode: instant results from pre-computed Lichess DB (depth 40+).
 *   Falls back to cp=0 if a position is not in the cloud database.
 * Local mode: full Stockfish analysis at the configured depth.
 * Hybrid mode: cloud first; falls back to Stockfish if the position is absent from
 *   the Lichess DB (typically after move ~15-20). Best of both worlds — opening/middlegame
 *   positions are served instantly at depth 40+, rare/deep positions use the engine.
 */
@Singleton
class PositionEvaluator @Inject constructor(
    private val engine: StockfishEngine,
    private val cloudEngine: LichessCloudEngine,
    private val evalCacheDao: PositionEvalCacheDao
) {
    companion object {
        private const val TAG = "PositionEvaluator"
        private val NEUTRAL = StockfishEngine.EvalResult(centipawns = 0)
    }

    /** Layer-1 in-memory cache: cacheKey → result. Thread-safe. */
    private val memCache = ConcurrentHashMap<String, StockfishEngine.EvalResult>()

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /** Evaluate with MultiPV=3 (for before-move analysis: best move, second-best). */
    suspend fun evaluateMultiPV(fen: String, depth: Int, mode: String = "local"): StockfishEngine.EvalResult {
        val key = cacheKey(fen, depth, mode, mpv = 3)
        memCache[key]?.let { return it }

        // Layer-2: persistent DB cache
        evalCacheDao.get(key)?.let { entity ->
            Log.d(TAG, "DB cache hit (mpv3): $key")
            return entity.toEvalResult(mpv = 3).also { memCache[key] = it }
        }

        val result = computeMultiPV(fen, depth, mode)
        persist(key, result, mpv = 3)
        memCache[key] = result

        // Cross-populate the mpv1 cache key so evaluateSingle() on the same FEN is a
        // free cache hit — effectiveCp and bestMoveUci are identical regardless of mpv count.
        val mpv1Key = cacheKey(fen, depth, mode, mpv = 1)
        if (!memCache.containsKey(mpv1Key)) memCache[mpv1Key] = result

        return result
    }

    /** Evaluate with MultiPV=1 (for after-move analysis). */
    suspend fun evaluateSingle(fen: String, depth: Int, mode: String = "local"): StockfishEngine.EvalResult {
        val key = cacheKey(fen, depth, mode, mpv = 1)
        memCache[key]?.let { return it }

        // Layer-2: persistent DB cache
        evalCacheDao.get(key)?.let { entity ->
            Log.d(TAG, "DB cache hit (mpv1): $key")
            return entity.toEvalResult(mpv = 1).also { memCache[key] = it }
        }

        val result = computeSingle(fen, depth, mode)
        persist(key, result, mpv = 1)
        memCache[key] = result
        return result
    }

    /** Clear the in-memory cache (DB cache is permanent). */
    fun clearCache() = memCache.clear()

    val cacheSize: Int get() = memCache.size

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun cacheKey(fen: String, depth: Int, mode: String, mpv: Int): String =
        "$fen|${when (mode) { "cloud" -> "cloud"; "hybrid" -> "hybrid"; else -> "d$depth" }}|mpv$mpv"

    private suspend fun computeMultiPV(fen: String, depth: Int, mode: String): StockfishEngine.EvalResult =
        when (mode) {
            "cloud" -> {
                val t0 = System.currentTimeMillis()
                val r = cloudEngine.evaluate(fen, multiPv = 3)
                Log.d(TAG, "[cloud] mpv3 ${System.currentTimeMillis()-t0}ms cp=${r?.centipawns} fen=${fen.take(30)}")
                r ?: NEUTRAL.also { Log.w(TAG, "Cloud returned null (mpv3), using neutral.") }
            }
            "hybrid" -> {
                val t0 = System.currentTimeMillis()
                val cloudResult = cloudEngine.evaluate(fen, multiPv = 3)
                val cloudMs = System.currentTimeMillis() - t0
                if (cloudResult != null) {
                    Log.i(TAG, "[hybrid] ☁️  cloud hit  mpv3 ${cloudMs}ms  cp=${cloudResult.centipawns}  fen=${fen.take(30)}")
                    cloudResult
                } else {
                    Log.i(TAG, "[hybrid] ☁️  cloud miss ${cloudMs}ms → 🔧 Stockfish mpv3  fen=${fen.take(30)}")
                    val t1 = System.currentTimeMillis()
                    val r = engine.evaluate(fen, depth, multiPv = 3)
                    Log.i(TAG, "[hybrid] 🔧 Stockfish done mpv3 ${System.currentTimeMillis()-t1}ms  cp=${r.centipawns}")
                    r
                }
            }
            else -> {
                val t0 = System.currentTimeMillis()
                val r = engine.evaluate(fen, depth, multiPv = 3)
                Log.d(TAG, "[local] mpv3 ${System.currentTimeMillis()-t0}ms cp=${r.centipawns}")
                r
            }
        }

    private suspend fun computeSingle(fen: String, depth: Int, mode: String): StockfishEngine.EvalResult =
        when (mode) {
            "cloud" -> {
                val t0 = System.currentTimeMillis()
                val r = cloudEngine.evaluate(fen, multiPv = 1)
                Log.d(TAG, "[cloud] mpv1 ${System.currentTimeMillis()-t0}ms cp=${r?.centipawns} fen=${fen.take(30)}")
                r ?: NEUTRAL.also { Log.w(TAG, "Cloud returned null (mpv1), using neutral.") }
            }
            "hybrid" -> {
                val t0 = System.currentTimeMillis()
                val cloudResult = cloudEngine.evaluate(fen, multiPv = 1)
                val cloudMs = System.currentTimeMillis() - t0
                if (cloudResult != null) {
                    Log.i(TAG, "[hybrid] ☁️  cloud hit  mpv1 ${cloudMs}ms  cp=${cloudResult.centipawns}  fen=${fen.take(30)}")
                    cloudResult
                } else {
                    Log.i(TAG, "[hybrid] ☁️  cloud miss ${cloudMs}ms → 🔧 Stockfish mpv1  fen=${fen.take(30)}")
                    val t1 = System.currentTimeMillis()
                    val r = engine.evaluateSingle(fen, depth)
                    Log.i(TAG, "[hybrid] 🔧 Stockfish done mpv1 ${System.currentTimeMillis()-t1}ms  cp=${r.centipawns}")
                    r
                }
            }
            else -> {
                val t0 = System.currentTimeMillis()
                val r = engine.evaluateSingle(fen, depth)
                Log.d(TAG, "[local] mpv1 ${System.currentTimeMillis()-t0}ms cp=${r.centipawns}")
                r
            }
        }

    private suspend fun persist(key: String, result: StockfishEngine.EvalResult, mpv: Int) {
        // Compute secondCpWhite from pvLines[1] the same way AnalyzeGameUseCase does,
        // so the stored value can be placed directly into a reconstructed PvLine.centipawns.
        val secondCp: Int? = if (mpv == 3) {
            result.pvLines.getOrNull(1)?.let { pv ->
                if (pv.isMate && pv.mateIn != null) {
                    val sign = if (pv.mateIn > 0) 1 else -1
                    sign * (10000 - abs(pv.mateIn) * 100)
                } else pv.centipawns
            }
        } else null

        evalCacheDao.insert(
            PositionEvalCacheEntity(
                cacheKey     = key,
                cpWhite      = result.effectiveCp,
                bestMoveUci  = result.bestMoveUci,
                secondCpWhite = secondCp,
                isMate       = result.isMate,
                mateIn       = result.mateIn
            )
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Extension: reconstruct EvalResult from the cache entity
// ──────────────────────────────────────────────────────────────────

private fun PositionEvalCacheEntity.toEvalResult(mpv: Int): StockfishEngine.EvalResult {
    val pvLines = buildList {
        add(StockfishEngine.PvLine(multipv = 1, centipawns = cpWhite, isMate = isMate, mateIn = mateIn))
        if (mpv == 3 && secondCpWhite != null) {
            // isMate=false: the effective-cp value was already computed before storage.
            add(StockfishEngine.PvLine(multipv = 2, centipawns = secondCpWhite, isMate = false))
        }
    }
    return StockfishEngine.EvalResult(
        centipawns  = cpWhite,
        isMate      = isMate,
        mateIn      = mateIn,
        bestMoveUci = bestMoveUci,
        pvLines     = pvLines
    )
}
