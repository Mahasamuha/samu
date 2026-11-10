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
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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
}

class SttException(
    val code: Int,
) : Exception("Speech recognition error $code") {
    val noSpeech get() = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
}

/** No on-device recognizer; the platform's default one may send audio off the phone, so it isn't used. */
class OnDeviceSttUnavailableException : Exception("On-device speech recognition is unavailable")

/**
 * One utterance per [listen]: partial results, then a single [SttEvent.Final], then completion.
 * [expected] are words or phrases to favor (e.g. yes/no while confirming); implementations may ignore it.
 */
interface SpeechToText {
    fun listen(expected: List<String> = emptyList()): Flow<SttEvent>
}

/** The platform's on-device recognizer, with no fallback to the default one. Must be driven from the main thread. */
class PlatformSpeechToText(
    private val context: Context,
) : SpeechToText {
    override fun listen(expected: List<String>): Flow<SttEvent> =
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
        }.flowOn(Dispatchers.Main)
}

/**
 * Listener shared by the recognizer-backed [SpeechToText] implementations: logs timings under [label],
 * forwards partials, and ends the flow on a result or error (with the last-partial fallback from [tracker]).
 * [onEnd] runs just before the flow is closed, for cleanup that must happen as soon as the recognizer is done.
 */
internal fun ProducerScope<SttEvent>.recognitionListener(
    label: String,
    tracker: ListenTracker,
    onEnd: () -> Unit = {},
): RecognitionListener =
    object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
            firstResult(partialResults)?.let {
                tracker.onPartial(it)?.let { ms -> Log.i(TAG, "$label +${ms}ms first partial") }
                trySend(SttEvent.Partial(it))
            }
        }

        override fun onResults(results: Bundle) {
            Log.i(TAG, "$label +${tracker.offsetMs()}ms final result")
            onEnd()
            trySend(SttEvent.Final(firstResult(results).orEmpty()))
            close()
        }

        override fun onError(error: Int) {
            val outcome = tracker.onError(error)
            Log.i(TAG, "$label +${outcome.offsetMs}ms error $error, partial used: ${outcome.rescuedText != null}")
            onEnd()
            if (outcome.rescuedText != null) {
                trySend(SttEvent.Final(outcome.rescuedText))
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
            tracker.onLevel()?.let { ms -> Log.i(TAG, "$label +${ms}ms first level") }
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
