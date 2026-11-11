package com.mahasamuha.assistant.voice

import com.mahasamuha.assistant.voice.PanelState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenStageTest {
    private val listening = PanelState(phase = Phase.Listening)

    @Test
    fun waitingBeforeTheDelayWhileTheEngineIsDown() {
        assertEquals(ListenStage.Waiting, listenStage(listening, delayPassed = false))
    }

    @Test
    fun warmupAfterTheDelayWhileTheEngineIsDown() {
        assertEquals(ListenStage.Warmup, listenStage(listening, delayPassed = true))
    }

    @Test
    fun hearingOnceTheEngineIsUpWithNoText() {
        assertEquals(ListenStage.Hearing, listenStage(listening.copy(engineUp = true), delayPassed = false))
        assertEquals(ListenStage.Hearing, listenStage(listening.copy(engineUp = true), delayPassed = true))
    }

    @Test
    fun transcribingOnceTextAppears() {
        val withText = listening.copy(hearing = "hello")
        assertEquals(ListenStage.Transcribing, listenStage(withText, delayPassed = true))
        assertEquals(ListenStage.Transcribing, listenStage(withText, delayPassed = false))
        assertEquals(ListenStage.Transcribing, listenStage(withText.copy(engineUp = true), delayPassed = true))
    }

    @Test
    fun noStageOutsideListening() {
        listOf(Phase.Starting, Phase.Thinking, Phase.Speaking, Phase.Idle).forEach {
            assertNull("$it", listenStage(listening.copy(phase = it, engineUp = true, hearing = "x"), delayPassed = true))
        }
    }
}
