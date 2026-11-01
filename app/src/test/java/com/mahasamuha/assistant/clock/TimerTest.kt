package com.mahasamuha.assistant.clock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerTest {
    private fun resolve(json: String) = resolveTimer(JSONObject(json))

    @Test
    fun announcesTheResolvedDuration() {
        assertEquals("Timer, 10 minutes.", resolve("""{"duration_seconds":600}""").announcement)
        assertEquals("Timer, 1 second.", resolve("""{"duration_seconds":1}""").announcement)
        assertEquals("Timer, 1 hour 30 minutes.", resolve("""{"duration_seconds":5400}""").announcement)
        assertEquals("Timer, 1 minute 1 second.", resolve("""{"duration_seconds":61}""").announcement)
        assertEquals("Timer, 24 hours.", resolve("""{"duration_seconds":86400}""").announcement)
    }

    @Test
    fun spokenDurationLeavesOutZeroParts() {
        assertEquals("1 hour 5 seconds", spokenDuration(3605))
        assertEquals("2 hours", spokenDuration(7200))
        assertEquals("59 seconds", spokenDuration(59))
    }

    @Test
    fun aLabelIsCapitalizedInTheAnnouncementAndTrimmed() {
        val timer = resolve("""{"duration_seconds":600,"label":"  pasta "}""")
        assertEquals("pasta", timer.label)
        assertEquals("Pasta timer, 10 minutes.", timer.announcement)
    }

    @Test
    fun aBlankLabelIsNoLabel() {
        val timer = resolve("""{"duration_seconds":600,"label":"   "}""")
        assertNull(timer.label)
        assertEquals("Timer, 10 minutes.", timer.announcement)
    }

    @Test
    fun aLongLabelIsCappedAtFortyCharacters() {
        val timer = resolve("""{"duration_seconds":60,"label":"${"a".repeat(60)}"}""")
        assertEquals(40, timer.label!!.length)
    }

    @Test
    fun cleanLabelTrimsAfterCapping() {
        assertEquals("a".repeat(39), cleanLabel("a".repeat(39) + " bbbbbb"))
        assertNull(cleanLabel(""))
    }

    @Test
    fun rejectsMissingZeroNegativeAndOverlongDurations() {
        for (json in listOf("{}", """{"duration_seconds":0}""", """{"duration_seconds":-5}""", """{"duration_seconds":86401}""", """{"duration_seconds":"soon"}""")) {
            val e = assertThrows(IllegalArgumentException::class.java) { resolve(json) }
            assertTrue(e.message!!.startsWith("duration_seconds must be between 1 and 86400"))
        }
    }

    @Test
    fun acceptsTheLimits() {
        assertEquals(1, resolve("""{"duration_seconds":1}""").seconds)
        assertEquals(86_400, resolve("""{"duration_seconds":86400}""").seconds)
    }

    @Test
    fun aNumericStringIsAccepted() {
        // org.json coerces "90" to 90; documented here because the model sometimes sends numbers as strings.
        assertEquals(90, resolve("""{"duration_seconds":"90"}""").seconds)
    }
}
