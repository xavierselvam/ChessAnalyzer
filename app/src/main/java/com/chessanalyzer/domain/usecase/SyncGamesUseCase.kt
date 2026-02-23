package com.chessanalyzer.domain.usecase

import com.chessanalyzer.data.local.preferences.UserPreferences
import com.chessanalyzer.data.repository.SyncRepository
import javax.inject.Inject

class SyncGamesUseCase @Inject constructor(
    private val syncRepository: SyncRepository,
    private val userPreferences: UserPreferences
) {
    suspend operator fun invoke(): SyncRepository.SyncResult {
        val result = syncRepository.syncAllPlatforms()
        if (result is SyncRepository.SyncResult.Success) {
            userPreferences.setLastSyncTime(System.currentTimeMillis())
        }
        return result
    }

    suspend fun syncChessCom(username: String) = syncRepository.syncChessCom(username)
    suspend fun syncLichess(username: String) = syncRepository.syncLichess(username)
}
