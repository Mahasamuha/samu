package com.mahasamuha.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

            recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onPartialResults(partialResults: Bundle) {
                        firstResult(partialResults)?.let { trySend(SttEvent.Partial(it)) }
                    }

                    override fun onResults(results: Bundle) {
                        trySend(SttEvent.Final(firstResult(results).orEmpty()))
                        close()
                    }

                    override fun onError(error: Int) {
                        Log.i(TAG, "STT error $error")
                        close(SttException(error))
                    }

                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.i(TAG, "STT ready")
                    }

                    override fun onBeginningOfSpeech() = Unit

                    override fun onRmsChanged(rmsdB: Float) = Unit

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() = Unit

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?,
                    ) = Unit
                },
            )

            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .apply {
                        // Biasing helps short answers like a lone "no", which otherwise often come back as no match.
                        if (expected.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(expected))
                    },
            )

            awaitClose {
                recognizer.cancel()
                recognizer.destroy()
            }
        }.flowOn(Dispatchers.Main)

    private fun firstResult(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
