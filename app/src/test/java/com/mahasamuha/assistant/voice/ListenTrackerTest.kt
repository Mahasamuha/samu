package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTrackerTest {
    private var now = 1_000L
    private val tracker = ListenTracker("STT") { now }

    @Test
    fun offsetsComeFromTheInjectedClock() {
        now = 1_340
        assertEquals(340L, tracker.offsetMs())
        now = 2_000
        assertEquals(1_000L, tracker.offsetMs())
    }

    @Test
    fun stampPrefixesLabelAndOffset() {
        now = 1_340
        assertEquals("STT +340ms ready", tracker.stamp("ready"))
    }

    @Test
    fun eachEventIsFirstOnlyOnce() {
        assertTrue(tracker.isFirst("partial"))
        assertFalse(tracker.isFirst("partial"))
        assertTrue(tracker.isFirst("level"))
    }
}
