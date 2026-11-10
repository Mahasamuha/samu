package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointerTest {
    private fun Endpointer.feed(
        speech: Boolean,
        frames: Int,
    ): EndReason? {
        var result: EndReason? = null
        repeat(frames) { onFrame(speech)?.let { result = it } }
        return result
    }

    @Test
    fun endsAfterSilenceOnceSpeechSeen() {
        val e = Endpointer(frameMs = 20)
        assertNull(e.feed(speech = true, frames = 10))
        assertNull(e.feed(speech = false, frames = 39)) // 780 ms
        assertEquals(EndReason.Silence, e.onFrame(false)) // 800 ms
    }

    @Test
    fun speechResetsTheSilenceRun() {
        val e = Endpointer(frameMs = 20)
        e.feed(speech = true, frames = 5)
        assertNull(e.feed(speech = false, frames = 30))
        assertNull(e.onFrame(true))
        assertNull(e.feed(speech = false, frames = 39))
        assertEquals(EndReason.Silence, e.onFrame(false))
    }

    @Test
    fun endsWithNoSpeechAfterTheWindow() {
        val e = Endpointer(frameMs = 20)
        assertNull(e.feed(speech = false, frames = 299))
        assertEquals(EndReason.NoSpeech, e.onFrame(false))
        assertEquals(6_000, e.elapsedMs)
    }

    @Test
    fun silenceBeforeSpeechDoesNotEndEarly() {
        val e = Endpointer(frameMs = 20)
        assertNull(e.feed(speech = false, frames = 100)) // 2 s of nothing, past the 800 ms rule
    }

    @Test
    fun reportsTheEndOnce() {
        val e = Endpointer(frameMs = 20, endSilenceMs = 40)
        e.onFrame(true)
        assertNull(e.onFrame(false))
        assertEquals(EndReason.Silence, e.onFrame(false))
        assertNull(e.onFrame(false))
    }

    @Test
    fun longSpeechNeverEndsByItself() {
        val e = Endpointer(frameMs = 20)
        assertNull(e.feed(speech = true, frames = 2_000))
    }
}
