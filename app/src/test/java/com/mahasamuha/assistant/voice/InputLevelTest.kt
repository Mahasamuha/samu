package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputLevelTest {
    @Test
    fun silenceIsZero() {
        assertEquals(0f, InputLevel.fromPcm(ShortArray(320)), 0f)
        assertEquals(0f, InputLevel.fromPcm(ShortArray(0)), 0f)
    }

    @Test
    fun loudSignalClampsToOne() {
        assertEquals(1f, InputLevel.fromPcm(ShortArray(320) { Short.MAX_VALUE }), 0f)
    }

    @Test
    fun louderIsHigherAndStaysInRange() {
        val quiet = InputLevel.fromPcm(ShortArray(320) { 100 })
        val speech = InputLevel.fromPcm(ShortArray(320) { 1_000 })
        assertTrue(quiet in 0f..1f)
        assertTrue(speech > quiet)
        assertTrue(speech < 1f)
    }

    @Test
    fun midScaleDbMapsToHalf() {
        // -42.5 dBFS is halfway between the -60 floor and -25 ceiling.
        val amplitude = (32_768 * Math.pow(10.0, -42.5 / 20)).toInt().toShort()
        assertEquals(0.5f, InputLevel.fromPcm(ShortArray(320) { amplitude }), 0.01f)
    }

    @Test
    fun countLimitsTheSamplesRead() {
        val samples = ShortArray(320) { if (it < 160) 0 else Short.MAX_VALUE }
        assertEquals(0f, InputLevel.fromPcm(samples, count = 160), 0f)
    }

    @Test
    fun recognizerDbIsClamped() {
        assertEquals(0f, InputLevel.fromRecognizerDb(-2f), 0f)
        assertEquals(0f, InputLevel.fromRecognizerDb(-10f), 0f)
        assertEquals(1f, InputLevel.fromRecognizerDb(10f), 0f)
        assertEquals(1f, InputLevel.fromRecognizerDb(20f), 0f)
        assertEquals(0.5f, InputLevel.fromRecognizerDb(4f), 0.001f)
    }

    @Test
    fun rmsDbfsOfFullScaleIsZeroAndSilenceIsFloor() {
        assertEquals(0.0, InputLevel.rmsDbfs(ShortArray(320) { Short.MAX_VALUE }), 0.01)
        assertEquals(InputLevel.SILENCE_DBFS, InputLevel.rmsDbfs(ShortArray(320)), 0.0)
    }

    @Test
    fun rangeEndsMapToZeroAndOne() {
        assertEquals(0f, InputLevel.fromDbfs(-60.0), 0f)
        assertEquals(1f, InputLevel.fromDbfs(-25.0), 0f)
        assertEquals(0f, InputLevel.fromDbfs(-80.0), 0f)
    }
}
