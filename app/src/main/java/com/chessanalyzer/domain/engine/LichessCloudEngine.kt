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
 * Lichess cloud evaluation engine.
 *
 * Calls https://lichess.org/api/cloud-eval to retrieve pre-computed
 * Stockfish evaluations (typically depth 40+) instantly.
 * Returns null if the position is not in the cloud database (rare for standard play).
 *
 * cp values from the API are side-to-move perspective; this class converts
 * them to White's perspective to match StockfishEngine.EvalResult.
 */
@Singleton
class LichessCloudEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "LichessCloudEngine"
        private const val BASE_URL = "https://lichess.org/api/cloud-eval"
    }

    /**
     * Evaluate a position. Returns null if the position is not cached in Lichess cloud.
     * @param fen FEN string of the position
     * @param multiPv number of principal variations to return (1 or 3)
     */
    suspend fun evaluate(fen: String, multiPv: Int = 1): StockfishEngine.EvalResult? =
        withContext(Dispatchers.IO) {
            try {
                val encodedFen = URLEncoder.encode(fen, "UTF-8")
                val url = "$BASE_URL?fen=$encodedFen&multiPv=$multiPv"

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (response.code == 404) {
                    Log.d(TAG, "Position not in cloud DB: $fen")
                    return@withContext null
                }

                if (!response.isSuccessful) {
                    Log.w(TAG, "Cloud eval HTTP ${response.code} for fen: $fen")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val pvArray = json.optJSONArray("pvs") ?: return@withContext null
                if (pvArray.length() == 0) return@withContext null

                // Lichess cp is side-to-move perspective; convert to White's perspective
                val isBlackToMove = fen.contains(" b ")

                val pvLines = mutableListOf<StockfishEngine.PvLine>()
                for (i in 0 until pvArray.length()) {
                    val pv = pvArray.getJSONObject(i)
                    val moves = pv.optString("moves", "")

                    if (pv.has("mate")) {
                        val mateStm = pv.getInt("mate")  // side-to-move perspective
                        val mateWhite = if (isBlackToMove) -mateStm else mateStm
                        pvLines.add(
                            StockfishEngine.PvLine(
                                multipv = i + 1,
                                centipawns = if (mateWhite > 0) 9900 else -9900,
                                isMate = true,
                                mateIn = mateWhite,
                                pv = moves
                            )
                        )
                    } else {
                        val cpStm = pv.getInt("cp")
                        val cpWhite = if (isBlackToMove) -cpStm else cpStm
                        pvLines.add(
                            StockfishEngine.PvLine(
                                multipv = i + 1,
                                centipawns = cpWhite,
                                pv = moves
                            )
                        )
                    }
                }

                val best = pvLines.firstOrNull() ?: return@withContext null
                val bestMoveUci = best.pv.split(" ").firstOrNull() ?: ""

                val depth = json.optInt("depth", 0)
                Log.d(TAG, "[cloud] depth=$depth bestMove=$bestMoveUci cp=${best.centipawns} fen=$fen")

                StockfishEngine.EvalResult(
                    centipawns = best.centipawns,
                    isMate = best.isMate,
                    mateIn = best.mateIn,
                    bestMoveUci = bestMoveUci,
                    pv = best.pv,
                    pvLines = pvLines
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cloud eval exception: ${e.message}")
                null
            }
        }
}
