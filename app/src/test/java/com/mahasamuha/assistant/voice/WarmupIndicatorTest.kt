package com.mahasamuha.assistant.voice

import com.mahasamuha.assistant.voice.PanelState.Phase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupIndicatorTest {
    private val warming = PanelState(phase = Phase.Listening)

    @Test
    fun showsWhileWarmingUpAfterTheDelay() {
        assertTrue(showWarmupIndicator(warming, delayPassed = true))
    }

    @Test
    fun hiddenBeforeTheDelay() {
        assertFalse(showWarmupIndicator(warming, delayPassed = false))
    }

    @Test
    fun hiddenOnceTheEngineIsUp() {
        assertFalse(showWarmupIndicator(warming.copy(engineUp = true), delayPassed = true))
    }

    @Test
    fun hiddenOnceTextAppears() {
        assertFalse(showWarmupIndicator(warming.copy(hearing = "hello"), delayPassed = true))
    }

    @Test
    fun hiddenOutsideListening() {
        assertFalse(showWarmupIndicator(warming.copy(phase = Phase.Thinking), delayPassed = true))
    }
}
