package com.mahasamuha.assistant.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTest {
    @Test
    fun parsesFrontmatterAndInstructions() {
        val skill = Skill.parse(
            """
            ---
            name: calling
            description: How to place calls
            capabilities: [phone, contacts]
            ---

            Look the contact up first.

            Then call.
            """.trimIndent(),
        )
        assertEquals("calling", skill.name)
        assertEquals("How to place calls", skill.description)
        assertEquals(listOf("phone", "contacts"), skill.capabilities)
        assertEquals("Look the contact up first.\n\nThen call.", skill.instructions)
    }

    @Test
    fun capabilitiesAreOptional() {
        val skill = Skill.parse("---\nname: tone\ndescription: Be kind\n---\nSmile.")
        assertTrue(skill.capabilities.isEmpty())
        assertEquals("Smile.", skill.instructions)
    }

    @Test
    fun acceptsWindowsLineEndings() {
        val skill = Skill.parse("---\r\nname: tone\r\ndescription: Be kind\r\n---\r\nSmile.\r\n")
        assertEquals("tone", skill.name)
        assertEquals("Smile.", skill.instructions)
    }

    @Test
    fun capabilityListToleratesSpacesAndEmptyItems() {
        val skill = Skill.parse("---\nname: x\ncapabilities: [ a ,  b , ]\n---\nbody")
        assertEquals(listOf("a", "b"), skill.capabilities)
        assertTrue(Skill.parse("---\nname: x\ncapabilities: []\n---\nbody").capabilities.isEmpty())
    }

    @Test
    fun aDescriptionMayContainColons() {
        val skill = Skill.parse("---\nname: x\ndescription: Use when: the user asks\n---\nbody")
        assertEquals("Use when: the user asks", skill.description)
    }

    @Test
    fun aHorizontalRuleInTheInstructionsIsKept() {
        val skill = Skill.parse("---\nname: x\n---\nbefore\n\n---\n\nafter")
        assertEquals("before\n\n---\n\nafter", skill.instructions)
    }

    @Test
    fun requiresFrontmatterAClosingMarkerAndAName() {
        assertThrows(IllegalArgumentException::class.java) { Skill.parse("name: x\n---\nbody") }
        assertThrows(IllegalArgumentException::class.java) { Skill.parse("---\nname: x\nbody") }
        assertThrows(IllegalArgumentException::class.java) { Skill.parse("---\ndescription: d\n---\nbody") }
        assertThrows(IllegalArgumentException::class.java) { Skill.parse("---\nname:   \n---\nbody") }
    }

    @Test
    fun promptSectionHasNameDescriptionAndInstructions() {
        val skill = Skill("calling", "How to call", emptyList(), "Be quick.")
        assertEquals("## Skill: calling\nHow to call\n\nBe quick.", skill.asPromptSection())
    }

    @Test
    fun aServerSkillOverridesABundledOneOfTheSameName() {
        val bundled = listOf(Skill("a", "bundled a", instructions = "1"), Skill("b", "bundled b", instructions = "2"))
        val server = listOf(Skill("b", "server b", instructions = "3"), Skill("c", "server c", instructions = "4"))

        val merged = mergeSkills(bundled, server)

        assertEquals(listOf("a", "b", "c"), merged.map { it.name })
        assertEquals("server b", merged.single { it.name == "b" }.description)
    }

    @Test
    fun withoutServerSkillsTheBundledOnesRemain() {
        val bundled = listOf(Skill("a", "", instructions = ""))
        assertEquals(bundled, mergeSkills(bundled, emptyList()))
    }
}
