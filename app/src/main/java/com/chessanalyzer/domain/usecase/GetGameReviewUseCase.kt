package com.chessanalyzer.domain.usecase

import com.chessanalyzer.data.repository.GameRepository
import com.chessanalyzer.domain.engine.GameMetrics
import com.chessanalyzer.domain.engine.MoveClassifier
import com.chessanalyzer.domain.model.*
import javax.inject.Inject

class GetGameReviewUseCase @Inject constructor(
    private val gameRepository: GameRepository
) {
    suspend operator fun invoke(gameId: String): AnalysisResult? {
        val game = gameRepository.getGameById(gameId) ?: return null
        if (game.analysisStatus != AnalysisStatus.DONE) return null

        val evaluations = gameRepository.getEvaluationsForGameSync(gameId)

        val whiteEvals = evaluations.filter { it.color == PieceColor.WHITE }
        val blackEvals = evaluations.filter { it.color == PieceColor.BLACK }

        val whiteClassifications = whiteEvals.groupBy { it.classification }
            .mapValues { it.value.size }
        val blackClassifications = blackEvals.groupBy { it.classification }
            .mapValues { it.value.size }

        val keyMoments = evaluations.filter { it.isKeyMoment || it.isTurningPoint }

        // Performance rating per spec §10
        // Use a default rating of 1500 (could be fetched from user profile)
        val whitePerformance = GameMetrics.performanceRating(1500, game.whiteAccuracy ?: 50f)
        val blackPerformance = GameMetrics.performanceRating(1500, game.blackAccuracy ?: 50f)

        return AnalysisResult(
            gameId = gameId,
            whiteAccuracy = game.whiteAccuracy ?: 0f,
            blackAccuracy = game.blackAccuracy ?: 0f,
            whitePerformance = whitePerformance,
            blackPerformance = blackPerformance,
            evaluations = evaluations,
            whiteClassifications = whiteClassifications,
            blackClassifications = blackClassifications,
            keyMoments = keyMoments,
            opening = game.opening
        )
    }
}
