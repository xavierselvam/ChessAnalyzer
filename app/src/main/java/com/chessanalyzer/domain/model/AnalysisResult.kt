package com.chessanalyzer.domain.model

data class AnalysisResult(
    val gameId: String,
    val whiteAccuracy: Float,
    val blackAccuracy: Float,
    val whitePerformance: Int,
    val blackPerformance: Int,
    val evaluations: List<MoveEvaluation>,
    val whiteClassifications: Map<MoveClassification, Int>,
    val blackClassifications: Map<MoveClassification, Int>,
    val keyMoments: List<MoveEvaluation>,
    val opening: String? = null
)
