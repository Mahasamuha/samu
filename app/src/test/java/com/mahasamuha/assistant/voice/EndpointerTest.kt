package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointerTest {
    private fun endpointer(waitSilenceMs: Int = 4_000) = Endpointer(frameMs = 20, waitSilenceMs = waitSilenceMs)

    private fun Endpointer.feed(
        speech: Boolean,
        frames: Int,
    ): EndReason? {
        var result: EndReason? = null
        repeat(frames) { onFrame(speech)?.let { result = it } }
        return result
    }

    @Test
    fun endsAfterShortSilenceOnceTextHeard() {
        val e = endpointer()
        e.feed(speech = true, frames = 10)
        e.onTextHeard()
        assertNull(e.feed(speech = false, frames = 39)) // 780 ms
        assertEquals(EndReason.Silence, e.onFrame(false)) // 800 ms
    }

    @Test
    fun speechResetsTheSilenceRunAfterText() {
        val e = endpointer()
        e.onTextHeard()
        e.feed(speech = true, frames = 5)
        assertNull(e.feed(speech = false, frames = 30))
        assertNull(e.onFrame(true))
        assertNull(e.feed(speech = false, frames = 39))
        assertEquals(EndReason.Silence, e.onFrame(false))
    }

    @Test
    fun vadSpeechWithoutTextDoesNotShortenTheWait() {
        // Noise flagged as speech at the start, then silence: the 800 ms rule must not apply without text.
        val e = endpointer()
        e.feed(speech = true, frames = 15)
        assertNull(e.feed(speech = false, frames = 199)) // 3 980 ms
        assertEquals(EndReason.NoSpeech, e.onFrame(false)) // 4 000 ms
    }

    @Test
    fun noVadSpeechEndsAtTheWait() {
        val e = endpointer(waitSilenceMs = 6_000)
        assertNull(e.feed(speech = false, frames = 299))
        assertEquals(EndReason.NoSpeech, e.onFrame(false))
        assertEquals(6_000, e.elapsedMs)
    }

    @Test
    fun speechResetsTheWaitBeforeText() {
        val e = endpointer()
        assertNull(e.feed(speech = false, frames = 150))
        assertNull(e.onFrame(true))
        assertNull(e.feed(speech = false, frames = 199))
        assertEquals(EndReason.NoSpeech, e.onFrame(false))
    }

    @Test
    fun textHeardMidSilenceSwitchesToTheShortRule() {
        val e = endpointer()
        e.feed(speech = false, frames = 20) // 400 ms
        e.onTextHeard()
        assertNull(e.feed(speech = false, frames = 19)) // run at 780 ms
        assertEquals(EndReason.Silence, e.onFrame(false))
    }

    @Test
    fun waitDependsOnListenKind() {
        assertEquals(6_000, Endpointer.waitSilenceMs(firstInSession = true))
        assertEquals(4_000, Endpointer.waitSilenceMs(firstInSession = false))
    }

    @Test
    fun reportsTheEndOnce() {
        val e = Endpointer(frameMs = 20, waitSilenceMs = 4_000, endSilenceMs = 40)
        e.onTextHeard()
        assertNull(e.onFrame(false))
        assertEquals(EndReason.Silence, e.onFrame(false))
        assertNull(e.onFrame(false))
    }

    @Test
    fun longSpeechNeverEndsByItself() {
        val e = endpointer()
        assertNull(e.feed(speech = true, frames = 2_000))
    }
}
