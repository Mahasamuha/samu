package com.mahasamuha.assistant.voice

import com.mahasamuha.assistant.capabilities.Capability
import com.mahasamuha.assistant.capabilities.Load
import com.mahasamuha.assistant.skills.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class VoicePromptTest {
    private val now = ZonedDateTime.of(2026, 10, 24, 15, 5, 0, 0, ZoneId.of("UTC"))

    private fun lazy(
        id: String,
        description: String = "$id description",
    ) = Capability(id, id, description, Load.LAZY, emptyList())

    private fun prompt(
        skills: List<Skill> = emptyList(),
        capabilities: List<Capability> = emptyList(),
        location: ApproximateLocation? = null,
    ) = VoicePrompt.system(skills, capabilities, location, now)

    @Test
    fun startsWithTheBaseRulesAndNamesSamu() {
        val text = prompt()
        assertTrue(text.startsWith("You are Samu, a voice assistant"))
        assertTrue(text.contains("# Speaking"))
        assertTrue(text.contains("# Tools and actions"))
    }

    @Test
    fun theUseCapabilityRuleIsAlwaysPresent() {
        assertTrue(prompt().contains("call use_capability first"))
        assertTrue(prompt(capabilities = listOf(lazy("search"))).contains("call use_capability first"))
    }

    @Test
    fun sectionsAreOmittedWhenEmpty() {
        val text = prompt()
        assertFalse(text.contains("# Skills"))
        assertFalse(text.contains("# Capabilities\n"))
        assertTrue(text.contains("# Context"))
    }

    @Test
    fun eagerSkillsAreSortedByNameAndRenderedAsSections() {
        val text =
            prompt(skills = listOf(Skill("zeta", "z desc", instructions = "Z body"), Skill("alpha", "a desc", instructions = "A body")))
        assertTrue(text.contains("# Skills"))
        assertTrue(text.indexOf("## Skill: alpha") in 0 until text.indexOf("## Skill: zeta"))
        assertTrue(text.contains("## Skill: alpha\na desc\n\nA body"))
    }

    @Test
    fun lazyCapabilitiesAreListedSortedByIdWithDescriptions() {
        val text = prompt(capabilities = listOf(lazy("weather", "Forecasts"), lazy("calendar", "Events")))
        assertTrue(text.contains("# Capabilities\n\n- calendar: Events\n- weather: Forecasts"))
    }

    @Test
    fun sectionOrderIsBaseSkillsCapabilitiesContext() {
        val text = prompt(skills = listOf(Skill("s", "d", instructions = "i")), capabilities = listOf(lazy("c")))
        val order = listOf("# Speaking", "# Skills", "# Capabilities", "# Context").map { text.indexOf(it) }
        assertEquals(order.sorted(), order)
        assertTrue(order.all { it >= 0 })
    }

    @Test
    fun aKnownLocationIsDescribedByCityAndRegionOnly() {
        val here = ApproximateLocation("Springfield", "Oregon", "US", 44.0, -123.0)
        val text = prompt(location = here)
        assertTrue(text.contains("The user is near Springfield, Oregon."))
        assertFalse(text.contains("44.0"))
        assertFalse(text.contains("unknown"))
    }

    @Test
    fun aLocationWithNoNamesIsTreatedAsUnknown() {
        val text = prompt(location = ApproximateLocation(null, null, "US", 44.0, -123.0))
        assertTrue(text.contains("The user's location is unknown. If a question depends on location, ask which city."))
    }

    @Test
    fun withoutALocationItSaysSo() {
        assertTrue(prompt().contains("The user's location is unknown."))
    }

    @Test
    fun endsWithTheLocalDateAndTime() {
        assertTrue(prompt().endsWith("The current local date and time is Saturday, October 24, 2026, 3:05 PM UTC."))
    }

    @Test
    fun theSamePieceInADifferentOrderGivesTheSamePrompt() {
        val a =
            prompt(
                capabilities = listOf(lazy("a"), lazy("b")),
                skills = listOf(Skill("x", "", instructions = ""), Skill("y", "", instructions = "")),
            )
        val b =
            prompt(
                capabilities = listOf(lazy("b"), lazy("a")),
                skills = listOf(Skill("y", "", instructions = ""), Skill("x", "", instructions = "")),
            )
        assertEquals(a, b)
    }
}
