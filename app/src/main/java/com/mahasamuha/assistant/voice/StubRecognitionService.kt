package com.mahasamuha.assistant.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Placeholder so the settings picker lists the app as an assistant. Real STT
 * uses the platform's on-device recognizer, not this service.
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent,
        listener: Callback,
    ) {
        Log.i(TAG, "StubRecognitionService.onStartListening (not implemented)")
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
