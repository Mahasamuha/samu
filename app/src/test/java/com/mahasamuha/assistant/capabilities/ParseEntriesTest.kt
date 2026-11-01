package com.mahasamuha.assistant.capabilities

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ParseEntriesTest {
    /** A parser like the real ones: requires a string "id" and applies a length limit. */
    private fun parseIds(
        array: JSONArray,
        max: Int = 10,
    ): List<String> =
        parseEntries(array, "test", max) { entry ->
            entry.getString("id").also { IndexLimits.checkLength("id", it, 5) }
        }

    @Test
    fun keepsValidEntriesInOrderAndSkipsBadOnes() {
        val array =
            JSONArray(
                """["not an object", {"id": "a"}, {"id": {"nested": 1}}, {"nope": true}, {"id": "toolong"}, {"id": "b"}]""",
            )
        // Skipped: a non-object, a wrong-typed id, a missing id, and an id over the length limit.
        assertEquals(listOf("a", "b"), parseIds(array))
    }

    @Test
    fun countLimitKeepsOnlyTheFirstEntries() {
        val array = JSONArray("""[{"id": "a"}, {"id": "b"}, {"id": "c"}]""")
        assertEquals(listOf("a", "b"), parseIds(array, max = 2))
    }
}
