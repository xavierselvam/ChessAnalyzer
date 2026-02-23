package com.chessanalyzer.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chessanalyzer.data.repository.SyncRepository
import com.chessanalyzer.domain.usecase.SyncGamesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background worker that syncs games from Chess.com and Lichess.
 * Triggered when the app is opened and periodically.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncGamesUseCase: SyncGamesUseCase
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "SyncWorker"
        const val WORK_NAME = "game_sync"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting game sync...")

        return when (val result = syncGamesUseCase()) {
            is SyncRepository.SyncResult.Success -> {
                Log.i(TAG, "Sync completed: ${result.newGamesCount} new games")
                Result.success()
            }
            is SyncRepository.SyncResult.Error -> {
                Log.e(TAG, "Sync failed: ${result.message}")
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }
}
