package com.mahasamuha.assistant.remote

import android.util.Log
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import com.mahasamuha.assistant.tools.ReadOnlyTool
import com.mahasamuha.assistant.tools.Tool
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** What a remote tool needs from the MCP client: run the named tool with these arguments and get its text. */
typealias CallTool = suspend (name: String, arguments: JSONObject) -> String

/**
 * A tool served by the MCP server (plan §4b), from its definition in the capability index. The call goes
 * through [callTool]; [onStale] runs when the server rejects the call as an unknown or disabled tool, or for
 * arguments it won't accept, i.e. the index may be out of date. A model's own bad arguments also trigger
 * it, which is acceptable since a refresh is cheap.
 */
fun remoteTool(
    definition: ToolDefinition,
    callTool: CallTool,
    onStale: () -> Unit,
): Tool =
    if (definition.annotations.readOnlyHint) {
        RemoteReadOnlyTool(
            definition,
            callTool,
            onStale,
        )
    } else {
        RemoteActionTool(definition, callTool, onStale)
    }

/** Calls the tool, reporting a stale index before the failure goes to the model. */
private suspend fun call(
    callTool: CallTool,
    definition: ToolDefinition,
    arguments: JSONObject,
    onStale: () -> Unit,
): String =
    try {
        callTool(definition.name, arguments)
    } catch (e: McpStaleToolException) {
        Log.w(TAG, "Server rejected ${definition.name} (${e.message}); refreshing the capability index")
        onStale()
        throw e
    }

/**
 * A remote read-only tool that replaced a local one of the same name (plan §4b): the local one answers if
 * the remote call fails, e.g. Pirate Weather unreachable → the phone's Open-Meteo get_weather.
 */
class FallbackTool(
    private val remote: ReadOnlyTool,
    private val local: ReadOnlyTool,
) : ReadOnlyTool {
    override val definition: ToolDefinition = remote.definition

    override suspend fun run(arguments: JSONObject): String =
        try {
            remote.run(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Remote ${definition.name} failed; using the local one", e)
            local.run(arguments)
        }
}

private class RemoteReadOnlyTool(
    override val definition: ToolDefinition,
    private val callTool: CallTool,
    private val onStale: () -> Unit,
) : ReadOnlyTool {
    override suspend fun run(arguments: JSONObject): String = call(callTool, definition, arguments, onStale)
}

/**
 * A remote tool with side effects always asks first (plan §4b): the phone has no tool-specific read-back
 * for it, so the question names the tool and its arguments.
 */
private class RemoteActionTool(
    override val definition: ToolDefinition,
    private val callTool: CallTool,
    private val onStale: () -> Unit,
) : ActionTool {
    override suspend fun prepare(arguments: JSONObject): PendingAction {
        val name = definition.annotations.title ?: definition.name.replace('_', ' ')
        val details = arguments.keys().asSequence().joinToString(", ") { key -> "$key ${arguments.opt(key)}" }
        val question = if (details.isEmpty()) "Run $name?" else "Run $name with $details?"
        return PendingAction(Confirmation.Ask(question)) { call(callTool, definition, arguments, onStale) }
    }
}
