package com.chessanalyzer.data.repository

import android.util.Log
import com.chessanalyzer.data.local.db.GameEntity
import com.chessanalyzer.data.local.preferences.UserPreferences
import com.chessanalyzer.data.remote.chesscom.ChessComApi
import com.chessanalyzer.data.remote.chesscom.ChessComGame
import com.chessanalyzer.data.remote.lichess.LichessApi
import com.chessanalyzer.data.remote.lichess.LichessGame
import com.chessanalyzer.domain.model.Platform
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    private val chessComApi: ChessComApi,
    private val lichessApi: LichessApi,
    private val gameRepository: GameRepository,
    private val userPreferences: UserPreferences,
    private val moshi: Moshi
) {
    companion object {
        private const val TAG = "SyncRepository"
    }

    sealed class SyncResult {
        data class Success(val newGamesCount: Int) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    suspend fun syncAllPlatforms(): SyncResult {
        var totalNew = 0

        val chessComUsername = userPreferences.chessComUsername.first()
        if (chessComUsername.isNotBlank()) {
            when (val result = syncChessCom(chessComUsername)) {
                is SyncResult.Success -> totalNew += result.newGamesCount
                is SyncResult.Error -> Log.w(TAG, "Chess.com sync error: ${result.message}")
            }
        }

        val lichessUsername = userPreferences.lichessUsername.first()
        if (lichessUsername.isNotBlank()) {
            when (val result = syncLichess(lichessUsername)) {
                is SyncResult.Success -> totalNew += result.newGamesCount
                is SyncResult.Error -> Log.w(TAG, "Lichess sync error: ${result.message}")
            }
        }

        return SyncResult.Success(totalNew)
    }

    suspend fun syncChessCom(username: String): SyncResult {
        return try {
            val archivesResponse = chessComApi.getArchives(username)
            if (!archivesResponse.isSuccessful) {
                return SyncResult.Error("Failed to fetch archives: ${archivesResponse.code()}")
            }

            val archives = archivesResponse.body()?.archives ?: return SyncResult.Error("No archives")
            val lastTimestamp = gameRepository.getLatestGameTimestamp(Platform.CHESS_COM)

            var totalNew = 0
            val excludedTypes = userPreferences.excludedSyncTypes.first()

            // Full sync: process all archives. Incremental: only last 3 months needed.
            val archivesToProcess = if (lastTimestamp == null) archives else archives.takeLast(3)

            for (archiveUrl in archivesToProcess) {
                val parts = archiveUrl.split("/")
                if (parts.size < 2) continue
                val year = parts[parts.size - 2]
                val month = parts[parts.size - 1]

                val gamesResponse = chessComApi.getGames(username, year, month)
                if (!gamesResponse.isSuccessful) continue

                val games = gamesResponse.body()?.games ?: continue
                val archiveGames = mutableListOf<GameEntity>()
                for (game in games) {
                    if (game.pgn == null) continue
                    if (game.rules != "chess") continue  // skip variants
                    if (lastTimestamp != null && game.endTime * 1000 <= lastTimestamp) continue
                    val entity = mapChessComGame(game, username)
                    if (entity.gameType !in excludedTypes) archiveGames.add(entity)
                }
                // Insert in chunks of 100 to avoid large transactions crashing
                if (archiveGames.isNotEmpty()) {
                    archiveGames.chunked(100).forEach { chunk ->
                        gameRepository.insertGames(chunk)
                    }
                    totalNew += archiveGames.size
                    Log.d(TAG, "Chess.com $year/$month: inserted ${archiveGames.size} games")
                }
            }

            SyncResult.Success(totalNew)
        } catch (e: Exception) {
            Log.e(TAG, "Chess.com sync failed", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun syncLichess(username: String): SyncResult {
        return try {
            val lastTimestamp = gameRepository.getLatestGameTimestamp(Platform.LICHESS)
            val since = lastTimestamp?.let { it + 1 }
            val excludedTypes = userPreferences.excludedSyncTypes.first()
            val adapter = moshi.adapter(LichessGame::class.java)
            var totalNew = 0

            // Paginate: Lichess returns newest-first; use `until` to walk backwards
            // through history. Stop when < 300 returned (end of history) or when
            // all remaining games predate our lastTimestamp.
            var until: Long? = null
            var pagesFetched = 0

            while (true) {
                val response = lichessApi.getGames(
                    username = username,
                    max = 300,
                    since = since,
                    until = until
                )

                if (!response.isSuccessful) {
                    if (totalNew == 0) return SyncResult.Error("Lichess API error: ${response.code()}")
                    break
                }

                val body = response.body()?.string() ?: break
                val lines = body.lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) break

                var oldestCreatedAt: Long = Long.MAX_VALUE
                val pageGames = mutableListOf<GameEntity>()

                lines.forEach { line ->
                    try {
                        val game = adapter.fromJson(line) ?: return@forEach
                        if (game.variant != "standard") return@forEach
                        oldestCreatedAt = minOf(oldestCreatedAt, game.createdAt)
                        val entity = mapLichessGame(game, username)
                        if (entity.gameType !in excludedTypes) pageGames.add(entity)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse Lichess game line", e)
                    }
                }

                pagesFetched++
                // Insert in chunks of 100 to avoid large transactions crashing
                if (pageGames.isNotEmpty()) {
                    pageGames.chunked(100).forEach { chunk ->
                        gameRepository.insertGames(chunk)
                    }
                    totalNew += pageGames.size
                }
                Log.d(TAG, "Lichess page $pagesFetched: ${pageGames.size} games inserted")

                // If fewer than 300 returned, we've reached the end
                if (lines.size < 300) break

                // Move `until` pointer to just before the oldest game in this page
                until = oldestCreatedAt - 1
            }

            SyncResult.Success(totalNew)
        } catch (e: Exception) {
            Log.e(TAG, "Lichess sync failed", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun validateChessComUsername(username: String): Boolean {
        return try {
            val response = chessComApi.getPlayer(username)
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun validateLichessUsername(username: String): Boolean {
        return try {
            val response = lichessApi.getUser(username)
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun mapChessComGame(game: ChessComGame, username: String): GameEntity {
        val userIsWhite = game.white.username.equals(username, ignoreCase = true)
        val result = when {
            game.white.result == "win" -> "1-0"
            game.black.result == "win" -> "0-1"
            else -> "1/2-1/2"
        }
        val pgn = game.pgn ?: ""
        val opening = extractOpeningFromPgn(pgn)
        val moveCount = countMovesInPgn(pgn)
        val gameType = deriveChessComGameType(pgn, game.timeControl)
        val userRating = if (userIsWhite) game.white.rating else game.black.rating
        val opponentRating = if (userIsWhite) game.black.rating else game.white.rating

        return GameEntity(
            id = "chesscom_${game.url.substringAfterLast("/")}",
            platform = "chesscom",
            pgn = pgn,
            white = game.white.username,
            black = game.black.username,
            result = result,
            timeControl = game.timeControl,
            playedAt = game.endTime * 1000,
            opening = opening,
            analysisStatus = "pending",
            userColor = if (userIsWhite) "white" else "black",
            totalMoves = moveCount,
            gameType = gameType,
            userRating = userRating,
            opponentRating = opponentRating
        )
    }

    private fun mapLichessGame(game: LichessGame, username: String): GameEntity {
        val whiteUser = game.players.white.user?.name ?: "Anonymous"
        val blackUser = game.players.black.user?.name ?: "Anonymous"
        val userIsWhite = whiteUser.equals(username, ignoreCase = true)

        val result = when (game.winner) {
            "white" -> "1-0"
            "black" -> "0-1"
            else -> "1/2-1/2"
        }

        val timeControl = game.clock?.let { "${it.initial / 60}+${it.increment}" } ?: game.speed
        val pgn = game.pgn ?: buildBasicPgn(game)
        val moveCount = game.moves?.split(" ")?.size?.div(2) ?: countMovesInPgn(pgn)
        val gameType = normalizeLichessSpeed(game.speed)
        val userRating = if (userIsWhite) game.players.white.rating else game.players.black.rating
        val opponentRating = if (userIsWhite) game.players.black.rating else game.players.white.rating

        return GameEntity(
            id = "lichess_${game.id}",
            platform = "lichess",
            pgn = pgn,
            white = whiteUser,
            black = blackUser,
            result = result,
            timeControl = timeControl,
            playedAt = game.createdAt,
            opening = game.opening?.name,
            analysisStatus = "pending",
            userColor = if (userIsWhite) "white" else "black",
            totalMoves = moveCount,
            gameType = gameType,
            userRating = userRating,
            opponentRating = opponentRating
        )
    }

    private fun buildBasicPgn(game: LichessGame): String {
        val moves = game.moves ?: return ""
        val whiteUser = game.players.white.user?.name ?: "?"
        val blackUser = game.players.black.user?.name ?: "?"
        val result = when (game.winner) {
            "white" -> "1-0"
            "black" -> "0-1"
            else -> "1/2-1/2"
        }
        return """
            [White "$whiteUser"]
            [Black "$blackUser"]
            [Result "$result"]
            
            ${formatUciMovesToPgn(moves)} $result
        """.trimIndent()
    }

    private fun formatUciMovesToPgn(uciMoves: String): String {
        // Lichess moves are in UCI notation (e2e4 d7d5 ...), just return as-is for now
        // The PGN parser will handle the conversion
        return uciMoves
    }

    private fun deriveChessComGameType(pgn: String, timeControl: String): String {
        // Prefer the [TimeClass "..."] PGN header (most reliable)
        val timeClass = Regex("""\[TimeClass\s+"([^"]+)"\]""").find(pgn)?.groupValues?.get(1)
        if (timeClass != null) return timeClass.lowercase()
        // Fallback: derive from time-control string "secs+inc"
        val secs = timeControl.split("+")[0].toLongOrNull() ?: 0L
        return when {
            secs == 0L -> "daily"
            secs < 180L -> "bullet"
            secs < 600L -> "blitz"
            else -> "rapid"
        }
    }

    private fun normalizeLichessSpeed(speed: String): String = when (speed.lowercase()) {
        "ultrabullet", "bullet" -> "bullet"
        "blitz" -> "blitz"
        "rapid" -> "rapid"
        "classical" -> "classical"
        "correspondence" -> "daily"
        else -> "rapid"
    }

    private fun extractOpeningFromPgn(pgn: String): String? {
        val regex = """\[ECOUrl\s+"[^"]*?/([^"]+)"\]""".toRegex()
        val match = regex.find(pgn)
        return match?.groupValues?.get(1)?.replace("-", " ")
            ?: run {
                val openingRegex = """\[Opening\s+"([^"]+)"\]""".toRegex()
                openingRegex.find(pgn)?.groupValues?.get(1)
            }
    }

    private fun countMovesInPgn(pgn: String): Int {
        val moveNumberRegex = """(\d+)\.""".toRegex()
        val matches = moveNumberRegex.findAll(pgn)
        return matches.lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
