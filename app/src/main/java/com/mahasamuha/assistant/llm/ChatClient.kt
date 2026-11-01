package com.mahasamuha.assistant.llm

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** A conversation entry. Assistant entries may carry tool calls; tool entries answer one by id. */
data class ChatMessage(
    val role: Role,
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /**
     * For assistant entries: the response exactly as the endpoint returned it (e.g. its web searches with
     * their results), sent back verbatim so follow-ups can see them. Opaque outside the client that made it.
     */
    val replay: List<JSONObject> = emptyList(),
) {
    enum class Role(
        val wire: String,
    ) {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        TOOL("tool"),
    }
}

/** A function the model may call, in the MCP tool shape: a JSON schema for its arguments, plus annotations. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val annotations: ToolAnnotations = ToolAnnotations(),
)

/**
 * MCP tool annotations, with MCP's defaults: an unannotated tool is assumed to have side effects. They
 * steer the phone's confirmation rules and aren't sent to the model.
 */
data class ToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = true,
    val idempotentHint: Boolean = false,
    val openWorldHint: Boolean = true,
)

data class ChatOptions(
    val model: String = "anthropic/claude-haiku-5-5",
    /** Includes thinking tokens, so it leaves room for the answer after a longer think. */
    val maxTokens: Int = 1000,
    /**
     * OpenAI-style `reasoning_effort`. "low" keeps the model's reasoning in an unspoken thinking step: with
     * "none" it often narrated its reasoning aloud ("I'll check…", "The user wants…") despite the prompt.
     * Costs about 0.5–2 s before the first word on plain and search answers (docs/phase3-notes.md).
     */
    val reasoningEffort: String = "low",
    val tools: List<ToolDefinition> = emptyList(),
)

sealed interface ChatEvent {
    data class Text(
        val delta: String,
    ) : ChatEvent

    /** Emitted once, at the end of a response that asks for tools. */
    data class ToolCalls(
        val calls: List<ToolCall>,
    ) : ChatEvent

    /** Emitted last: what to keep in [ChatMessage.replay] for this response. */
    data class Finished(
        val replay: List<JSONObject>,
    ) : ChatEvent
}

/** The LLM endpoint. Implementations stream events and fail with [ChatException]. */
interface ChatClient {
    fun stream(
        messages: List<ChatMessage>,
        options: ChatOptions = ChatOptions(),
    ): Flow<ChatEvent>
}

sealed class ChatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Unauthorized : ChatException("Token rejected by the gateway")

    class BudgetExceeded : ChatException("Spend budget exceeded")

    class RateLimited : ChatException("Rate limited")

    /** The request itself was refused (a bug on our side, not an outage). */
    class Rejected(
        val status: Int,
    ) : ChatException("Request rejected with $status")

    class Unavailable(
        message: String,
        cause: Throwable? = null,
    ) : ChatException(message, cause)
}
