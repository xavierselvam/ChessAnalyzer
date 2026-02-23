package com.chessanalyzer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.chessanalyzer.data.local.preferences.UserPreferences
import com.chessanalyzer.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays chess move sounds using [SoundPool].
 *
 * Loading sounds via [SoundPool] is asynchronous; playback requests that arrive before a sound
 * has finished loading are queued and replayed once loading completes.
 *
 * Volume and enable/disable are controlled via [UserPreferences] (DataStore).
 *
 * TODO: Call [close] when the app is destroyed (e.g. from a lifecycle-aware component) to free
 *       the underlying [SoundPool] resources.
 */
@Singleton
class ChessSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    @ApplicationScope private val scope: CoroutineScope
) : Closeable {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // IDs returned by soundPool.load(); 0 means the asset could not be opened.
    private val captureSound: Int
    private val moveSound: Int
    private val checkSound: Int
    private val errorSound: Int

    /**
     * Guards [loadedSounds] and [pendingPlay] so that the check-and-enqueue in [playSound]
     * and the dequeue-and-play in [onLoadCompleteListener] are atomic relative to each other.
     */
    private val lock = Any()

    /** Sound IDs that have finished loading and are ready to play. */
    private val loadedSounds = HashSet<Int>()

    /**
     * Last-requested volume for a sound that was not yet loaded.
     * Once loading completes, the sound is played at this volume.
     */
    private val pendingPlay = HashMap<Int, Float>()

    @Volatile private var soundEnabled: Boolean = true
    @Volatile private var soundVolume: Float = 1.0f

    private val preferenceJobs = mutableListOf<Job>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId != 0) {
                val vol: Float? = synchronized(lock) {
                    loadedSounds.add(sampleId)
                    pendingPlay.remove(sampleId)
                }
                vol?.let { soundPool.play(sampleId, it, it, 1, 0, 1f) }
            }
        }

        captureSound = loadSound("sounds/capture.mp3")
        moveSound    = loadSound("sounds/move.mp3")
        checkSound   = loadSound("sounds/check.mp3")
        errorSound   = loadSound("sounds/error.mp3")

        preferenceJobs += scope.launch {
            userPreferences.soundEnabled.collect { enabled -> soundEnabled = enabled }
        }
        preferenceJobs += scope.launch {
            userPreferences.soundVolume.collect { vol -> soundVolume = vol }
        }
    }

    private fun loadSound(path: String): Int {
        return try {
            soundPool.load(context.assets.openFd(path), 1)
        } catch (e: Exception) {
            Log.w("ChessSoundPlayer", "Could not load sound asset: $path", e)
            0
        }
    }

    private fun playSound(soundId: Int) {
        if (!soundEnabled || soundId == 0) return
        val vol = soundVolume
        val ready: Boolean = synchronized(lock) {
            if (soundId in loadedSounds) {
                true
            } else {
                pendingPlay[soundId] = vol
                false
            }
        }
        if (ready) soundPool.play(soundId, vol, vol, 1, 0, 1f)
    }

    fun playCapture() = playSound(captureSound)
    fun playMove()    = playSound(moveSound)
    fun playCheck()   = playSound(checkSound)
    fun playError()   = playSound(errorSound)

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

    /** Releases the underlying [SoundPool] and cancels preference observation. */
    override fun close() {
        preferenceJobs.forEach { it.cancel() }
        soundPool.release()
    }
}
