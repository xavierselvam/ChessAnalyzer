package com.chessanalyzer.worker

import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chessanalyzer.domain.usecase.AnalyzeGameUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
/**
 * WorkManager worker that runs full game analysis in the background.
 * Survives the app being minimised, navigated away, or killed (WorkManager reschedules
 * it if the process dies mid-run).
 *
 * Shows a persistent foreground notification with live progress so the user knows
 * analysis is ongoing even when they're not on the game detail screen.
 */
@HiltWorker
class AnalysisWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val analyzeGameUseCase: AnalyzeGameUseCase,
    private val autoAnalysisScheduler: AutoAnalysisScheduler
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "AnalysisWorker"
        const val KEY_GAME_ID = "game_id"
        const val KEY_GAME_INDEX = "game_index"      // 1-based position within the batch
        const val KEY_GAME_TOTAL = "game_total"      // batch size
        const val KEY_IS_LAST_IN_BATCH = "is_last"
        const val KEY_IS_USER_REQUESTED = "is_user_requested"
        const val KEY_PROGRESS = "progress"
        private const val NOTIFICATION_CHANNEL_ID = "chess_analysis_channel"
        private const val NOTIFICATION_ID = 2001

        /**
         * Auto-analysis queue (scheduler batch jobs).
         * Serial — all batch jobs run one at a time.
         */
        const val QUEUE_NAME = "analysis_auto_queue"

        /**
         * User-initiated analysis queue.
         * Separate from the auto queue so user requests are never blocked by the batch.
         * A Mutex in AnalyzeGameUseCase ensures Stockfish is still used serially.
         */
        const val USER_QUEUE_NAME = "analysis_user_queue"
    }

    override suspend fun doWork(): Result {
        val gameId = inputData.getString(KEY_GAME_ID)
            ?: return Result.failure(workDataOf("error" to "Missing game_id"))
        val gameIndex = inputData.getInt(KEY_GAME_INDEX, 0)
        val gameTotal = inputData.getInt(KEY_GAME_TOTAL, 0)
        val isLastInBatch = inputData.getBoolean(KEY_IS_LAST_IN_BATCH, false)
        val isUserRequested = inputData.getBoolean(KEY_IS_USER_REQUESTED, false)

        Log.i(TAG, "Starting analysis for game $gameId ($gameIndex/$gameTotal, last=$isLastInBatch)")
        trySetForeground(buildForegroundInfo(0f))

        return try {
            analyzeGameUseCase.invoke(gameId, isUserRequested).collect { progress ->
                setProgress(workDataOf(
                    KEY_PROGRESS to progress.percentage,
                    KEY_GAME_INDEX to gameIndex,
                    KEY_GAME_TOTAL to gameTotal
                ))
                trySetForeground(buildForegroundInfo(progress.percentage, gameIndex, gameTotal))
            }
            Log.i(TAG, "Analysis complete for game $gameId")
            // Last job in this batch: kick off the next batch
            if (isLastInBatch) autoAnalysisScheduler.scheduleNextBatch()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed for game $gameId: ${e.message}", e)
            Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
        }
    }

    /**
     * Attempts to promote this worker to a foreground service with a notification.
     * On Android 12+ this can fail with [ForegroundServiceStartNotAllowedException] if
     * the system decides the app is not sufficiently in the foreground at the time
     * WorkManager attempts to start [SystemForegroundService].  In that case we
     * silently continue — the work still executes, just without a visible notification.
     */
    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            val isFgsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            val isFgsTypeInvalid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                e is InvalidForegroundServiceTypeException
            if (isFgsBlocked || isFgsTypeInvalid) {
                Log.w(TAG, "Cannot promote to foreground service (${e.javaClass.simpleName}), continuing without notification")
            } else {
                throw e
            }
        }
    }

    private fun buildForegroundInfo(progress: Float, gameIndex: Int = 0, gameTotal: Int = 0): ForegroundInfo {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Game Analysis",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val pct = (progress * 100).toInt()
        val title = if (gameIndex > 0 && gameTotal > 0)
            "Analysing game $gameIndex of $gameTotal"
        else
            "Analysing game…"
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (pct == 0) "Starting…" else "$pct% complete")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
