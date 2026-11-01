package com.mahasamuha.assistant.llm

import android.net.Uri
import android.util.Log
import com.mahasamuha.assistant.BuildConfig
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The OpenAI Responses API (`/v1/responses`), streamed through the auth gateway, with function tools. Its
 * output is typed: each response's items are sent back verbatim as history (docs/phase3-notes.md). Web
 * search is one of our tools (the MCP server's `web_search`), not the provider's, so nothing here is
 * provider-specific.
 */
class GatewayChatClient(
    /** Read at each request, so a URL changed on the setup screen applies at once. */
    private val gateways: () -> List<Uri>,
    private val auth: AuthManager,
) : ChatClient {
    override fun stream(
        messages: List<ChatMessage>,
        options: ChatOptions,
    ): Flow<ChatEvent> =
        callbackFlow {
            val token = auth.freshAccessToken()
            // v1 uses the first gateway; the list leaves room for a fallback.
            val connection = URL("${gateways().first()}/v1/responses").openConnection() as HttpURLConnection

            // An unreachable server often still accepts the connection (the reverse proxy answers) and then
            // sends nothing, so the read timeout alone would leave the user waiting a minute. The gateway
            // sends headers as soon as the provider starts its reply, so a late first response means it's down.
            val awaitingResponse =
                launch {
                    delay(RESPONSE_START_TIMEOUT_MS)
                    Log.w(TAG, "Gateway sent no response in ${RESPONSE_START_TIMEOUT_MS / 1000} s")
                    connection.disconnect()
                }

            launch(Dispatchers.IO) {
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 60_000
                    connection.instanceFollowRedirects = false // the gateway never redirects; don't send the token elsewhere
                    connection.doOutput = true
                    connection.setRequestProperty("content-type", "application/json")
                    connection.setRequestProperty("accept", "text/event-stream")
                    connection.setRequestProperty("authorization", "Bearer $token")
                    connection.outputStream.use { it.write(requestBody(messages, options).toString().toByteArray()) }

                    val status = connection.responseCode
                    awaitingResponse.cancel()
                    if (status != HttpURLConnection.HTTP_OK) {
                        // Upstream error text can quote the request, so it's logged only in debug builds.
                        val detail =
                            if (BuildConfig.DEBUG) {
                                connection.errorStream
                                    ?.bufferedReader()
                                    ?.use { it.readText() }
                                    ?.take(300)
                            } else {
                                null
                            }
                        Log.w(TAG, "Gateway returned $status${detail?.let { ": $it" }.orEmpty()}")
                        close(errorFor(status))
                        return@launch
                    }
                    // Items are collected as each one finishes: in a streamed search, the LLM gateway's final
                    // response.completed omits the web_search_call items (docs/phase3-notes.md).
                    // Items are collected as each one finishes rather than from response.completed, which the LLM
                    // gateway has been seen to leave incomplete in a stream (docs/phase3-notes.md).
                    val items = mutableListOf<JSONObject>()
                    var completed = false
                    connection.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!line.startsWith("data:")) continue
                            val event = event(line.removePrefix("data:").trim()) ?: continue
                            when (event.optString("type")) {
                                "response.output_text.delta" -> {
                                    event
                                        .optString(
                                            "delta",
                                        ).takeIf { it.isNotEmpty() }
                                        ?.let { send(ChatEvent.Text(it)) }
                                }

                                "response.output_item.done" -> {
                                    event.optJSONObject("item")?.let(items::add)
                                }

                                // Incomplete means a limit was hit (e.g. max tokens): keep what arrived.
                                "response.completed", "response.incomplete" -> {
                                    completed = true
                                }

                                "response.failed", "error" -> {
                                    throw ChatException.Unavailable("Response failed: ${event.toString().take(300)}")
                                }
                            }
                        }
                    }
                    if (!completed) throw ChatException.Unavailable("Stream ended without a completed response")
                    val (kept, calls) = replayAndCalls(items)
                    if (calls.isNotEmpty()) send(ChatEvent.ToolCalls(calls))
                    send(ChatEvent.Finished(kept))
                    close()
                } catch (e: ChatException) {
                    close(e)
                } catch (e: IOException) {
                    // Also how a cancelled collection ends: awaitClose disconnects, and the blocked read throws.
                    close(ChatException.Unavailable("Gateway connection failed", e))
                }
            }

            awaitClose {
                awaitingResponse.cancel()
                connection.disconnect()
            }
        }

    private fun event(payload: String): JSONObject? =
        try {
            JSONObject(payload)
        } catch (e: JSONException) {
            Log.w(TAG, "Unparseable stream event: ${payload.take(200)}")
            null
        }

    private companion object {
        const val RESPONSE_START_TIMEOUT_MS = 15_000L
    }
}

internal fun requestBody(
    messages: List<ChatMessage>,
    options: ChatOptions,
): JSONObject {
    val body =
        JSONObject()
            .put("model", options.model)
            .put("max_output_tokens", options.maxTokens)
            .put("stream", true)
            .put("reasoning", JSONObject().put("effort", options.reasoningEffort))
            .put("instructions", messages.filter { it.role == ChatMessage.Role.SYSTEM }.joinToString("\n\n") { it.content.orEmpty() })
            .put("input", JSONArray().apply { messages.forEach { message -> inputItems(message).forEach(::put) } })
    val tools = JSONArray()
    options.tools.forEach { tool ->
        tools.put(
            JSONObject()
                .put("type", "function")
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", tool.inputSchema),
        )
    }
    if (tools.length() > 0) body.put("tools", tools)
    return body
}

/** One conversation entry as Responses input items. Assistant turns go back exactly as received. */
private fun inputItems(message: ChatMessage): List<JSONObject> =
    when (message.role) {
        ChatMessage.Role.SYSTEM -> {
            emptyList()
        }

        // sent as instructions
        ChatMessage.Role.USER -> {
            listOf(JSONObject().put("role", "user").put("content", message.content.orEmpty()))
        }

        ChatMessage.Role.TOOL -> {
            listOf(
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", message.toolCallId)
                    .put("output", message.content.orEmpty()),
            )
        }

        ChatMessage.Role.ASSISTANT -> {
            message.replay.ifEmpty {
                buildList {
                    message.content?.takeIf { it.isNotEmpty() }?.let { add(JSONObject().put("role", "assistant").put("content", it)) }
                    message.toolCalls.forEach { call ->
                        add(
                            JSONObject()
                                .put(
                                    "type",
                                    "function_call",
                                ).put("call_id", call.id)
                                .put("name", call.name)
                                .put("arguments", call.arguments),
                        )
                    }
                }
            }
        }
    }

internal fun errorFor(status: Int): ChatException =
    when (status) {
        401 -> ChatException.Unauthorized()
        402 -> ChatException.BudgetExceeded()
        429 -> ChatException.RateLimited()
        in 400..499 -> ChatException.Rejected(status)
        else -> ChatException.Unavailable("Gateway returned $status")
    }

/** The items to keep for replay (without thinking) and the function calls among them. */
internal fun replayAndCalls(items: List<JSONObject>): Pair<List<JSONObject>, List<ToolCall>> {
    // Thinking can't be sent back through the LLM gateway (its signatures are lost), and
    // earlier turns don't need it.
    val kept = items.filter { it.optString("type") != "reasoning" }
    val calls =
        kept
            .filter { it.optString("type") == "function_call" }
            .map { ToolCall(it.getString("call_id"), it.getString("name"), it.optString("arguments")) }
    return kept to calls
}
