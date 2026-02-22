package com.chessanalyzer.domain.usecase

import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.domain.model.Game
import com.chessanalyzer.domain.model.Platform
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetGamesUseCase @Inject constructor(
    private val gameRepository: GameRepository
) {
    operator fun invoke(platform: Platform? = null, gameType: String? = null): Flow<List<Game>> {
        return when {
            platform != null && gameType != null ->
                gameRepository.getGamesByPlatformAndType(platform, gameType)
            platform != null ->
                gameRepository.getGamesByPlatform(platform)
            gameType != null ->
                gameRepository.getGamesByType(gameType)
            else ->
                gameRepository.getAllGames()
        }
    }

    suspend fun getById(gameId: String): Game? = gameRepository.getGameById(gameId)

    fun observeById(gameId: String): Flow<Game?> = gameRepository.observeGameById(gameId)
}
