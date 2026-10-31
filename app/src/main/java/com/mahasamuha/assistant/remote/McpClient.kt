package com.mahasamuha.assistant.remote

import android.net.Uri
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The few MCP calls the phone needs (`tools/list`, `tools/call`), as JSON-RPC over the auth gateway's
 * `/mcp` route. Our server is stateless and answers in plain JSON, so each call is one POST: no session,
 * no event stream, and no `initialize` handshake (a stateless server keeps nothing from one request to the
 * next; the protocol version travels in a header instead). The official Kotlin SDK covers all of MCP but
 * would roughly double the APK (plan §6).
 */
class McpClient(private val gateways: List<Uri>, private val auth: AuthManager) {
    private val ids = AtomicLong()

    /** The server's tools, as definitions for the model. Unset annotations take MCP's defaults. */
    suspend fun listTools(): List<ToolDefinition> {
        val definitions = mutableListOf<ToolDefinition>()
        var cursor: String? = null
        do {
            val result = request("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
            val tools = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until tools.length()) definitions += toolDefinition(tools.getJSONObject(i))
            cursor = result.optString("nextCursor").takeIf { it.isNotEmpty() }
        } while (cursor != null)
        return definitions
    }

    /** Runs a tool and returns its text content. A tool-reported error throws, so the registry relays it. */
    suspend fun callTool(name: String, arguments: JSONObject): String {
        val result = request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        val content = result.optJSONArray("content") ?: JSONArray()
        val text = (0 until content.length())
            .map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.optString("text") }
        if (result.optBoolean("isError")) throw McpException(text.ifEmpty { "Tool $name failed" })
        return text
    }

    /** Reads a resource and returns its first content's text (the capability index is one JSON document). */
    suspend fun readResource(uri: String): String {
        val result = request("resources/read", JSONObject().put("uri", uri), maxBytes = MAX_RESOURCE_BYTES)
        val contents = result.optJSONArray("contents")
        val text = contents?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optString("text")?.takeIf { it.isNotEmpty() }
        return text ?: throw McpException("MCP resource $uri has no text content")
    }

    private suspend fun request(method: String, params: JSONObject, maxBytes: Int = MAX_RESPONSE_BYTES): JSONObject {
        val token = auth.freshAccessToken()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", ids.incrementAndGet())
            .put("method", method)
            .put("params", params)
        // v1 uses the first gateway; the list leaves room for a fallback.
        val connection = URL("${gateways.first()}/mcp").openConnection() as HttpURLConnection
        val response = disconnectingOnCancel(connection) {
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false // the gateway never redirects; don't send the token elsewhere
                connection.doOutput = true
                connection.setRequestProperty("content-type", "application/json")
                connection.setRequestProperty("accept", "application/json, text/event-stream")
                connection.setRequestProperty("mcp-protocol-version", PROTOCOL_VERSION)
                connection.setRequestProperty("authorization", "Bearer $token")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw McpException("MCP $method returned $status")
                // Capped: a tool result goes to the model, so an oversized one costs tokens on every later round.
                val bytes = connection.inputStream.use { it.readNBytes(maxBytes + 1) }
                if (bytes.size > maxBytes) throw McpException("MCP $method response is over $maxBytes bytes")
                bytes.toString(Charsets.UTF_8)
            } catch (e: IOException) {
                throw McpException("MCP $method failed", e)
            } finally {
                connection.disconnect()
            }
        }
        val json = JSONObject(response)
        json.optJSONObject("error")?.let { throw McpException("MCP $method: ${it.optString("message")}") }
        return json.optJSONObject("result") ?: throw McpException("MCP $method returned no result")
    }

    /**
     * Runs [exchange] on the IO dispatcher. A blocked read ignores coroutine cancellation, so a caller's
     * timeout (e.g. [RemoteTools]' tool-list fetch) would otherwise wait for the full read timeout; on
     * cancellation this disconnects, which ends the read at once.
     */
    private suspend fun <T> disconnectingOnCancel(connection: HttpURLConnection, exchange: () -> T): T = coroutineScope {
        val call = async(Dispatchers.IO) { exchange() }
        try {
            call.await()
        } catch (e: CancellationException) {
            connection.disconnect()
            throw e
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val MAX_RESPONSE_BYTES = 64 * 1024
        // The capability index (tool definitions and skills) never reaches the model whole.
        const val MAX_RESOURCE_BYTES = 256 * 1024
    }
}

/** A tool definition in MCP's `tools/list` shape; also used for the definitions inside the capability index. */
fun toolDefinition(tool: JSONObject): ToolDefinition {
    val annotations = tool.optJSONObject("annotations") ?: JSONObject()
    val defaults = ToolAnnotations()
    return ToolDefinition(
        name = tool.getString("name"),
        description = tool.optString("description"),
        inputSchema = tool.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
        annotations = ToolAnnotations(
            title = annotations.optString("title").ifEmpty { tool.optString("title") }.ifEmpty { null },
            readOnlyHint = annotations.optBoolean("readOnlyHint", defaults.readOnlyHint),
            destructiveHint = annotations.optBoolean("destructiveHint", defaults.destructiveHint),
            idempotentHint = annotations.optBoolean("idempotentHint", defaults.idempotentHint),
            openWorldHint = annotations.optBoolean("openWorldHint", defaults.openWorldHint),
        ),
    )
}
