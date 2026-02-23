package com.chessanalyzer.domain.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single opening continuation from the Lichess Opening Explorer.
 *
 * @param san       SAN of this move
 * @param white     Number of games White won after this move
 * @param draws     Number of drawn games after this move
 * @param black     Number of games Black won after this move
 * @param total     Total games after this move
 * @param winPct    White win percentage (0-100)
 */
data class OpeningContinuation(
    val san: String,
    val white: Int,
    val draws: Int,
    val black: Int,
    val total: Int
) {
    val winPct: Float get() = if (total > 0) white * 100f / total else 50f
    val drawPct: Float get() = if (total > 0) draws * 100f / total else 0f
    val lossPct: Float get() = if (total > 0) black * 100f / total else 50f
}

/**
 * The full opening explorer result for a position.
 *
 * @param opening    Opening name (null if not identified)
 * @param moves      Top candidate moves sorted by total games (best first)
 * @param white      Total White wins in this position
 * @param draws      Total draws in this position
 * @param black      Total Black wins in this position
 */
data class OpeningExplorerResult(
    val opening: String?,
    val moves: List<OpeningContinuation>,
    val white: Int,
    val draws: Int,
    val black: Int
)

/**
 * Queries the Lichess Opening Explorer API (master games database).
 *
 * Endpoint: https://explorer.lichess.ovh/masters?fen=<URL_ENCODED_FEN>&moves=10&recentGames=0
 *
 * Returns the top 10 continuations played in master (≥2200 Elo) games.
 * Suitable for opening positions (first ~25 moves).
 * Returns null on error or if no data is available.
 */
@Singleton
class LichessOpeningService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "LichessOpening"
        private const val BASE_URL = "https://explorer.lichess.ovh/masters"
    }

    /**
     * Fetch opening continuations for a position.
     * @param fen FEN of the current position
     * @param movesLimit Maximum number of continuations to return (default 10)
     */
    suspend fun fetchContinuations(
        fen: String,
        movesLimit: Int = 10
    ): OpeningExplorerResult? = withContext(Dispatchers.IO) {
        try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = "$BASE_URL?fen=$encodedFen&moves=$movesLimit&recentGames=0"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Opening explorer failed: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val white = json.optInt("white", 0)
            val draws = json.optInt("draws", 0)
            val black = json.optInt("black", 0)

            // Opening name
            val openingName: String? = json.optJSONObject("opening")
                ?.let { o ->
                    val eco  = o.optString("eco", "")
                    val name = o.optString("name", "")
                    if (name.isNotBlank()) "$eco $name".trim() else null
                }

            // Parse moves array
            val movesArr = json.optJSONArray("moves")
            val moves = mutableListOf<OpeningContinuation>()
            if (movesArr != null) {
                for (i in 0 until movesArr.length()) {
                    val m = movesArr.optJSONObject(i) ?: continue
                    val san = m.optString("san").takeIf { it.isNotBlank() } ?: continue
                    moves.add(
                        OpeningContinuation(
                            san = san,
                            white = m.optInt("white", 0),
                            draws = m.optInt("draws", 0),
                            black = m.optInt("black", 0),
                            total = m.optInt("white", 0) + m.optInt("draws", 0) + m.optInt("black", 0)
                        )
                    )
                }
            }

            if (moves.isEmpty() && openingName == null) {
                Log.d(TAG, "No opening data for $fen")
                return@withContext null
            }

            Log.d(TAG, "Opening explorer: $openingName — ${moves.size} continuations for $fen")
            OpeningExplorerResult(
                opening = openingName,
                moves = moves,
                white = white,
                draws = draws,
                black = black
            )
        } catch (e: Exception) {
            Log.w(TAG, "Opening explorer error: ${e.message}")
            null
        }
    }
}
