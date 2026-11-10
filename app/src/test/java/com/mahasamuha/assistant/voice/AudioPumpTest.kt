package com.mahasamuha.assistant.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val pump = AudioPump(sink)
            pump.offer(byteArrayOf(1, 2))
            pump.offer(byteArrayOf(3))
            pump.finish()
            pump.run()
            assertArrayEquals(byteArrayOf(1, 2, 3), sink.toByteArray())
            assertEquals(3L, pump.bytesWritten)
            assertTrue(sink.closed)
        }

    @Test
    fun offerAfterFinishIsRejected() {
        val pump = AudioPump(RecordingSink())
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
            val pump = AudioPump(sink)
            pump.offer(byteArrayOf(1))
            pump.finish()
            try {
                pump.run()
                throw AssertionError("expected IOException")
            } catch (_: IOException) {
                // expected
            }
            assertTrue(closed)
            assertEquals(0L, pump.bytesWritten)
        }
}
