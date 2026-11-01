package com.mahasamuha.assistant.testutil

import com.mahasamuha.assistant.capabilities.Capability
import com.mahasamuha.assistant.capabilities.CapabilityTool
import com.mahasamuha.assistant.capabilities.Load
import com.mahasamuha.assistant.capabilities.Where
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolCall
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.ConfirmationResult
import com.mahasamuha.assistant.tools.Confirmer
import com.mahasamuha.assistant.tools.PendingAction
import com.mahasamuha.assistant.tools.ReadOnlyTool
import org.json.JSONObject

fun definition(name: String, readOnly: Boolean = true, openWorld: Boolean = false, title: String? = null) = ToolDefinition(
    name = name,
    description = "$name description",
    inputSchema = JSONObject().put("type", "object"),
    annotations = ToolAnnotations(title = title, readOnlyHint = readOnly, openWorldHint = openWorld),
)

class FakeReadOnlyTool(
    name: String,
    openWorld: Boolean = false,
    private val result: String = """{"from":"$name"}""",
    override val allowRemote: Boolean = false,
    override val privateRemoteTools: Set<String> = emptySet(),
    private val failWith: Exception? = null,
) : ReadOnlyTool {
    override val definition = definition(name, readOnly = true, openWorld = openWorld)
    var runs = 0

    override suspend fun run(arguments: JSONObject): String {
        runs++
        failWith?.let { throw it }
        return result
    }
}

class FakeActionTool(
    name: String,
    private val confirmation: Confirmation,
    override val allowRemote: Boolean = false,
    private val prepareFails: Exception? = null,
) : ActionTool {
    override val definition = definition(name, readOnly = false, openWorld = true)
    var performed = 0
    var lastArguments: JSONObject? = null

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        lastArguments = arguments
        prepareFails?.let { throw it }
        return PendingAction(confirmation) {
            performed++
            """{"status":"done"}"""
        }
    }
}

/** Answers every confirmation with [reply] and remembers what it was asked. */
class RecordingConfirmer(var reply: ConfirmationResult = ConfirmationResult.Confirmed) : Confirmer {
    val asked = mutableListOf<Confirmation>()

    override suspend fun confirm(confirmation: Confirmation): ConfirmationResult {
        asked += confirmation
        return reply
    }
}

fun call(name: String, arguments: String = "{}") = ToolCall("call-$name", name, arguments)

fun capability(
    id: String,
    load: Load = Load.EAGER,
    vararg tools: CapabilityTool,
    description: String = "$id description",
) = Capability(id, id.replaceFirstChar { it.uppercase() }, description, load, tools.toList())

fun local(name: String, required: Boolean = true) = CapabilityTool(name, Where.LOCAL, required)

fun remote(name: String, required: Boolean = true) = CapabilityTool(name, Where.REMOTE, required)

fun hybrid(name: String, required: Boolean = true) = CapabilityTool(name, Where.HYBRID, required)
