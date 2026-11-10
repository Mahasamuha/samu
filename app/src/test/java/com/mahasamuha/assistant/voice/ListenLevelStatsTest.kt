package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenLevelStatsTest {
    @Test
    fun emptyHasNoStats() {
        val stats = ListenLevelStats()
        assertNull(stats.peakDbfs())
        assertNull(stats.percentileDbfs(90))
        assertTrue(stats.summary().contains("recognizer dB none"))
    }

    @Test
    fun peakAndNearestRankPercentile() {
        val stats = ListenLevelStats()
        (1..10).forEach { stats.onChunk(-it * 10.0) } // -10 .. -100
        assertEquals(-10.0, stats.peakDbfs()!!, 0.0)
        // Sorted ascending -100..-10; rank ceil(0.9 * 10) = 9 is -20.
        assertEquals(-20.0, stats.percentileDbfs(90)!!, 0.0)
    }

    @Test
    fun recognizerRangeAppearsInSummary() {
        val stats = ListenLevelStats()
        stats.onRecognizerDb(-2f)
        stats.onRecognizerDb(7.5f)
        stats.onRecognizerDb(3f)
        assertTrue(stats.summary().contains("min -2.0, max 7.5"))
    }
}
