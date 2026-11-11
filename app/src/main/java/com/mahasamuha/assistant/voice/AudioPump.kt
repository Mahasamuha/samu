package com.mahasamuha.assistant.voice

import kotlinx.coroutines.channels.Channel
import java.io.OutputStream

/**
 * Hands audio frames to a blocking [sink] without ever stalling the producer: [offer] queues in memory
 * (unbounded; a listen is seconds of 16 kHz audio) and [run] drains the queue into the sink. Frames offered
 * before the consumer is ready are kept, not dropped. No Android dependencies.
 *
 * The pump is gated. While the gate is closed, real frames wait in the queue untouched and [run] writes silence
 * instead, paced one-for-one with the real audio captured (so it flows in real time and the reader sees a live
 * stream). [openGate] releases the queued audio from its first frame, then live audio follows. [finish] does not
 * open the gate: if capture ends while it is still closed (a short request during a long recognizer start-up),
 * [run] keeps writing silence, one [idleChunkBytes] chunk per [pace], so the reader keeps flowing until the gate
 * opens; then the queued audio is delivered and the sink closes.
 *
 * Thread-safe: producers, [openGate] and [finish] may be called from any thread; [run] is a single consumer.
 */
class AudioPump(
    private val sink: OutputStream,
    private val idleChunkBytes: Int,
    private val pace: suspend () -> Unit,
) {
    private val lock = Any()
    private val frames = ArrayDeque<ByteArray>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var gateOpen = false
    private var finished = false
    private var realOffered = 0L
    private var realWritten = 0L
    private var silenceWritten = 0L

    /** Real audio bytes handed to the sink so far. */
    val realBytesWritten: Long get() = synchronized(lock) { realWritten }

    /** Silence bytes handed to the sink so far. */
    val silenceBytesWritten: Long get() = synchronized(lock) { silenceWritten }

    /** Never blocks. Returns false once [finish] was called, and the frame is discarded. */
    fun offer(frame: ByteArray): Boolean {
        synchronized(lock) {
            if (finished) return false
            frames.addLast(frame)
            realOffered += frame.size
        }
        wake.trySend(Unit)
        return true
    }

    /**
     * Opens the gate. Returns the bytes of real audio queued at that moment, or null if it was already open.
     */
    fun openGate(): Long? {
        val queued =
            synchronized(lock) {
                if (gateOpen) return null
                gateOpen = true
                realOffered - realWritten
            }
        wake.trySend(Unit)
        return queued
    }

    /** No more frames. Once the gate is open, [run] drains what is queued, then closes the sink. */
    fun finish() {
        synchronized(lock) {
            finished = true
        }
        wake.trySend(Unit)
    }

    /** Copies audio to the sink until [finish]; closes the sink on the way out, including on failure. */
    suspend fun run() {
        sink.use {
            while (true) {
                val chunk = nextChunk() ?: break
                if (chunk.bytes.isEmpty()) {
                    wake.receive()
                    continue
                }
                it.write(chunk.bytes)
                synchronized(lock) {
                    if (chunk.silence) silenceWritten += chunk.bytes.size else realWritten += chunk.bytes.size
                }
                if (chunk.paced) pace()
            }
        }
    }

    private class Chunk(
        val bytes: ByteArray,
        val silence: Boolean = false,
        val paced: Boolean = false,
    )

    // Empty chunk: nothing to write yet, wait for a wake-up. Null: finished and drained.
    private fun nextChunk(): Chunk? =
        synchronized(lock) {
            if (gateOpen) {
                val frame = frames.removeFirstOrNull()
                when {
                    frame != null -> Chunk(frame)
                    finished -> null
                    else -> Chunk(EMPTY)
                }
            } else {
                val owed = realOffered - silenceWritten
                when {
                    owed > 0 -> Chunk(ByteArray(owed.toInt()), silence = true)
                    finished -> Chunk(ByteArray(idleChunkBytes), silence = true, paced = true)
                    else -> Chunk(EMPTY)
                }
            }
        }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
