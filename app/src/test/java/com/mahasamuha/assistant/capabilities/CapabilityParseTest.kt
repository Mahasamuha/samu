package com.mahasamuha.assistant.capabilities

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityParseTest {
    private fun entry(id: String, load: String = "lazy", tools: String = """[{"name":"t","where":"local"}]""", extra: String = "") =
        """{"id":"$id","title":"T","description":"d","load":"$load","tools":$tools$extra}"""

    private fun parse(vararg entries: String) = Capability.parseAll(JSONArray("[${entries.joinToString(",")}]"))

    @Test
    fun parsesFieldsAndDefaultsRequiredToTrue() {
        val tools = """[{"name":"a","where":"local"},{"name":"b","where":"REMOTE","required":false},{"name":"c","where":"hybrid"}]"""
        val capability = parse(entry("nav", load = "Eager", tools = tools)).single()

        assertEquals("nav", capability.id)
        assertEquals(Load.EAGER, capability.load)
        assertEquals(
            listOf(
                CapabilityTool("a", Where.LOCAL, true),
                CapabilityTool("b", Where.REMOTE, false),
                CapabilityTool("c", Where.HYBRID, true),
            ),
            capability.tools,
        )
    }

    @Test
    fun skipsEntriesWithUnknownLoadOrWhereButKeepsTheRest() {
        val result = parse(
            entry("good1"),
            entry("badLoad", load = "sometimes"),
            entry("badWhere", tools = """[{"name":"t","where":"cloud"}]"""),
            entry("good2"),
        )
        assertEquals(listOf("good1", "good2"), result.map { it.id })
    }

    @Test
    fun skipsMalformedEntries() {
        val result = parse("""{"id":"noTools","load":"lazy"}""", """{"load":"lazy","tools":[]}""", entry("ok"))
        assertEquals(listOf("ok"), result.map { it.id })
    }

    @Test
    fun skipsEntryOverTheToolLimit() {
        val tooMany = (1..IndexLimits.MAX_TOOLS_PER_CAPABILITY + 1).joinToString(",", "[", "]") { """{"name":"t$it","where":"local"}""" }
        val atLimit = (1..IndexLimits.MAX_TOOLS_PER_CAPABILITY).joinToString(",", "[", "]") { """{"name":"t$it","where":"local"}""" }
        val result = parse(entry("over", tools = tooMany), entry("at", tools = atLimit))
        assertEquals(listOf("at"), result.map { it.id })
    }

    @Test
    fun skipsEntryWithOverlongDescriptionInsteadOfTruncating() {
        val long = "x".repeat(IndexLimits.MAX_CAPABILITY_DESCRIPTION + 1)
        val exact = "x".repeat(IndexLimits.MAX_CAPABILITY_DESCRIPTION)
        val result = parse(
            """{"id":"long","description":"$long","load":"lazy","tools":[{"name":"t","where":"local"}]}""",
            """{"id":"exact","description":"$exact","load":"lazy","tools":[{"name":"t","where":"local"}]}""",
        )
        assertEquals(listOf("exact"), result.map { it.id })
        assertEquals(exact, result.single().description)
    }

    @Test
    fun keepsOnlyTheFirstCapabilitiesPastTheCountLimit() {
        val entries = (1..IndexLimits.MAX_CAPABILITIES + 5).map { entry("c$it") }.toTypedArray()
        val result = parse(*entries)
        assertEquals(IndexLimits.MAX_CAPABILITIES, result.size)
        assertEquals("c1", result.first().id)
        assertEquals("c${IndexLimits.MAX_CAPABILITIES}", result.last().id)
    }

    @Test
    fun tierIsDerivedFromTheTools() {
        fun tier(vararg wheres: Where) = Capability("id", "", "", Load.LAZY, wheres.mapIndexed { i, w -> CapabilityTool("t$i", w) }).tier
        assertEquals(Tier.LOCAL, tier(Where.LOCAL, Where.LOCAL))
        assertEquals(Tier.SERVER, tier(Where.REMOTE))
        assertEquals(Tier.HYBRID, tier(Where.LOCAL, Where.REMOTE))
        assertEquals(Tier.HYBRID, tier(Where.HYBRID))
        assertEquals(Tier.HYBRID, tier(Where.REMOTE, Where.HYBRID))
    }

    @Test
    fun checkLengthAllowsTheLimitAndRejectsOneOver() {
        IndexLimits.checkLength("f", "abc", 3)
        val e = assertThrows(IllegalArgumentException::class.java) { IndexLimits.checkLength("field", "abcd", 3) }
        assertTrue(e.message!!.contains("field"))
        assertFalse(e.message!!.isEmpty())
    }
}
