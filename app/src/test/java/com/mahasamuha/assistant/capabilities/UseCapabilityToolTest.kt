package com.mahasamuha.assistant.capabilities

import com.mahasamuha.assistant.skills.Skill
import com.mahasamuha.assistant.testutil.FakeReadOnlyTool
import com.mahasamuha.assistant.testutil.RecordingConfirmer
import com.mahasamuha.assistant.testutil.call
import com.mahasamuha.assistant.testutil.capability
import com.mahasamuha.assistant.testutil.local
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UseCapabilityToolTest {
    private val skill = Skill("music-tips", "How to music", listOf("music"), "Say the artist.")

    private fun catalog() = SessionCatalog.build(
        candidates = listOf(FakeReadOnlyTool("play") to true, FakeReadOnlyTool("pause") to true),
        merged = listOf(MergedCapability(capability("music", Load.LAZY, local("play"), local("pause")), emptyMap())),
        skills = listOf(skill),
        remoteTool = { error("no remote tools expected") },
        source = "test",
    )

    private fun tool() = UseCapabilityTool(catalog())

    @Test
    fun theToolIsLocalReadOnlyAndNotOpenWorld() {
        val annotations = tool().definition.annotations
        assertTrue(annotations.readOnlyHint)
        // Binding reads nothing from outside, so it must never turn announced actions into asked ones.
        assertFalse(annotations.openWorldHint)
        assertEquals("use_capability", tool().definition.name)
    }

    @Test
    fun resultListsToolsAndSkillInstructions() {
        val result = JSONObject(runBlocking { tool().run(JSONObject().put("id", " music ")) })
        assertEquals("music", result.getString("capability"))
        assertEquals(listOf("play", "pause"), (0 until result.getJSONArray("tools").length()).map { result.getJSONArray("tools").getString(it) })
        assertEquals(skill.asPromptSection(), result.getString("instructions"))
        assertFalse(result.has("note"))
    }

    @Test
    fun aSecondBindSaysItIsAlreadyAvailable() {
        val tool = tool()
        runBlocking { tool.run(JSONObject().put("id", "music")) }
        val result = JSONObject(runBlocking { tool.run(JSONObject().put("id", "music")) })
        assertEquals("Already available.", result.getString("note"))
    }

    @Test
    fun anUnknownIdFailsAndNamesTheAvailableOnes() {
        val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { tool().run(JSONObject().put("id", "nope")) } }
        assertEquals("Unknown capability \"nope\". Available: music", e.message)
    }

    @Test
    fun aMissingIdIsAnUnknownCapability() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { tool().run(JSONObject()) } }
    }

    @Test
    fun throughTheRegistryAnUnknownIdBecomesAnErrorResultAndBindingMakesToolsCallable() {
        val catalog = catalog()
        val confirmer = RecordingConfirmer()
        val error = runBlocking { catalog.registry.execute(call("use_capability", """{"id":"x"}"""), confirmer) }
        assertTrue(JSONObject(error).getString("error").startsWith("Unknown capability"))

        val before = runBlocking { catalog.registry.execute(call("play"), confirmer) }
        assertTrue(JSONObject(before).getString("error").contains("use_capability"))
        runBlocking { catalog.registry.execute(call("use_capability", """{"id":"music"}"""), confirmer) }
        assertEquals("""{"from":"play"}""", runBlocking { catalog.registry.execute(call("play"), confirmer) })
    }
}
