package com.mahasamuha.assistant.voice

import kotlinx.coroutines.channels.Channel
import java.io.OutputStream

/**
 * Hands audio frames to a blocking [sink] without ever stalling the producer: [offer] queues in memory
 * (unbounded; a listen is seconds of 16 kHz audio) and [run] drains the queue into the sink. Frames offered
 * before the consumer is ready are kept, not dropped. No Android dependencies.
 */
class AudioPump(
    private val sink: OutputStream,
) {
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)

    /** Total bytes handed to the sink so far. */
    @Volatile
    var bytesWritten = 0L
        private set

    /** Never blocks. Returns false once [finish] was called, and the frame is discarded. */
    fun offer(frame: ByteArray): Boolean = frames.trySend(frame).isSuccess

    /** No more frames: [run] drains what is queued, then closes the sink. */
    fun finish() {
        frames.close()
    }

    /** Copies queued frames to the sink until [finish]; closes the sink on the way out, including on failure. */
    suspend fun run() {
        sink.use {
            for (frame in frames) {
                it.write(frame)
                bytesWritten += frame.size
            }
        }
    }
}
