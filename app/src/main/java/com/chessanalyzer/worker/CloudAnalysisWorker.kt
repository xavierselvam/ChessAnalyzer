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
import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.domain.usecase.CloudPhaseUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Phase 1 of the two-phase hybrid analysis:
 * Calls Lichess cloud eval for all pending games in parallel, then enqueues
 * [StockfishAnalysisWorker] to finalise moves that missed the cloud cache.
 */
@HiltWorker
class CloudAnalysisWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val cloudPhaseUseCase: CloudPhaseUseCase,
    private val gameRepository: GameRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "CloudAnalysisWorker"
        const val KEY_PROGRESS = "progress"
        const val QUEUE_NAME = "analysis_cloud_phase"
        private const val NOTIFICATION_CHANNEL_ID = "chess_cloud_analysis_channel"
        private const val NOTIFICATION_ID = 2002
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting cloud analysis phase")
        trySetForeground(buildForegroundInfo(0f))

        return try {
            val pendingIds = gameRepository.getPendingGameIds()
            if (pendingIds.isEmpty()) {
                Log.d(TAG, "No pending games for cloud phase")
                return Result.success()
            }
            Log.i(TAG, "Cloud phase: ${pendingIds.size} pending game(s)")

            cloudPhaseUseCase.invoke(pendingIds) { progress ->
                setProgress(workDataOf(KEY_PROGRESS to progress.percentage))
                trySetForeground(buildForegroundInfo(progress.percentage, progress.processed, progress.total))
            }

            Log.i(TAG, "Cloud phase complete — enqueuing Stockfish phase")
            enqueueStockfishPhase()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cloud phase failed: ${e.message}", e)
            Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private fun enqueueStockfishPhase() {
        val request = OneTimeWorkRequestBuilder<StockfishAnalysisWorker>().build()
        appContext.let {
            WorkManager.getInstance(it)
                .enqueueUniqueWork(
                    StockfishAnalysisWorker.QUEUE_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
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
        processed: Int = 0,
        total: Int = 0
    ): ForegroundInfo {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Cloud Analysis",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val pct = (progress * 100).toInt()
        val text = when {
            total > 0 -> "Fetching cloud evals: $processed/$total positions ($pct%)"
            else      -> "Fetching cloud evaluations…"
        }
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Analysing games (cloud phase)")
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
