package com.mahasamuha.assistant.capabilities

import com.mahasamuha.assistant.skills.Skill
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityIndexParseTest {
    private val capability =
        """{"id":"search","title":"Search","description":"d","load":"lazy",""" +
            """"tools":[{"name":"web_search","where":"remote"}]}"""
    private val tool =
        """{"name":"web_search","description":"Search the web","inputSchema":{"type":"object"},""" +
            """"annotations":{"readOnlyHint":true,"openWorldHint":true}}"""
    private val skill = """{"name":"search-tips","description":"s","capabilities":["search"],"instructions":"Be brief."}"""

    private fun parse(
        text: String,
        principal: String = "user-1",
    ) = parseCapabilityIndex(text, principal, 123L, IndexSource.FRESH)

    @Test
    fun parsesAVersionOneIndex() {
        val index = parse("""{"version":1,"capabilities":[$capability],"tools":[$tool],"skills":[$skill]}""")

        assertEquals("user-1", index.principal)
        assertEquals(123L, index.fetchedAtMillis)
        assertEquals(IndexSource.FRESH, index.source)
        assertEquals(listOf("search"), index.capabilities.map { it.id })
        assertEquals(listOf("web_search"), index.tools.map { it.name })
        assertEquals(Skill("search-tips", "s", listOf("search"), "Be brief."), index.skills.single())
    }

    @Test
    fun toolsAndSkillsAreOptional() {
        val index = parse("""{"version":1,"capabilities":[$capability]}""")
        assertTrue(index.tools.isEmpty())
        assertTrue(index.skills.isEmpty())
    }

    @Test
    fun anUnsupportedOrMissingVersionIsRejected() {
        val e = assertThrows(IllegalStateException::class.java) { parse("""{"version":2,"capabilities":[]}""") }
        assertTrue(e.message!!.contains("version 2"))
        assertThrows(IllegalStateException::class.java) { parse("""{"capabilities":[]}""") }
    }

    @Test
    fun aDocumentWithoutCapabilitiesOrNotJsonIsRejected() {
        assertThrows(JSONException::class.java) { parse("""{"version":1}""") }
        assertThrows(JSONException::class.java) { parse("not json") }
    }

    @Test
    fun badToolAndSkillEntriesAreSkippedIndividually() {
        val longToolDescription = "x".repeat(IndexLimits.MAX_TOOL_DESCRIPTION + 1)
        val longInstructions = "x".repeat(IndexLimits.MAX_SKILL_INSTRUCTIONS + 1)
        val index =
            parse(
                """{"version":1,"capabilities":[$capability],
              "tools":[$tool,{"description":"no name"},{"name":"long","description":"$longToolDescription"},{"name":"other"}],
              "skills":[$skill,{"description":"no name"},{"name":"big","instructions":"$longInstructions"}]}""",
            )
        assertEquals(listOf("web_search", "other"), index.tools.map { it.name })
        assertEquals(listOf("search-tips"), index.skills.map { it.name })
    }

    @Test
    fun anOverlargeInputSchemaSkipsThatTool() {
        val bigSchema = """{"type":"object","description":"${"x".repeat(IndexLimits.MAX_INPUT_SCHEMA)}"}"""
        val index = parse("""{"version":1,"capabilities":[],"tools":[{"name":"big","inputSchema":$bigSchema},$tool]}""")
        assertEquals(listOf("web_search"), index.tools.map { it.name })
    }

    @Test
    fun ageIsNeverNegative() {
        val index = parse("""{"version":1,"capabilities":[]}""")
        assertEquals(0L, index.ageMillis(now = 100L))
        assertEquals(1000L, index.ageMillis(now = 1123L))
    }
}
