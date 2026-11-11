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
    /** The recognizer heard nothing it could match. The one definition of "no speech" (a rescuable, quiet failure). */
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
            val tracker = ListenTracker("STT", SystemClock::elapsedRealtime)
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                tracker.logWarning("on-device recognition unavailable")
                throw OnDeviceSttUnavailableException()
            }
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

            recognizer.setRecognitionListener(PlainListener(this, tracker))

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

/** Logs [message] with the listen's label and the time since it started. */
internal fun ListenTracker.log(message: String) {
    Log.i(TAG, stamp(message))
}

internal fun ListenTracker.logWarning(
    message: String,
    cause: Throwable? = null,
) {
    Log.w(TAG, stamp(message), cause)
}

/** Logs "first [event]" the first time it is called for that [event]; returns whether it was the first. */
internal fun ListenTracker.logFirst(event: String): Boolean = isFirst(event).also { if (it) log("first $event") }

/**
 * What the recognizer-backed listeners share: timing logs, [SttEvent.EngineUp] on the first level callback, and
 * ending the flow when the recognizer errors. A no-speech error ([SttException.noSpeech]) with [pendingText]
 * available ends in a [SttEvent.Final] of that text; any other error fails the flow with an [SttException].
 * [onEnd] runs just before the flow is closed, for cleanup that must happen as soon as the recognizer is done.
 */
internal abstract class SttListener(
    private val events: ProducerScope<SttEvent>,
    protected val tracker: ListenTracker,
    private val onEnd: () -> Unit = {},
) : RecognitionListener {
    /** The text to fall back on when the recognizer ends without a result; blank when there is none. */
    protected abstract fun pendingText(): String

    /** Runs on every `onRmsChanged` (main thread), after the first-level handling. */
    protected open fun onRecognizerLevel(rmsdB: Float) = Unit

    protected fun send(event: SttEvent) {
        events.trySend(event)
    }

    /** Ends the flow with [text] as the final result. */
    protected fun finish(text: String) {
        onEnd()
        events.trySend(SttEvent.Final(text))
        events.close()
    }

    override fun onError(error: Int) {
        val failure = SttException(error)
        val rescued = pendingText().takeIf { failure.noSpeech && it.isNotEmpty() }
        tracker.log("error $error, partial used: ${rescued != null}")
        if (rescued != null) {
            finish(rescued)
        } else {
            onEnd()
            events.close(failure)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = tracker.log("ready")

    override fun onBeginningOfSpeech() = tracker.log("beginning of speech")

    override fun onEndOfSpeech() = tracker.log("end of speech")

    override fun onRmsChanged(rmsdB: Float) {
        if (tracker.logFirst("recognizer level")) send(SttEvent.EngineUp)
        onRecognizerLevel(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEvent(
        eventType: Int,
        params: Bundle?,
    ) = Unit
}

/**
 * Listener for [PlatformSpeechToText]: one result per listen. Partials are forwarded, the last one stands in for
 * the result when the recognizer ends with no match, and the recognizer's own levels become [SttEvent.Level].
 */
private class PlainListener(
    events: ProducerScope<SttEvent>,
    tracker: ListenTracker,
) : SttListener(events, tracker) {
    private var lastPartial = ""

    override fun pendingText() = lastPartial

    override fun onPartialResults(partialResults: Bundle) {
        firstResult(partialResults)?.let {
            tracker.logFirst("partial")
            lastPartial = it
            send(SttEvent.Partial(it))
        }
    }

    override fun onResults(results: Bundle) {
        tracker.log("final result")
        finish(firstResult(results).orEmpty())
    }

    override fun onRecognizerLevel(rmsdB: Float) = send(SttEvent.Level(InputLevel.fromRecognizerDb(rmsdB)))
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

internal fun firstResult(bundle: Bundle): String? =
    bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
