package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentedTranscriptTest {
    @Test
    fun partialsShowPreviousSegmentsThenCurrent() {
        val t = SegmentedTranscript()
        assertEquals("what is", t.onPartial("what is"))
        t.onSegment("what is the weather")
        assertEquals("what is the weather and", t.onPartial("and"))
        assertEquals("what is the weather and tomorrow", t.onPartial("and tomorrow"))
    }

    @Test
    fun segmentsJoinInOrderAndBlanksAreDropped() {
        val t = SegmentedTranscript()
        t.onSegment("one")
        t.onSegment("  ")
        t.onSegment("")
        t.onSegment(" two ")
        assertEquals("one two", t.joined())
        assertEquals(2, t.segmentCount)
    }

    @Test
    fun finishedSegmentSupersedesItsPartial() {
        val t = SegmentedTranscript()
        t.onPartial("hello wor")
        t.onSegment("hello world")
        assertEquals("hello world", t.pendingText())
    }

    @Test
    fun pendingTextCoversSegmentsAndUnfinishedPartial() {
        val t = SegmentedTranscript()
        t.onSegment("first")
        t.onPartial("second")
        assertEquals("first second", t.pendingText())
        assertEquals("first", t.joined())
    }

    @Test
    fun emptyTranscriptIsEmpty() {
        val t = SegmentedTranscript()
        assertEquals("", t.joined())
        assertEquals("", t.pendingText())
    }
}
