package com.mahasamuha.assistant.llm

import com.mahasamuha.assistant.llm.ChatMessage.Role
import com.mahasamuha.assistant.testutil.definition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayChatClientTest {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun body(
        messages: List<ChatMessage>,
        options: ChatOptions = ChatOptions(),
    ) = requestBody(messages, options)

    @Test
    fun systemMessagesBecomeInstructionsAndAreNotInput() {
        val result = body(listOf(ChatMessage(Role.SYSTEM, "Rule one"), ChatMessage(Role.SYSTEM, "Rule two"), ChatMessage(Role.USER, "hi")))
        assertEquals("Rule one\n\nRule two", result.getString("instructions"))
        assertEquals(listOf("user"), result.getJSONArray("input").objects().map { it.getString("role") })
    }

    @Test
    fun carriesTheOptionsAndStreams() {
        val result = body(emptyList(), ChatOptions(model = "m", maxTokens = 42, reasoningEffort = "low"))
        assertEquals("m", result.getString("model"))
        assertEquals(42, result.getInt("max_output_tokens"))
        assertTrue(result.getBoolean("stream"))
        assertEquals("low", result.getJSONObject("reasoning").getString("effort"))
        assertFalse(result.has("tools"))
    }

    @Test
    fun toolsAreSentAsFunctionsWithoutAnnotations() {
        val tool = definition("find_contact", readOnly = true)
        val sent = body(emptyList(), ChatOptions(tools = listOf(tool))).getJSONArray("tools").objects().single()
        assertEquals("function", sent.getString("type"))
        assertEquals("find_contact", sent.getString("name"))
        assertEquals("find_contact description", sent.getString("description"))
        assertEquals("object", sent.getJSONObject("parameters").getString("type"))
        // Annotations steer the phone's confirmation rules and are not sent to the model.
        assertEquals(setOf("type", "name", "description", "parameters"), sent.keys().asSequence().toSet())
    }

    @Test
    fun toolResultsAreFunctionCallOutputs() {
        val input = body(listOf(ChatMessage(Role.TOOL, """{"ok":true}""", toolCallId = "c1"))).getJSONArray("input").objects().single()
        assertEquals("function_call_output", input.getString("type"))
        assertEquals("c1", input.getString("call_id"))
        assertEquals("""{"ok":true}""", input.getString("output"))
    }

    @Test
    fun anAssistantTurnWithoutReplayIsRebuiltFromTextAndToolCalls() {
        val message = ChatMessage(Role.ASSISTANT, "Let me check.", toolCalls = listOf(ToolCall("c1", "find_contact", """{"name":"Sam"}""")))
        val items = body(listOf(message)).getJSONArray("input").objects()
        assertEquals("assistant", items[0].getString("role"))
        assertEquals("Let me check.", items[0].getString("content"))
        assertEquals("function_call", items[1].getString("type"))
        assertEquals("c1", items[1].getString("call_id"))
        assertEquals("find_contact", items[1].getString("name"))
        assertEquals("""{"name":"Sam"}""", items[1].getString("arguments"))
    }

    @Test
    fun anAssistantTurnWithNoTextAddsNoEmptyMessage() {
        val items =
            body(
                listOf(ChatMessage(Role.ASSISTANT, null, toolCalls = listOf(ToolCall("c1", "t", "{}")))),
            ).getJSONArray("input").objects()
        assertEquals(listOf("function_call"), items.map { it.getString("type") })
    }

    @Test
    fun anAssistantTurnWithReplayIsSentBackExactly() {
        val replay = listOf(JSONObject("""{"type":"message","id":"m1"}"""), JSONObject("""{"type":"function_call","call_id":"c9"}"""))
        val message = ChatMessage(Role.ASSISTANT, "ignored text", toolCalls = listOf(ToolCall("zz", "ignored", "{}")), replay = replay)
        val items = body(listOf(message)).getJSONArray("input").objects()
        assertEquals(replay.map { it.toString() }, items.map { it.toString() })
    }

    @Test
    fun conversationOrderIsPreserved() {
        val input =
            body(
                listOf(
                    ChatMessage(Role.SYSTEM, "s"),
                    ChatMessage(Role.USER, "u1"),
                    ChatMessage(Role.ASSISTANT, "a1"),
                    ChatMessage(Role.USER, "u2"),
                ),
            ).getJSONArray("input").objects()
        assertEquals(listOf("u1", "a1", "u2"), input.map { it.getString("content") })
    }

    @Test
    fun statusCodesMapToTheExceptionsTheSessionSpeaks() {
        assertTrue(errorFor(401) is ChatException.Unauthorized)
        assertTrue(errorFor(402) is ChatException.BudgetExceeded)
        assertTrue(errorFor(429) is ChatException.RateLimited)
        assertEquals(404, (errorFor(404) as ChatException.Rejected).status)
        assertEquals(400, (errorFor(400) as ChatException.Rejected).status)
        assertTrue(errorFor(500) is ChatException.Unavailable)
        assertTrue(errorFor(503) is ChatException.Unavailable)
    }

    @Test
    fun reasoningItemsAreDroppedAndFunctionCallsExtracted() {
        val items =
            listOf(
                JSONObject("""{"type":"reasoning","summary":[]}"""),
                JSONObject("""{"type":"message","content":[]}"""),
                JSONObject("""{"type":"function_call","call_id":"c1","name":"find_contact","arguments":"{\"name\":\"Sam\"}"}"""),
                JSONObject("""{"type":"function_call","call_id":"c2","name":"set_timer"}"""),
            )
        val (kept, calls) = replayAndCalls(items)

        assertEquals(listOf("message", "function_call", "function_call"), kept.map { it.getString("type") })
        assertEquals(
            listOf(ToolCall("c1", "find_contact", """{"name":"Sam"}"""), ToolCall("c2", "set_timer", "")),
            calls,
        )
    }

    @Test
    fun aResponseWithoutCallsHasNone() {
        val (kept, calls) = replayAndCalls(listOf(JSONObject("""{"type":"message"}""")))
        assertEquals(1, kept.size)
        assertTrue(calls.isEmpty())
    }
}
