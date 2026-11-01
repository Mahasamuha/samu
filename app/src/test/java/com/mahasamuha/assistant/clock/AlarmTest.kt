package com.mahasamuha.assistant.clock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmTest {
    private fun at(
        hour: Int,
        minute: Int,
    ) = ZonedDateTime.of(2026, 10, 24, hour, minute, 0, 0, ZoneId.of("UTC"))

    private fun resolve(
        json: String,
        now: ZonedDateTime = at(12, 0),
    ) = resolveAlarm(JSONObject(json), now)

    private fun announce(
        hour: Int,
        minute: Int,
        now: ZonedDateTime = at(12, 0),
        extra: String = "",
    ) = resolve("""{"hour":$hour,"minute":$minute$extra}""", now).announcement

    @Test
    fun spokenTimeUsesTwelveHoursWithAmPm() {
        assertEquals("Alarm, 6:30 AM tomorrow.", announce(6, 30))
        assertEquals("Alarm, 7 AM tomorrow.", announce(7, 0))
        assertEquals("Alarm, 12 AM tomorrow.", announce(0, 0))
        assertEquals("Alarm, 12:05 AM tomorrow.", announce(0, 5))
        assertEquals("Alarm, 12 PM today.", announce(12, 0, now = at(11, 0)))
        assertEquals("Alarm, 1:09 PM today.", announce(13, 9))
        assertEquals("Alarm, 11:59 PM today.", announce(23, 59))
    }

    @Test
    fun aOneTimeAlarmSaysTodayOnlyWhenTheTimeIsStillAhead() {
        assertEquals("Alarm, 3 PM today.", announce(15, 0, now = at(14, 59)))
        assertEquals("Alarm, 3 PM tomorrow.", announce(15, 0, now = at(15, 1)))
        // Exactly now has passed.
        assertEquals("Alarm, 3 PM tomorrow.", announce(15, 0, now = at(15, 0)))
    }

    @Test
    fun theSameClockTimeInTheMorningAndEveningSoundsDifferent() {
        // The AM/PM slip is the failure to catch: 7 and 19 must never be announced alike.
        assertTrue(announce(7, 0).contains("7 AM"))
        assertTrue(announce(19, 0).contains("7 PM"))
    }

    @Test
    fun repeatingAlarmsNameTheirDaysInWeekOrder() {
        assertEquals("Alarm, 7 AM, weekdays.", announce(7, 0, extra = ""","days":["fri","mon","wed","tue","thu"]"""))
        assertEquals("Alarm, 7 AM, weekends.", announce(7, 0, extra = ""","days":["sun","sat"]"""))
        assertEquals("Alarm, 7 AM, every day.", announce(7, 0, extra = ""","days":["mon","tue","wed","thu","fri","sat","sun"]"""))
        assertEquals("Alarm, 7 AM, Mondays.", announce(7, 0, extra = ""","days":["mon"]"""))
        assertEquals("Alarm, 7 AM, Mondays and Thursdays.", announce(7, 0, extra = ""","days":["thu","mon"]"""))
        assertEquals("Alarm, 7 AM, Mondays, Wednesdays and Fridays.", announce(7, 0, extra = ""","days":["fri","mon","wed"]"""))
    }

    @Test
    fun aRepeatingAlarmDoesNotSayTodayOrTomorrow() {
        val text = announce(7, 0, extra = ""","days":["mon"]""")
        assertTrue(!text.contains("today") && !text.contains("tomorrow"))
    }

    @Test
    fun duplicateDaysCountOnce() {
        val alarm = resolve("""{"hour":7,"minute":0,"days":["mon","mon","tue"]}""")
        assertEquals(listOf("mon", "tue"), alarm.days.map { it.id })
    }

    @Test
    fun aLabelIsCapitalizedAndPrefixesTheAnnouncement() {
        assertEquals("Wake up alarm, 6:30 AM tomorrow.", announce(6, 30, extra = ""","label":"wake up""""))
        assertNull(resolve("""{"hour":6,"minute":30,"label":" "}""").label)
    }

    @Test
    fun rejectsAnUnknownDayNamingIt() {
        val e = assertThrows(IllegalArgumentException::class.java) { resolve("""{"hour":7,"minute":0,"days":["mon","funday"]}""") }
        assertTrue(e.message!!.startsWith("Unknown day: funday"))
    }

    @Test
    fun rejectsOutOfRangeAndMissingTimes() {
        for (json in listOf(
            """{"minute":0}""",
            """{"hour":7}""",
            """{"hour":24,"minute":0}""",
            """{"hour":-1,"minute":0}""",
            """{"hour":7,"minute":60}""",
            """{"hour":7,"minute":-1}""",
        )) {
            assertThrows(json, IllegalArgumentException::class.java) { resolve(json) }
        }
    }

    @Test
    fun acceptsTheLimits() {
        assertEquals("Alarm, 12 AM tomorrow.", announce(0, 0))
        assertEquals("Alarm, 11:59 PM today.", announce(23, 59))
    }

    @Test
    fun calendarDaysFollowJavaCalendarNumbering() {
        val days = resolve("""{"hour":7,"minute":0,"days":["sun","mon","sat"]}""").days
        // Calendar.MONDAY = 2 ... SATURDAY = 7, SUNDAY = 1.
        assertEquals(listOf(2, 7, 1), days.map { it.calendar })
    }
}
