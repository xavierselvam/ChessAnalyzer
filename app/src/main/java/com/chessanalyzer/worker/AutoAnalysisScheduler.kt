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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules background analysis in rolling batches of [BATCH_SIZE] games via
 * [AnalysisWorker]. Works for all engine modes (local / cloud / hybrid) — the
 * per-move cloud-vs-Stockfish decision is made inside [AnalysisWorker] /
 * [PositionEvaluator] based on the user's engine mode setting.
 */
@Singleton
class AutoAnalysisScheduler @Inject constructor(
    private val gameRepository: GameRepository,
    private val workManager: WorkManager,
    private val userPreferences: UserPreferences
) {
    companion object {
        private const val TAG = "AutoAnalysisScheduler"
        const val BATCH_SIZE = 1
    }

    /**
     * Kick off (or continue) the next batch of [BATCH_SIZE] games.
     * Safe to call multiple times — uses APPEND_OR_REPLACE so already-running
     * workers are not displaced.
     */
    suspend fun scheduleNextBatch() {
        if (!userPreferences.autoAnalyze.first()) {
            Log.d(TAG, "Auto-analyze is OFF — skipping schedule")
            return
        }
        scheduleBatch()
    }

    /** Convenience alias used at app-level trigger points (sync complete, toggle ON). */
    suspend fun scheduleAllPending() = scheduleNextBatch()

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun scheduleBatch() {
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
    }
}

