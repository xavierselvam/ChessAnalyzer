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
import androidx.work.*
import com.chessanalyzer.domain.usecase.StockfishPhaseUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Phase 2 of the two-phase hybrid analysis:
 * Processes a batch of "cloud_done" games with Stockfish to fill in missing
 * evaluations, then marks each game "done".
 *
 * Self-re-enqueues if [StockfishPhaseUseCase] reports that more cloud_done
 * games remain — allowing processing to continue in successive WorkManager jobs
 * rather than one very long-running worker.
 *
 * Also started directly at app launch (via [AutoAnalysisScheduler]) if any
 * cloud_done games are found — ensuring the phase resumes after an app kill.
 */
@HiltWorker
class StockfishAnalysisWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val stockfishPhaseUseCase: StockfishPhaseUseCase
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "StockfishAnalysisWorker"
        const val KEY_PROGRESS = "progress"
        const val QUEUE_NAME = "analysis_stockfish_phase"
        private const val NOTIFICATION_CHANNEL_ID = "chess_stockfish_analysis_channel"
        private const val NOTIFICATION_ID = 2003
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting Stockfish analysis phase")
        trySetForeground(buildForegroundInfo(0f))

        return try {
            val result = stockfishPhaseUseCase.invoke { progress ->
                setProgress(workDataOf(KEY_PROGRESS to progress.percentage))
                trySetForeground(buildForegroundInfo(progress.percentage, progress.doneGames, progress.totalGames))
            }

            if (result.hasMoreGames) {
                Log.i(TAG, "More cloud_done games remain — re-enqueueing Stockfish phase")
                reenqueue()
            } else {
                Log.i(TAG, "Stockfish phase fully complete — no more cloud_done games")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Stockfish phase failed: ${e.message}", e)
            Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private fun reenqueue() {
        val request = OneTimeWorkRequestBuilder<StockfishAnalysisWorker>().build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            val isFgsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            val isFgsTypeInvalid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                e is InvalidForegroundServiceTypeException
            if (isFgsBlocked || isFgsTypeInvalid) {
                Log.w(TAG, "Cannot promote to foreground (${e.javaClass.simpleName}), continuing")
            } else throw e
        }
    }

    private fun buildForegroundInfo(
        progress: Float,
        doneGames: Int = 0,
        totalGames: Int = 0
    ): ForegroundInfo {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Stockfish Analysis",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val pct = (progress * 100).toInt()
        val text = when {
            totalGames > 0 -> "Deep analysis: $doneGames/$totalGames games ($pct%)"
            else           -> "Running deep analysis…"
        }
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Analysing games (deep phase)")
            .setContentText(text)
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
