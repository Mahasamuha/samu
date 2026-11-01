package com.mahasamuha.assistant.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    private fun result(json: String) = JSONObject(json)

    // --- tools/call results

    @Test
    fun joinsTheTextContentOfASuccessfulResult() {
        val text = toolResultText("t", result("""{"content":[{"type":"text","text":"one"},{"type":"image","data":"x"},{"type":"text","text":"two"}]}"""))
        assertEquals("one\ntwo", text)
    }

    @Test
    fun aResultWithoutContentIsEmptyText() {
        assertEquals("", toolResultText("t", result("{}")))
    }

    @Test
    fun anSdkInvalidParamsErrorResultMeansTheIndexMayBeStale() {
        val e = assertThrows(McpStaleToolException::class.java) {
            toolResultText("t", result("""{"isError":true,"content":[{"type":"text","text":"MCP error -32602: Tool nope not found"}]}"""))
        }
        assertEquals("MCP error -32602: Tool nope not found", e.message)
    }

    @Test
    fun aToolsOwnFailureIsAPlainMcpExceptionNotStale() {
        val e = assertThrows(McpException::class.java) {
            toolResultText("t", result("""{"isError":true,"content":[{"type":"text","text":"Brave returned 429"}]}"""))
        }
        assertFalse(e is McpStaleToolException)
        assertEquals("Brave returned 429", e.message)
    }

    @Test
    fun theStaleTextMustBeAPrefixNotJustContained() {
        val e = assertThrows(McpException::class.java) {
            toolResultText("t", result("""{"isError":true,"content":[{"type":"text","text":"Upstream said: MCP error -32602: bad"}]}"""))
        }
        assertFalse(e is McpStaleToolException)
    }

    @Test
    fun anErrorWithNoTextNamesTheTool() {
        val e = assertThrows(McpException::class.java) { toolResultText("web_search", result("""{"isError":true}""")) }
        assertEquals("Tool web_search failed", e.message)
    }

    @Test
    fun isErrorFalseIsNotAnError() {
        assertEquals("fine", toolResultText("t", result("""{"isError":false,"content":[{"type":"text","text":"fine"}]}""")))
    }

    // --- JSON-RPC level

    @Test
    fun returnsTheResultObject() {
        val inner = rpcResult("tools/call", JSONObject("""{"jsonrpc":"2.0","id":1,"result":{"content":[]}}"""))
        assertTrue(inner.has("content"))
    }

    @Test
    fun invalidParamsOnAToolCallIsStale() {
        val e = assertThrows(McpStaleToolException::class.java) {
            rpcResult("tools/call", JSONObject("""{"error":{"code":-32602,"message":"Unknown tool"}}"""))
        }
        assertEquals("MCP tools/call: Unknown tool", e.message)
    }

    @Test
    fun invalidParamsOnAnotherMethodIsNotStale() {
        val e = assertThrows(McpException::class.java) {
            rpcResult("resources/read", JSONObject("""{"error":{"code":-32602,"message":"Unknown resource"}}"""))
        }
        assertFalse(e is McpStaleToolException)
    }

    @Test
    fun otherRpcErrorsOnAToolCallAreNotStale() {
        val e = assertThrows(McpException::class.java) {
            rpcResult("tools/call", JSONObject("""{"error":{"code":-32603,"message":"Internal"}}"""))
        }
        assertFalse(e is McpStaleToolException)
        assertEquals("MCP tools/call: Internal", e.message)
    }

    @Test
    fun aResponseWithNeitherResultNorErrorFails() {
        val e = assertThrows(McpException::class.java) { rpcResult("tools/call", JSONObject("""{"jsonrpc":"2.0","id":1}""")) }
        assertEquals("MCP tools/call returned no result", e.message)
    }

    // --- tool definitions

    @Test
    fun anUnannotatedToolIsAssumedToHaveSideEffectsAndReachOutside() {
        val tool = toolDefinition(JSONObject("""{"name":"mystery"}"""))
        assertEquals("mystery", tool.name)
        assertEquals("", tool.description)
        assertEquals("object", tool.inputSchema.getString("type"))
        assertFalse(tool.annotations.readOnlyHint)
        assertTrue(tool.annotations.openWorldHint)
        assertTrue(tool.annotations.destructiveHint)
        assertFalse(tool.annotations.idempotentHint)
        assertNull(tool.annotations.title)
    }

    @Test
    fun partialAnnotationsKeepTheDefaultsForTheRest() {
        val tool = toolDefinition(JSONObject("""{"name":"t","annotations":{"readOnlyHint":true}}"""))
        assertTrue(tool.annotations.readOnlyHint)
        assertTrue(tool.annotations.openWorldHint)
    }

    @Test
    fun readsAnnotationsDescriptionAndSchema() {
        val tool = toolDefinition(
            JSONObject(
                """{"name":"web_search","description":"Search","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},
                   "annotations":{"title":"Web search","readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}}""",
            ),
        )
        assertEquals("Search", tool.description)
        assertTrue(tool.inputSchema.getJSONObject("properties").has("q"))
        assertEquals("Web search", tool.annotations.title)
        assertTrue(tool.annotations.readOnlyHint)
        assertFalse(tool.annotations.destructiveHint)
        assertTrue(tool.annotations.idempotentHint)
        assertFalse(tool.annotations.openWorldHint)
    }

    @Test
    fun theTitleFallsBackToTheToolsOwnTitle() {
        assertEquals("Top level", toolDefinition(JSONObject("""{"name":"t","title":"Top level"}""")).annotations.title)
        assertEquals("Annotated", toolDefinition(JSONObject("""{"name":"t","title":"Top level","annotations":{"title":"Annotated"}}""")).annotations.title)
    }

    @Test
    fun aToolWithoutANameIsRejected() {
        assertThrows(org.json.JSONException::class.java) { toolDefinition(JSONObject("""{"description":"x"}""")) }
    }
}
