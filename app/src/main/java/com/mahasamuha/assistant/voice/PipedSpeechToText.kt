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
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experiment: we own the microphone and feed the on-device recognizer through `EXTRA_AUDIO_SOURCE`, so audio
 * captured while a cold recognizer is still starting up waits in the pipe instead of being lost.
 * The recognizer won't finalise piped audio until the pipe closes, and its callbacks run in processing time while it
 * catches up, so endpointing is ours: a WebRTC VAD on the audio as recorded feeds an [Endpointer] (audio time),
 * and an end closes the pipe. The session is segmented, so speech the recognizer split into several segments is
 * joined. [maxListenMs] closes the pipe regardless.
 * A cold recognizer seems to discard audio older than a short window while its engine initialises, so until its
 * first level callback ("recognizer up") the pipe carries paced silence and the real audio waits in the pump's
 * queue; then the queue is sent from the first frame. After [GATE_TIMEOUT_MS] without that signal, or at the end
 * of the listen, the gate opens anyway.
 * Must be driven from the main thread.
 */
class PipedSpeechToText(
    private val context: Context,
    private val maxListenMs: Long = MAX_LISTEN_MS,
) : SpeechToText {
    @SuppressLint("MissingPermission") // The session checks RECORD_AUDIO before listening.
    override fun listen(
        expected: List<String>,
        firstInSession: Boolean,
    ): Flow<SttEvent> =
        callbackFlow {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                Log.w(TAG, "$LABEL: on-device recognition unavailable")
                throw OnDeviceSttUnavailableException()
            }
            val tracker = ListenTracker(SystemClock::elapsedRealtime)

            val chunkBytes = VAD_FRAME_SAMPLES * FRAMES_PER_CHUNK * BYTES_PER_SAMPLE
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
            val pump =
                AudioPump(
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide),
                    idleChunkBytes = chunkBytes,
                    pace = { delay(VAD_FRAME_MS.toLong() * FRAMES_PER_CHUNK) },
                )

            val capFired = AtomicBoolean(false)
            val ended = AtomicBoolean(false)

            val levelStats = ListenLevelStats()

            // Idempotent, any thread. Real audio queued behind a closed gate is released at the end of the listen.
            fun openGate(reason: String) {
                val queued = pump.openGate() ?: return
                val queuedMs = queued / BYTES_PER_MS
                Log.i(TAG, "$LABEL +${tracker.offsetMs()}ms gate open ($reason), $queuedMs ms of real audio queued")
            }

            // Safe from any thread and idempotent. The reader loop notices the flag and releases the AudioRecord.
            // An endpoint leaves the gate alone: during a long start-up the recognizer discards early audio, so the
            // queue waits (behind paced silence) for "recognizer up" or the gate timeout. Only ends that mean the
            // recognizer is done or gone release it at once.
            fun endCapture(
                reason: String,
                releaseGate: Boolean,
            ) {
                if (releaseGate) openGate(reason)
                if (!ended.compareAndSet(false, true)) return
                pump.finish()
                Log.i(TAG, "$LABEL +${tracker.offsetMs()}ms capture ended ($reason), cap fired: ${capFired.get()}")
                Log.i(TAG, "$LABEL ${levelStats.summary()}")
            }

            val endpointer = Endpointer(frameMs = VAD_FRAME_MS, waitSilenceMs = Endpointer.waitSilenceMs(firstInSession))
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val transcript = SegmentedTranscript()
            recognizer.setRecognitionListener(
                recognitionListener(
                    LABEL,
                    tracker,
                    transcript,
                    onEnd = { endCapture("recognizer done", releaseGate = true) },
                    onText = endpointer::onTextHeard,
                    onRms = {
                        levelStats.onRecognizerDb(it)
                        openGate("recognizer up")
                    },
                ),
            )

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
                        Log.i(TAG, "$LABEL: ${pump.realBytesWritten} bytes real, ${pump.silenceBytesWritten} bytes silence piped")
                    } catch (e: IOException) {
                        Log.i(TAG, "$LABEL: pipe closed by the recognizer (${e.message})")
                    }
                }
            io.launch {
                val buffer = ByteArray(chunkBytes)
                val samples = ShortArray(chunkBytes / BYTES_PER_SAMPLE)
                val frame = ShortArray(VAD_FRAME_SAMPLES)
                var firstLevelLogged = false
                val vad =
                    VadWebRTC(
                        sampleRate = SampleRate.SAMPLE_RATE_16K,
                        frameSize = FrameSize.FRAME_SIZE_320,
                        mode = Mode.VERY_AGGRESSIVE,
                        silenceDurationMs = VAD_HANGOVER_MS, // our Endpointer owns the silence rule
                        speechDurationMs = VAD_SPEECH_MS,
                    )
                try {
                    while (isActive && !ended.get()) {
                        val n = audioRecord.read(buffer, 0, buffer.size)
                        if (n > 0) {
                            pump.offer(buffer.copyOf(n))
                            val count = n / BYTES_PER_SAMPLE
                            ByteBuffer
                                .wrap(buffer, 0, n)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .get(samples, 0, count)
                            // One level per chunk (~17/s), from the first recorded audio.
                            val dbfs = InputLevel.rmsDbfs(samples, count)
                            levelStats.onChunk(dbfs)
                            trySend(SttEvent.Level(InputLevel.fromDbfs(dbfs)))
                            if (!firstLevelLogged) {
                                firstLevelLogged = true
                                Log.i(TAG, "$LABEL +${tracker.offsetMs()}ms first level (ours)")
                            }
                            for (start in 0 until count - VAD_FRAME_SAMPLES + 1 step VAD_FRAME_SAMPLES) {
                                samples.copyInto(frame, 0, start, start + VAD_FRAME_SAMPLES)
                                val reason = endpointer.onFrame(vad.isSpeech(frame)) ?: continue
                                Log.i(
                                    TAG,
                                    "$LABEL +${tracker.offsetMs()}ms endpoint ($reason) at audio ${endpointer.elapsedMs}ms, " +
                                        "text heard: ${endpointer.hasHeardText}, first in session: $firstInSession",
                                )
                                endCapture("endpoint", releaseGate = false)
                                break
                            }
                        } else if (n < 0) {
                            Log.w(TAG, "$LABEL: AudioRecord.read returned $n")
                            break
                        }
                    }
                } finally {
                    vad.close()
                    runCatching { audioRecord.stop() }
                    audioRecord.release()
                    endCapture("capture stopped", releaseGate = false)
                }
            }
            io.launch {
                delay(GATE_TIMEOUT_MS)
                openGate("timeout")
            }
            io.launch {
                delay(maxListenMs)
                capFired.set(true)
                Log.w(TAG, "$LABEL +${tracker.offsetMs()}ms listen cap of ${maxListenMs}ms fired, closing the pipe")
                endCapture("cap", releaseGate = true)
            }

            recognizer.startListening(
                recognizerIntent(expected)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ),
            )

            awaitClose {
                endCapture("flow closed", releaseGate = true)
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
        }
            // Unbounded: trySend must never drop the final result behind a backlog of level events.
            .buffer(Channel.UNLIMITED)
            .flowOn(Dispatchers.Main)

    private companion object {
        const val LABEL = "STT(piped)"
        const val SAMPLE_RATE_HZ = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val VAD_FRAME_SAMPLES = 320 // 20 ms at 16 kHz, a size the WebRTC VAD accepts
        const val VAD_FRAME_MS = 20
        const val FRAMES_PER_CHUNK = 3 // 60 ms reads
        const val VAD_SPEECH_MS = 50
        const val VAD_HANGOVER_MS = 20
        const val MAX_LISTEN_MS = 15_000L
        const val GATE_TIMEOUT_MS = 10_000L
        const val BYTES_PER_MS = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 1000
    }
}
