package com.chessanalyzer.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chessanalyzer.domain.model.AnalysisResult
import com.chessanalyzer.domain.model.Game
import com.chessanalyzer.domain.model.MoveClassification
import com.chessanalyzer.domain.model.MoveEvaluation
import com.chessanalyzer.domain.usecase.GetGameReviewUseCase
import com.chessanalyzer.domain.usecase.GetGamesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewUiState(
    val game: Game? = null,
    val analysisResult: AnalysisResult? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val getGamesUseCase: GetGamesUseCase,
    private val getGameReviewUseCase: GetGameReviewUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    fun loadReview(gameId: String) {
        viewModelScope.launch {
            val game = getGamesUseCase.getById(gameId)
            val review = getGameReviewUseCase(gameId)

            _uiState.update {
                it.copy(
                    game = game,
                    analysisResult = review,
                    isLoading = false,
                    errorMessage = if (review == null) "Analysis not available" else null
                )
            }
        }
    }
}
