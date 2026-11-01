package com.mahasamuha.assistant.remote

import com.mahasamuha.assistant.testutil.RecordingConfirmer
import com.mahasamuha.assistant.testutil.call
import com.mahasamuha.assistant.testutil.definition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.ConfirmationResult
import com.mahasamuha.assistant.tools.ReadOnlyTool
import com.mahasamuha.assistant.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteToolTest {
    private class FakeCaller(
        private val outcome: () -> String,
    ) {
        val calls = mutableListOf<Pair<String, JSONObject>>()

        suspend fun callTool(
            name: String,
            arguments: JSONObject,
        ): String {
            calls += name to arguments
            return outcome()
        }
    }

    private var staleCalls = 0

    private fun remote(
        def: com.mahasamuha.assistant.llm.ToolDefinition,
        caller: FakeCaller,
    ) = remoteTool(def, caller::callTool) {
        staleCalls++
    }

    @Test
    fun readOnlyHintDecidesTheToolKind() {
        val caller = FakeCaller { "x" }
        assertTrue(remote(definition("search", readOnly = true), caller) is ReadOnlyTool)
        assertTrue(remote(definition("create", readOnly = false), caller) is ActionTool)
    }

    @Test
    fun aReadOnlyToolReturnsTheServersText() {
        val caller = FakeCaller { """{"results":[]}""" }
        val tool = remote(definition("search", readOnly = true), caller) as ReadOnlyTool
        assertEquals("""{"results":[]}""", runBlocking { tool.run(JSONObject().put("q", "x")) })
        assertEquals("search", caller.calls.single().first)
        assertEquals(
            "x",
            caller.calls
                .single()
                .second
                .getString("q"),
        )
        assertEquals(0, staleCalls)
    }

    @Test
    fun theStaleExceptionTriggersOnStaleThenPropagates() {
        val caller = FakeCaller { throw McpStaleToolException("MCP error -32602: gone") }
        val tool = remote(definition("search", readOnly = true), caller) as ReadOnlyTool
        val registry = ToolRegistry(listOf(tool))

        val result = runBlocking { registry.execute(call("search"), RecordingConfirmer()) }

        assertEquals(1, staleCalls)
        assertEquals("MCP error -32602: gone", JSONObject(result).getString("error"))
    }

    @Test
    fun aPlainToolFailureDoesNotTriggerOnStale() {
        val caller = FakeCaller { throw McpException("Brave returned 429") }
        val tool = remote(definition("search", readOnly = true), caller) as ReadOnlyTool
        val result = runBlocking { ToolRegistry(listOf(tool)).execute(call("search"), RecordingConfirmer()) }
        assertEquals(0, staleCalls)
        assertEquals("Brave returned 429", JSONObject(result).getString("error"))
    }

    @Test
    fun aRemoteActionAlwaysAsksAndNamesTheToolAndArguments() {
        val caller = FakeCaller { "done" }
        val tool = remote(definition("create_event", readOnly = false), caller) as ActionTool
        val pending = runBlocking { tool.prepare(JSONObject().put("title", "Lunch")) }
        assertEquals(Confirmation.Ask("Run create event with title Lunch?"), pending.confirmation)
        assertTrue(caller.calls.isEmpty())
    }

    @Test
    fun aRemoteActionPrefersTheAnnotationTitleAndHandlesNoArguments() {
        val caller = FakeCaller { "done" }
        val tool = remote(definition("create_event", readOnly = false, title = "Add to calendar"), caller) as ActionTool
        assertEquals(Confirmation.Ask("Run Add to calendar?"), runBlocking { tool.prepare(JSONObject()) }.confirmation)
    }

    @Test
    fun aRemoteActionCallsTheServerOnlyAfterConfirmation() {
        val caller = FakeCaller { "created" }
        val tool = remote(definition("create_event", readOnly = false), caller) as ActionTool
        val registry = ToolRegistry(listOf(tool))

        runBlocking { registry.execute(call("create_event", """{"title":"x"}"""), RecordingConfirmer(ConfirmationResult.Declined("no"))) }
        assertTrue(caller.calls.isEmpty())

        assertEquals("created", runBlocking { registry.execute(call("create_event", """{"title":"x"}"""), RecordingConfirmer()) })
        assertEquals(1, caller.calls.size)
    }

    @Test
    fun aStaleRejectionDuringAConfirmedActionTriggersOnStale() {
        val caller = FakeCaller { throw McpStaleToolException("stale") }
        val tool = remote(definition("create_event", readOnly = false), caller) as ActionTool
        runBlocking { ToolRegistry(listOf(tool)).execute(call("create_event"), RecordingConfirmer()) }
        assertEquals(1, staleCalls)
    }
}
