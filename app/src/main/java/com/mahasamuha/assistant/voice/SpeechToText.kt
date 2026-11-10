package com.mahasamuha.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

sealed interface SttEvent {
    data class Partial(
        val text: String,
    ) : SttEvent

    data class Final(
        val text: String,
    ) : SttEvent

    /** Microphone loudness, normalised 0..1, for the panel's level indicator. Callers that don't show one ignore it. */
    data class Level(
        val value: Float,
    ) : SttEvent

    /** The recognizer is up: its first level callback arrived. Once per listen; collectors that don't care ignore it. */
    data object EngineUp : SttEvent
}

class SttException(
    val code: Int,
) : Exception("Speech recognition error $code") {
    val noSpeech get() = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
}

/** No on-device recognizer; the platform's default one may send audio off the phone, so it isn't used. */
class OnDeviceSttUnavailableException : Exception("On-device speech recognition is unavailable")

/** What a listen is for: the session's first request, a later reply or follow-up, or a yes/no confirmation. */
enum class ListenKind { First, Reply, Confirmation }

/**
 * One utterance per [listen]: [SttEvent.Level]s and partial results, then a single [SttEvent.Final], then completion.
 * [expected] are words or phrases to favor (e.g. yes/no while confirming); implementations may ignore it.
 * [kind] says what is being listened for; an implementation that endpoints itself may wait less for a reply and
 * more for a confirmation. Implementations may ignore it.
 */
interface SpeechToText {
    fun listen(
        expected: List<String> = emptyList(),
        kind: ListenKind = ListenKind.First,
    ): Flow<SttEvent>
}

/** The platform's on-device recognizer, with no fallback to the default one. Must be driven from the main thread. */
class PlatformSpeechToText(
    private val context: Context,
) : SpeechToText {
    override fun listen(
        expected: List<String>,
        kind: ListenKind,
    ): Flow<SttEvent> =
        callbackFlow {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                Log.w(TAG, "STT: on-device recognition unavailable")
                throw OnDeviceSttUnavailableException()
            }
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val tracker = ListenTracker(SystemClock::elapsedRealtime)

            recognizer.setRecognitionListener(recognitionListener("STT", tracker))

            recognizer.startListening(recognizerIntent(expected))

            awaitClose {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
            // Unbounded: trySend must never drop the final result behind a backlog of level events.
            .buffer(Channel.UNLIMITED)
            .flowOn(Dispatchers.Main)
}

/**
 * Listener shared by the recognizer-backed [SpeechToText] implementations: logs timings under [label],
 * forwards partials, and ends the flow on a result or error (with the last-partial fallback from [tracker]).
 * With a [segments] transcript the session is segmented (piped audio): results are collected per segment and the
 * flow ends with their join when the session ends; partials show the earlier segments too. The recognizer's own
 * level callbacks are forwarded as [SttEvent.Level] only when not segmented (the piped source measures its own audio).
 * [onEnd] runs just before the flow is closed, for cleanup that must happen as soon as the recognizer is done.
 * [onText] runs on the first non-blank partial or segment (any thread's worth of care is the caller's).
 * The first `onRmsChanged` also sends [SttEvent.EngineUp]. [onRms] runs on every `onRmsChanged` (main thread), after the first-level log.
 */
internal fun ProducerScope<SttEvent>.recognitionListener(
    label: String,
    tracker: ListenTracker,
    segments: SegmentedTranscript? = null,
    onEnd: () -> Unit = {},
    onText: () -> Unit = {},
    onRms: (Float) -> Unit = {},
): RecognitionListener =
    object : RecognitionListener {
        private var engineUp = false

        override fun onPartialResults(partialResults: Bundle) {
            firstResult(partialResults)?.let {
                tracker.onPartial(it)?.let { ms -> Log.i(TAG, "$label +${ms}ms first partial") }
                onText()
                trySend(SttEvent.Partial(segments?.onPartial(it) ?: it))
            }
        }

        override fun onResults(results: Bundle) {
            if (segments != null) {
                // Segmented sessions end in onEndOfSegmentedSession; a plain result is just one more segment.
                addSegment(results)
                return
            }
            Log.i(TAG, "$label +${tracker.offsetMs()}ms final result")
            onEnd()
            trySend(SttEvent.Final(firstResult(results).orEmpty()))
            close()
        }

        override fun onSegmentResults(segmentResults: Bundle) = addSegment(segmentResults)

        override fun onEndOfSegmentedSession() {
            val transcript = segments ?: return
            Log.i(TAG, "$label +${tracker.offsetMs()}ms end of segmented session, ${transcript.segmentCount} segments")
            onEnd()
            trySend(SttEvent.Final(transcript.joined()))
            close()
        }

        private fun addSegment(bundle: Bundle) {
            val transcript = segments ?: return
            val text = firstResult(bundle).orEmpty()
            transcript.onSegment(text)
            if (text.isNotBlank()) onText()
            Log.i(TAG, "$label +${tracker.offsetMs()}ms segment result #${transcript.segmentCount}, ${text.length} chars")
        }

        override fun onError(error: Int) {
            val outcome = tracker.onError(error)
            // Segmented: the segments so far plus the unfinished partial, not just the last partial.
            val rescued =
                if (segments == null) {
                    outcome.rescuedText
                } else {
                    segments.pendingText().takeIf { ListenTracker.isNoSpeech(error) && it.isNotEmpty() }
                }
            Log.i(TAG, "$label +${outcome.offsetMs}ms error $error, partial used: ${rescued != null}")
            onEnd()
            if (rescued != null) {
                trySend(SttEvent.Final(rescued))
                close()
            } else {
                close(SttException(error))
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "$label +${tracker.offsetMs()}ms ready")
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "$label +${tracker.offsetMs()}ms beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            tracker.onLevel()?.let { ms ->
                val what = if (segments == null) "first level" else "recognizer up (first rms)"
                Log.i(TAG, "$label +${ms}ms $what")
            }
            if (!engineUp) {
                engineUp = true
                trySend(SttEvent.EngineUp)
            }
            onRms(rmsdB)
            if (segments == null) trySend(SttEvent.Level(InputLevel.fromRecognizerDb(rmsdB)))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            Log.i(TAG, "$label +${tracker.offsetMs()}ms end of speech")
        }

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }

/** The intent both implementations start the recognizer with. */
internal fun recognizerIntent(expected: List<String>): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .apply {
            // Biasing helps short answers like a lone "no", which otherwise often come back as no match.
            if (expected.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(expected))
        }

private fun firstResult(bundle: Bundle): String? =
    bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
