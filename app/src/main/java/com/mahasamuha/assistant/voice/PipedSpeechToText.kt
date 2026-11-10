package com.mahasamuha.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experiment: we own the microphone and feed the on-device recognizer through `EXTRA_AUDIO_SOURCE`, so audio
 * captured while a cold recognizer is still starting up waits in the pipe instead of being lost.
 * No VAD: the recognizer is expected to endpoint the piped audio itself; [maxListenMs] closes the pipe if it doesn't.
 * Must be driven from the main thread.
 */
class PipedSpeechToText(
    private val context: Context,
    private val maxListenMs: Long = MAX_LISTEN_MS,
) : SpeechToText {
    @SuppressLint("MissingPermission") // The session checks RECORD_AUDIO before listening.
    override fun listen(expected: List<String>): Flow<SttEvent> =
        callbackFlow {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                Log.w(TAG, "$LABEL: on-device recognition unavailable")
                throw OnDeviceSttUnavailableException()
            }
            val tracker = ListenTracker(SystemClock::elapsedRealtime)

            val chunkBytes = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / CHUNKS_PER_SECOND
            val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBytes, chunkBytes * 4),
                )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                throw IllegalStateException("AudioRecord failed to initialise (microphone busy or permission missing)")
            }
            val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
            val pump = AudioPump(ParcelFileDescriptor.AutoCloseOutputStream(writeSide))

            val capFired = AtomicBoolean(false)
            val ended = AtomicBoolean(false)

            // Safe from any thread and idempotent. The reader loop notices the flag and releases the AudioRecord.
            fun endCapture(reason: String) {
                if (!ended.compareAndSet(false, true)) return
                pump.finish()
                val piped = pump.bytesWritten
                Log.i(TAG, "$LABEL +${tracker.offsetMs()}ms capture ended ($reason), $piped bytes piped, cap fired: ${capFired.get()}")
            }

            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.setRecognitionListener(recognitionListener(LABEL, tracker, onEnd = { endCapture("recognizer done") }))

            try {
                audioRecord.startRecording()
            } catch (e: IllegalStateException) {
                recognizer.destroy()
                audioRecord.release()
                readSide.close()
                writeSide.close()
                throw e
            }
            Log.i(TAG, "$LABEL +${tracker.offsetMs()}ms audio record started")

            val io = CoroutineScope(Dispatchers.IO)
            // The writer blocks on the pipe, so it runs apart from the reader: capture never waits on the recognizer.
            val writer: Job =
                io.launch {
                    try {
                        pump.run()
                    } catch (e: IOException) {
                        Log.i(TAG, "$LABEL: pipe closed by the recognizer (${e.message})")
                    }
                }
            io.launch {
                val buffer = ByteArray(chunkBytes)
                try {
                    while (isActive && !ended.get()) {
                        val n = audioRecord.read(buffer, 0, buffer.size)
                        if (n > 0) {
                            pump.offer(buffer.copyOf(n))
                        } else if (n < 0) {
                            Log.w(TAG, "$LABEL: AudioRecord.read returned $n")
                            break
                        }
                    }
                } finally {
                    runCatching { audioRecord.stop() }
                    audioRecord.release()
                    endCapture("capture stopped")
                }
            }
            io.launch {
                delay(maxListenMs)
                capFired.set(true)
                Log.w(TAG, "$LABEL +${tracker.offsetMs()}ms listen cap of ${maxListenMs}ms fired, closing the pipe")
                endCapture("cap")
            }

            recognizer.startListening(
                recognizerIntent(expected)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ),
            )

            awaitClose {
                endCapture("flow closed")
                // Unblocks a writer stuck on a full pipe if the recognizer walked away.
                runCatching { writeSide.close() }
                // Not closed after startListening: the intent is parcelled later on the main looper, and a closed
                // descriptor fails there with "Bad file descriptor" (error 5).
                runCatching { readSide.close() }
                io.coroutineContext[Job]?.cancel()
                writer.cancel()
                recognizer.cancel()
                recognizer.destroy()
            }
        }.flowOn(Dispatchers.Main)

    private companion object {
        const val LABEL = "STT(piped)"
        const val SAMPLE_RATE_HZ = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val CHUNKS_PER_SECOND = 20 // 50 ms frames
        const val MAX_LISTEN_MS = 15_000L
    }
}
