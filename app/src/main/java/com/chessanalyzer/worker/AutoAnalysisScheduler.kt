package com.chessanalyzer.worker

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chessanalyzer.data.local.preferences.UserPreferences
import com.chessanalyzer.data.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules background analysis, routing between two strategies:
 *
 * **Non-hybrid** ("local" | "cloud"): rolling batches of [BATCH_SIZE] games via
 * [AnalysisWorker] — the original behaviour.
 *
 * **Hybrid**: a single [CloudAnalysisWorker] that covers ALL pending games in
 * parallel cloud calls, then auto-chains to [StockfishAnalysisWorker] for deep
 * evaluation of cloud-miss moves.
 *
 * On every app start [scheduleAllPending] also resumes any interrupted
 * [StockfishAnalysisWorker] if "cloud_done" games are waiting.
 */
@Singleton
class AutoAnalysisScheduler @Inject constructor(
    private val gameRepository: GameRepository,
    private val workManager: WorkManager,
    private val userPreferences: UserPreferences
) {
    companion object {
        private const val TAG = "AutoAnalysisScheduler"
        const val BATCH_SIZE = 5
    }

    /**
     * Kick off (or continue) analysis based on the current engine mode.
     * Safe to call multiple times — uses KEEP/APPEND policies so already-running
     * workers are not displaced.
     */
    suspend fun scheduleNextBatch() {
        if (!userPreferences.autoAnalyze.first()) {
            Log.d(TAG, "Auto-analyze is OFF — skipping schedule")
            return
        }

        val mode = userPreferences.engineMode.first()
        if (mode == "hybrid") {
            scheduleHybrid()
        } else {
            scheduleNonHybrid()
        }
    }

    /** Convenience alias used at app-level trigger points (sync complete, toggle ON). */
    suspend fun scheduleAllPending() {
        scheduleNextBatch()
        // Always try to resume an interrupted Stockfish phase, regardless of mode,
        // in case the app was killed while cloud_done games were waiting.
        resumeStockfishPhaseIfNeeded()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Hybrid mode: enqueue a single [CloudAnalysisWorker] to cover all pending games.
     * Uses KEEP policy — if one is already running, we don't start another.
     */
    private suspend fun scheduleHybrid() {
        val pending = gameRepository.getPendingGameIds()
        if (pending.isEmpty()) {
            Log.d(TAG, "[hybrid] No pending games to cloud-analyze")
            resumeStockfishPhaseIfNeeded()
            return
        }
        Log.i(TAG, "[hybrid] Scheduling CloudAnalysisWorker for ${pending.size} pending game(s)")
        val request = OneTimeWorkRequestBuilder<CloudAnalysisWorker>().build()
        workManager.enqueueUniqueWork(
            CloudAnalysisWorker.QUEUE_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Non-hybrid: rolling batch of [BATCH_SIZE] [AnalysisWorker] jobs.
     */
    private suspend fun scheduleNonHybrid() {
        val batch = gameRepository.getNextPendingGames(BATCH_SIZE)
        if (batch.isEmpty()) {
            Log.d(TAG, "No pending games — analysis complete")
            return
        }
        Log.i(TAG, "Scheduling batch of ${batch.size} game(s) for analysis")
        batch.forEachIndexed { index, game ->
            val isLast = index == batch.size - 1
            val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(workDataOf(
                    AnalysisWorker.KEY_GAME_ID to game.id,
                    AnalysisWorker.KEY_GAME_INDEX to (index + 1),
                    AnalysisWorker.KEY_GAME_TOTAL to batch.size,
                    AnalysisWorker.KEY_IS_LAST_IN_BATCH to isLast
                ))
                .addTag(game.id)
                .build()
            workManager
                .beginUniqueWork(AnalysisWorker.QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .enqueue()
        }
    }

    /**
     * If any games are stuck at "cloud_done" (e.g. after an app kill mid-phase),
     * enqueue a [StockfishAnalysisWorker] to resume them.
     */
    suspend fun resumeStockfishPhaseIfNeeded() {
        val waiting = gameRepository.getCloudDoneGameIds(1)
        if (waiting.isNotEmpty()) {
            Log.i(TAG, "Resuming Stockfish phase: ${waiting.size}+ cloud_done game(s) found")
            val request = OneTimeWorkRequestBuilder<StockfishAnalysisWorker>().build()
            workManager.enqueueUniqueWork(
                StockfishAnalysisWorker.QUEUE_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    /**
     * Returns a flow of a human-readable status string reflecting the current queue state.
     */
    fun observeStatus(): Flow<String?> =
        workManager.getWorkInfosForUniqueWorkFlow(AnalysisWorker.QUEUE_NAME)
            .map { infos ->
                val active = infos.filter {
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.BLOCKED
                }
                val running = active.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val enqueued = active.count { it.state == WorkInfo.State.ENQUEUED }
                when {
                    running != null -> {
                        val idx   = running.progress.getInt(AnalysisWorker.KEY_GAME_INDEX, 0)
                        val tot   = running.progress.getInt(AnalysisWorker.KEY_GAME_TOTAL, 0)
                        val pct   = (running.progress.getFloat(AnalysisWorker.KEY_PROGRESS, 0f) * 100).toInt()
                        val label = if (idx > 0 && tot > 0) "Game $idx of $tot" else "Game"
                        "Analyzing $label\u2026 ($pct%)"
                    }
                    enqueued > 0 -> "$enqueued game${if (enqueued == 1) "" else "s"} queued"
                    else -> null
                }
            }

    /**
     * Returns the set of game IDs that are RUNNING, ENQUEUED, or BLOCKED in the
     * auto-analysis queue. Each work request is tagged with the game ID.
     */
    fun observeAutoQueuedIds(): Flow<Set<String>> =
        workManager.getWorkInfosForUniqueWorkFlow(AnalysisWorker.QUEUE_NAME)
            .map { infos ->
                infos
                    .filter {
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                    }
                    .flatMap { it.tags }
                    .filter { it.length > 10 && !it.contains('.') }
                    .toSet()
            }
            .distinctUntilChanged()

    /**
     * Cancels all queued/running analysis jobs across all queues.
     */
    fun cancelAll() {
        Log.i(TAG, "Cancelling all auto-analysis jobs")
        workManager.cancelUniqueWork(AnalysisWorker.QUEUE_NAME)
        workManager.cancelUniqueWork(CloudAnalysisWorker.QUEUE_NAME)
        workManager.cancelUniqueWork(StockfishAnalysisWorker.QUEUE_NAME)
    }
}

