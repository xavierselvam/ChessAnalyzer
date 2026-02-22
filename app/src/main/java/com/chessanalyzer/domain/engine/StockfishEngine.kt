package com.chessanalyzer.domain.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge to the Stockfish chess engine binary.
 * Communicates via UCI protocol over stdin/stdout of the native process.
 *
 * Configuration per spec:
 *   Threads = number_of_cpu_cores
 *   Hash = 256 MB minimum
 *   MultiPV = 3
 *   UCI_ShowWDL = true
 *
 * Analysis: depth 18-22 (configurable)
 */
@Singleton
class StockfishEngine @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "StockfishEngine"
        private const val LIB_NAME = "libstockfish.so"
    }

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    private var isRunning = false

    /** Single PV line result from the engine. */
    data class PvLine(
        val multipv: Int,
        val centipawns: Int,       // from side-to-move perspective
        val isMate: Boolean = false,
        val mateIn: Int? = null,
        val pv: String = "",
        val depth: Int = 0,
        val wdl: Triple<Int, Int, Int>? = null  // win/draw/loss per-mille
    )

    /** Full evaluation result with multiple PV lines. */
    data class EvalResult(
        val centipawns: Int,       // best line eval (White's perspective)
        val isMate: Boolean = false,
        val mateIn: Int? = null,
        val bestMoveUci: String = "",
        val pv: String = "",
        val pvLines: List<PvLine> = emptyList(),
        val wdl: Triple<Int, Int, Int>? = null
    ) {
        /** Mate → centipawn: sign * (10000 - moves_to_mate * 100) */
        val mateScoreCp: Int
            get() = if (isMate && mateIn != null) {
                val sign = if (mateIn > 0) 1 else -1
                sign * (10000 - kotlin.math.abs(mateIn) * 100)
            } else centipawns

        /** Effective cp (collapses mate to cp). Always from White's perspective. */
        val effectiveCp: Int get() = if (isMate) mateScoreCp else centipawns
    }

    /** Start engine with proper UCI settings. */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext

        val binaryFile = findBinary()
        if (binaryFile == null) {
            Log.e(TAG, "Stockfish binary not found in nativeLibraryDir")
            return@withContext
        }

        try {
            val pb = ProcessBuilder(binaryFile.absolutePath)
            pb.redirectErrorStream(true)
            process = pb.start()

            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            sendCommand("uci")
            waitForResponse("uciok")

            val cpuCores = Runtime.getRuntime().availableProcessors()
            sendCommand("setoption name Threads value $cpuCores")
            sendCommand("setoption name Hash value 256")
            sendCommand("setoption name MultiPV value 3")
            sendCommand("setoption name UCI_ShowWDL value true")

            sendCommand("isready")
            waitForResponse("readyok")

            isRunning = true
            Log.i(TAG, "Stockfish started (threads=$cpuCores, hash=256, multipv=3, wdl=true)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Stockfish", e)
            stop()
        }
    }

    fun stop() {
        try { if (isRunning) sendCommand("quit") } catch (_: Exception) {}
        try { writer?.close(); reader?.close(); process?.destroy() } catch (_: Exception) {}
        writer = null; reader = null; process = null; isRunning = false
    }

    /**
     * Evaluate a position with MultiPV.
     * @param fen position
     * @param depth analysis depth (18-22 recommended)
     * @param multiPv number of PV lines
     * @return EvalResult with all PV lines, oriented to White's perspective
     */
    suspend fun evaluate(
        fen: String,
        depth: Int = 20,
        multiPv: Int = 3
    ): EvalResult = withContext(Dispatchers.IO) {
        if (!isRunning) start()

        val sideToMove = fen.split(" ").getOrElse(1) { "w" }[0]

        sendCommand("setoption name MultiPV value $multiPv")
        sendCommand("isready")
        waitForResponse("readyok")

        sendCommand("position fen $fen")
        sendCommand("go depth $depth")
        Log.d(TAG, "[eval] fen=$fen depth=$depth multiPv=$multiPv")

        val currentPvLines = mutableMapOf<Int, PvLine>()
        var bestMove = ""
        var finalDepth = 0
        var lineCount = 0

        try {
            while (true) {
                val line = reader?.readLine()
                if (line == null) {
                    Log.e(TAG, "[eval] reader returned null — engine stream closed!")
                    break
                }
                lineCount++
                if (lineCount <= 6) Log.d(TAG, "[eval] raw[$lineCount]: $line")
                if (line.startsWith("info") && "score" in line) {
                    val pvLine = parseInfoLine(line)
                    if (pvLine.depth >= finalDepth) {
                        if (pvLine.depth > finalDepth) {
                            currentPvLines.clear()
                            finalDepth = pvLine.depth
                        }
                        currentPvLines[pvLine.multipv] = pvLine
                    }
                }
                if (line.startsWith("bestmove")) {
                    bestMove = line.split(" ").getOrElse(1) { "" }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during evaluation", e)
        }

        if (currentPvLines.isEmpty()) {
            Log.e(TAG, "[eval] WARNING: pvLines EMPTY after $lineCount lines — returning cp=0! fen=$fen")
        } else {
            Log.d(TAG, "[eval] ok: depth=$finalDepth bestMove=$bestMove cp=${currentPvLines[1]?.centipawns}")
        }

        val pvLines = currentPvLines.values.sortedBy { it.multipv }
        val bestPv = pvLines.firstOrNull()
        val flipSign = if (sideToMove == 'b') -1 else 1

        val whiteOrientedPvLines = pvLines.map { pv ->
            pv.copy(
                centipawns = pv.centipawns * flipSign,
                mateIn = pv.mateIn?.let { it * flipSign }
            )
        }

        EvalResult(
            centipawns = (bestPv?.centipawns ?: 0) * flipSign,
            isMate = bestPv?.isMate ?: false,
            mateIn = bestPv?.mateIn?.let { it * flipSign },
            bestMoveUci = bestMove,
            pv = bestPv?.pv ?: "",
            pvLines = whiteOrientedPvLines,
            wdl = bestPv?.wdl
        )
    }

    /** Evaluate with single PV (faster for after-move evals). */
    suspend fun evaluateSingle(fen: String, depth: Int = 20): EvalResult {
        return evaluate(fen, depth, multiPv = 1)
    }

    suspend fun getBestMove(fen: String, depth: Int = 20): EvalResult = evaluate(fen, depth)

    private fun sendCommand(command: String) {
        try { writer?.write("$command\n"); writer?.flush() }
        catch (e: Exception) { Log.e(TAG, "Failed to send: $command", e) }
    }

    private fun waitForResponse(expected: String): Boolean {
        try {
            while (true) {
                val line = reader?.readLine() ?: return false
                if (line.contains(expected)) return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for: $expected", e)
            return false
        }
    }

    /**
     * Parse UCI info line:
     *   info depth 20 multipv 1 score cp 35 wdl 152 810 38 pv e2e4 e7e5
     */
    private fun parseInfoLine(line: String): PvLine {
        val tokens = line.split(" ")
        var cp = 0; var isMate = false; var mateIn: Int? = null
        var pv = ""; var multipv = 1; var depth = 0
        var wdl: Triple<Int, Int, Int>? = null

        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> { depth = tokens.getOrElse(i + 1) { "0" }.toIntOrNull() ?: 0; i += 2 }
                "multipv" -> { multipv = tokens.getOrElse(i + 1) { "1" }.toIntOrNull() ?: 1; i += 2 }
                "cp" -> { cp = tokens.getOrElse(i + 1) { "0" }.toIntOrNull() ?: 0; i += 2 }
                "mate" -> {
                    isMate = true
                    mateIn = tokens.getOrElse(i + 1) { "0" }.toIntOrNull()
                    cp = if (mateIn != null) {
                        val sign = if (mateIn > 0) 1 else -1
                        sign * (10000 - kotlin.math.abs(mateIn) * 100)
                    } else 0
                    i += 2
                }
                "wdl" -> {
                    val w = tokens.getOrElse(i + 1) { "0" }.toIntOrNull() ?: 0
                    val d = tokens.getOrElse(i + 2) { "0" }.toIntOrNull() ?: 0
                    val l = tokens.getOrElse(i + 3) { "0" }.toIntOrNull() ?: 0
                    wdl = Triple(w, d, l)
                    i += 4
                }
                "pv" -> { pv = tokens.drop(i + 1).joinToString(" "); i = tokens.size }
                else -> i++
            }
        }

        return PvLine(multipv = multipv, centipawns = cp, isMate = isMate,
            mateIn = mateIn, pv = pv, depth = depth, wdl = wdl)
    }

    /**
     * Locate the Stockfish binary extracted by Android into nativeLibraryDir.
     * Shipped as jniLibs/arm64-v8a/libstockfish.so so Android extracts it to
     * a directory that IS executable (unlike files/ which is noexec on API 29+).
     */
    private fun findBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val file = File(nativeDir, LIB_NAME)
        Log.d(TAG, "Looking for Stockfish at: ${file.absolutePath} exists=${file.exists()} canExec=${file.canExecute()}")
        return if (file.exists()) file else null
    }
}
