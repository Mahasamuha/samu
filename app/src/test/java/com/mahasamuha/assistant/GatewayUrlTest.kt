package com.mahasamuha.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayUrlTest {
    private fun assertRejected(vararg inputs: String) =
        inputs.forEach {
            try {
                parseGatewayUrl(it)
                fail("\"$it\" should be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }
        }

    @Test
    fun blankIsRejected() {
        assertRejected("", "   ")
    }

    @Test
    fun acceptsHttpsUrls() {
        assertEquals("https://gateway.example.test", parseGatewayUrl("https://gateway.example.test"))
        assertEquals("https://gateway.example.test:8443", parseGatewayUrl("https://gateway.example.test:8443"))
        assertEquals("https://gateway.example.test/samu", parseGatewayUrl("https://gateway.example.test/samu"))
    }

    @Test
    fun normalizes() {
        assertEquals("https://gateway.example.test", parseGatewayUrl("  https://gateway.example.test/  "))
        assertEquals("https://gateway.example.test/samu", parseGatewayUrl("https://gateway.example.test/samu//"))
        assertEquals("https://gateway.example.test", parseGatewayUrl("HTTPS://Gateway.Example.TEST"))
    }

    @Test
    fun rejectsOtherSchemesAndMissingHosts() {
        assertRejected("http://gateway.example.test", "gateway.example.test", "ftp://gateway.example.test", "https://", "https:///path")
    }

    @Test
    fun rejectsQueryFragmentAndCredentials() {
        assertRejected(
            "https://gateway.example.test?x=1",
            "https://gateway.example.test/?",
            "https://gateway.example.test#top",
            "https://gateway.example.test/#",
            "https://user:pw@gateway.example.test",
        )
    }

    @Test
    fun rejectsMalformedUrls() {
        assertRejected("https://gateway example.test", "https://[bad")
    }

    @Test
    fun gatewayListIsTheSavedUrl() {
        assertEquals(listOf("https://dev.example.test"), gatewayUrls("https://dev.example.test"))
    }

    @Test(expected = GatewayNotSetException::class)
    fun noGatewayWithoutASavedUrl() {
        gatewayUrls(null)
    }

    @Test
    fun onlyADifferentUrlChangesBackend() {
        val dev = "https://dev.example.test"
        assertTrue(changesBackend(null, dev))
        assertTrue(changesBackend(dev, "https://other.example.test"))
        assertFalse(changesBackend(dev, dev))
    }
}
