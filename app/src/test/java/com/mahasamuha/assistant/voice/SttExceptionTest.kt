package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SttExceptionTest {
    @Test
    fun onlyNoMatchAndSpeechTimeoutAreNoSpeech() {
        // SpeechRecognizer.ERROR_SPEECH_TIMEOUT is 6 and ERROR_NO_MATCH is 7.
        assertEquals(setOf(6, 7), (0..13).filter { SttException(it).noSpeech }.toSet())
    }
}
