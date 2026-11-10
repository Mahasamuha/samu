package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTrackerTest {
    private var now = 1_000L
    private val tracker = ListenTracker { now }

    @Test
    fun rescuesLastPartialOnNoMatch() {
        tracker.onPartial("what is")
        assertEquals("what is", tracker.onError(7).rescuedText)
    }

    @Test
    fun noRescueOnNoMatchWithoutPartials() {
        assertNull(tracker.onError(7).rescuedText)
    }

    @Test
    fun blankPartialsAreIgnored() {
        tracker.onPartial("")
        tracker.onPartial("   ")
        assertNull(tracker.onError(7).rescuedText)
    }

    @Test
    fun blankPartialDoesNotReplaceEarlierOne() {
        tracker.onPartial("hello")
        tracker.onPartial(" ")
        assertEquals("hello", tracker.onError(7).rescuedText)
    }

    @Test
    fun otherErrorsAreNotRescued() {
        tracker.onPartial("hello")
        listOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13).forEach {
            assertNull("code $it", tracker.onError(it).rescuedText)
        }
    }

    @Test
    fun lastPartialWins() {
        tracker.onPartial("what")
        tracker.onPartial("what is the")
        tracker.onPartial("what is the weather")
        assertEquals("what is the weather", tracker.onError(7).rescuedText)
    }

    @Test
    fun offsetsComeFromTheInjectedClock() {
        now = 1_340
        assertEquals(340L, tracker.offsetMs())
        now = 2_000
        assertEquals(1_000L, tracker.onError(3).offsetMs)
    }

    @Test
    fun firstPartialOffsetIsReportedOnce() {
        now = 1_500
        assertNull(tracker.onPartial(" "))
        assertEquals(500L, tracker.onPartial("hi"))
        now = 1_900
        assertNull(tracker.onPartial("hi there"))
    }
}
