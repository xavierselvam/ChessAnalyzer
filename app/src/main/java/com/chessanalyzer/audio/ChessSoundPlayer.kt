package com.chessanalyzer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChessSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val captureSound: Int = soundPool.load(context.assets.openFd("sounds/capture.mp3"), 1)
    private val moveSound: Int    = soundPool.load(context.assets.openFd("sounds/move.mp3"),    1)
    private val checkSound: Int   = soundPool.load(context.assets.openFd("sounds/check.mp3"),   1)
    private val errorSound: Int   = soundPool.load(context.assets.openFd("sounds/error.mp3"),   1)

    fun playCapture() { soundPool.play(captureSound, 1f, 1f, 1, 0, 1f) }
    fun playMove()    { soundPool.play(moveSound,    1f, 1f, 1, 0, 1f) }
    fun playCheck()   { soundPool.play(checkSound,   1f, 1f, 1, 0, 1f) }
    fun playError()   { soundPool.play(errorSound,   1f, 1f, 1, 0, 1f) }

    /**
     * Play the appropriate sound for a move: check > capture > normal move.
     * [newBoard] is the board state *after* the move has been applied.
     * [isCapture] is true if the move captured a piece (or was en-passant).
     */
    fun playMoveSound(newBoard: com.chessanalyzer.domain.chess.ChessBoard, isCapture: Boolean) {
        when {
            newBoard.isInCheck() -> playCheck()
            isCapture            -> playCapture()
            else                 -> playMove()
        }
    }
}
