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
 * Result of a Lichess Syzygy tablebase lookup.
 *
 * @param category  "win" | "draw" | "loss" | "cursed-win" | "blessed-loss" | "unknown"
 * @param dtz       Distance to zero (moves to conversion, null if unknown)
 * @param dtm       Distance to mate (null for most positions)
 * @param bestMove  Best move UCI according to the tablebase (null if unavailable)
 */
data class TablebaseResult(
    val category: String,
    val dtz: Int?,
    val dtm: Int?,
    val bestMove: String?
)

/**
 * Queries the Lichess Syzygy tablebase API.
 *
 * Endpoint: https://tablebase.lichess.ovh/standard?fen=<URL_ENCODED_FEN>
 *
 * Only available for positions with ≤7 pieces (including kings).
 * Returns null when the API returns an error or the FEN has >7 pieces.
 */
@Singleton
class LichessTablebaseService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "LichessTablebase"
        private const val BASE_URL = "https://tablebase.lichess.ovh/standard"
    }

    /**
     * Look up a position in the Syzygy tablebase.
     * @param fen FEN of the position to look up
     * @return TablebaseResult or null if not in tablebase / error
     */
    suspend fun lookup(fen: String): TablebaseResult? = withContext(Dispatchers.IO) {
        try {
            // Quick pre-check: count pieces; tablebase only valid for ≤7 pieces
            val piecePart = fen.split(" ").firstOrNull() ?: fen
            val pieceCount = piecePart.count { it.isLetter() }
            if (pieceCount > 7) return@withContext null

            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = "$BASE_URL?fen=$encodedFen"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Tablebase lookup failed: HTTP ${response.code} for $fen")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val category = json.optString("category", "unknown")
            val dtz = if (json.has("dtz") && !json.isNull("dtz")) json.getInt("dtz") else null
            val dtm = if (json.has("dtm") && !json.isNull("dtm")) json.getInt("dtm") else null

            // Best move is the first move in the moves array
            val bestMove: String? = json.optJSONArray("moves")
                ?.optJSONObject(0)
                ?.optString("uci")
                ?.takeIf { it.isNotBlank() }

            Log.d(TAG, "Tablebase: $fen → category=$category dtz=$dtz bestMove=$bestMove")
            TablebaseResult(category = category, dtz = dtz, dtm = dtm, bestMove = bestMove)

        } catch (e: Exception) {
            Log.w(TAG, "Tablebase lookup error: ${e.message}")
            null
        }
    }
}
