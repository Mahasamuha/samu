package com.mahasamuha.assistant.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class AudioPumpTest {
    private class RecordingSink : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    @Test
    fun framesOfferedBeforeRunAreKeptInOrder() =
        runBlocking {
            val sink = RecordingSink()
            val pump = AudioPump(sink, IDLE, pace = { delay(1) })
            pump.offer(byteArrayOf(1, 2))
            pump.offer(byteArrayOf(3))
            pump.openGate()
            pump.finish()
            pump.run()
            assertArrayEquals(byteArrayOf(1, 2, 3), sink.toByteArray())
            assertEquals(3L, pump.realBytesWritten)
            assertEquals(0L, pump.silenceBytesWritten)
            assertTrue(sink.closed)
        }

    @Test
    fun offerAfterFinishIsRejected() {
        val pump = AudioPump(RecordingSink(), IDLE, pace = { delay(1) })
        pump.finish()
        assertFalse(pump.offer(byteArrayOf(1)))
    }

    @Test
    fun sinkIsClosedWhenWriteFails() =
        runBlocking {
            var closed = false
            val sink =
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("broken pipe")

                    override fun close() {
                        closed = true
                    }
                }
            val pump = AudioPump(sink, IDLE, pace = { delay(1) })
            pump.offer(byteArrayOf(1))
            pump.finish()
            try {
                pump.run()
                throw AssertionError("expected IOException")
            } catch (_: IOException) {
                // expected
            }
            assertTrue(closed)
            assertEquals(0L, pump.realBytesWritten)
        }

    @Test
    fun closedGateSendsPacedSilenceAndKeepsRealAudioQueued() =
        runBlocking {
            val sink = RecordingSink()
            val pump = AudioPump(sink, IDLE, pace = { delay(1) })
            val job = launch(Dispatchers.Default) { pump.run() }
            pump.offer(byteArrayOf(1, 2, 3, 4))
            pump.offer(byteArrayOf(5, 6))
            waitUntil { pump.silenceBytesWritten == 6L }
            assertEquals(0L, pump.realBytesWritten)
            assertEquals(6L, pump.openGate())
            pump.offer(byteArrayOf(7))
            pump.finish()
            job.join()
            val out = sink.toByteArray()
            assertArrayEquals(ByteArray(6) + byteArrayOf(1, 2, 3, 4, 5, 6, 7), out)
            assertEquals(7L, pump.realBytesWritten)
            assertEquals(6L, pump.silenceBytesWritten)
            assertTrue(sink.closed)
        }

    @Test
    fun finishWithClosedGateKeepsSendingSilenceUntilTheGateOpens() =
        runBlocking {
            val sink = RecordingSink()
            val pump = AudioPump(sink, IDLE, pace = { delay(1) })
            val job = launch(Dispatchers.Default) { pump.run() }
            pump.offer(byteArrayOf(1, 2))
            pump.finish()
            // Owed silence for the real bytes, then idle chunks while the gate stays closed.
            waitUntil { pump.silenceBytesWritten >= 2L + 3 * IDLE }
            assertFalse(sink.closed)
            assertEquals(0L, pump.realBytesWritten)
            pump.openGate()
            job.join()
            val out = sink.toByteArray()
            assertArrayEquals(byteArrayOf(1, 2), out.copyOfRange(out.size - 2, out.size))
            assertTrue(out.copyOfRange(0, out.size - 2).all { it == 0.toByte() })
            assertEquals(2L, pump.realBytesWritten)
            assertTrue(sink.closed)
        }

    @Test
    fun openGateReportsQueuedOnlyOnce() {
        val pump = AudioPump(RecordingSink(), IDLE, pace = { delay(1) })
        pump.offer(ByteArray(10))
        assertEquals(10L, pump.openGate())
        assertNull(pump.openGate())
    }

    private companion object {
        const val IDLE = 4
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }
}
