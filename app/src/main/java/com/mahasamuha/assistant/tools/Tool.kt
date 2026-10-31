package com.mahasamuha.assistant.tools

import android.util.Log
import com.mahasamuha.assistant.llm.ToolCall
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

/** Something the model can call. Runs on the phone; a [ReadOnlyTool] or an [ActionTool] (plan §4b). */
sealed interface Tool {
    val definition: ToolDefinition

    /**
     * For local tools: whether a remote tool with the same name may replace this one (plan §4b). Off by
     * default, so the remote one is dropped, even in a session where this tool's permission is off.
     */
    val allowRemote: Boolean get() = false

    /** Remote tools this local tool calls itself (plan §4b). They're kept out of the model's tool list. */
    val privateRemoteTools: Set<String> get() = emptySet()
}

/** No side effects, so it runs without asking. Its definition must set `readOnlyHint`. */
interface ReadOnlyTool : Tool {
    /** Returns the result for the model, usually JSON. Throw for failures; the registry reports them to the model. */
    suspend fun run(arguments: JSONObject): String
}

/**
 * Changes something outside the conversation (sends a text, places a call). The tool only prepares the
 * action; the registry gets it confirmed or announced before performing it (a harmless one is performed at
 * once and reported), so no tool can skip that step.
 */
interface ActionTool : Tool {
    /** Validates and resolves the arguments. Throw for failures, as in [ReadOnlyTool.run]. */
    suspend fun prepare(arguments: JSONObject): PendingAction
}

/** A prepared action. [confirmation]'s text is built from the resolved arguments, never taken from the model. */
class PendingAction(val confirmation: Confirmation, private val action: suspend () -> String) {
    suspend fun perform(): String = action()
}

sealed interface Confirmation {
    val text: String

    /** Read back and wait for a yes ("Text Sam Lee: I'm running late. Send it?"). */
    data class Ask(override val text: String) : Confirmation

    /**
     * Say what's happening and go ahead unless cancelled ("Calling Mom, mobile"). Once the session has
     * read outside content, the registry asks [question] instead ("Call Mom, mobile?").
     */
    data class Announce(override val text: String, val question: String) : Confirmation

    /**
     * Harmless and easy to undo (a timer, an alarm): performed at once and said while it happens
     * ("Timer, 10 minutes"), with nothing to cancel. Stays a report after outside content, since an
     * injected timer does no harm.
     */
    data class Report(override val text: String) : Confirmation
}

sealed interface ConfirmationResult {
    data object Confirmed : ConfirmationResult

    /** Cancelled, or the user said something other than yes; [userSaid] goes back to the model. */
    data class Declined(val userSaid: String?) : ConfirmationResult
}

/** Asks the user; implemented by the session, which owns speech and the panel. */
fun interface Confirmer {
    suspend fun confirm(confirmation: Confirmation): ConfirmationResult
}

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName = LinkedHashMap<String, Tool>()

    init {
        require(tools.map { it.definition.name }.toSet().size == tools.size) { "Tool names must be unique" }
        tools.forEach(::add)
    }

    /** What the model may call right now; grows as capabilities are bound, so read it once per model round. */
    val definitions: List<ToolDefinition> get() = byName.values.map { it.definition }

    val names: Set<String> get() = byName.keys.toSet()

    /** Adds tools to the live registry (late binding). A name that is already bound is skipped. */
    fun bind(tools: List<Tool>) {
        require(tools.map { it.definition.name }.toSet().size == tools.size) { "Tool names must be unique" }
        tools.filter { it.definition.name !in byName }.forEach(::add)
    }

    private fun add(tool: Tool) {
        require((tool is ReadOnlyTool) == tool.definition.annotations.readOnlyHint) {
            "${tool.definition.name}: readOnlyHint must be set exactly for read-only tools"
        }
        byName[tool.definition.name] = tool
    }

    /**
     * Set once a read-only `openWorldHint` tool has run (web search, weather). Its results are text from
     * outside the phone that may carry instructions, so from then on this session asks instead of announcing.
     */
    private var readOutsideContent = false

    /** Never throws (except cancellation): failures become an error result the model can explain. */
    suspend fun execute(call: ToolCall, confirmer: Confirmer): String {
        val tool = byName[call.name] ?: return error("Unknown tool ${call.name}. If it belongs to a capability listed under Capabilities, call use_capability first.")
        val arguments = try {
            JSONObject(call.arguments.ifBlank { "{}" })
        } catch (e: JSONException) {
            return error("Arguments were not valid JSON")
        }
        return try {
            when (tool) {
                is ReadOnlyTool -> {
                    if (tool.definition.annotations.openWorldHint) readOutsideContent = true
                    tool.run(arguments)
                }
                is ActionTool -> {
                    val action = tool.prepare(arguments)
                    val confirmation = action.confirmation.let {
                        if (it is Confirmation.Announce && readOutsideContent) Confirmation.Ask(it.question) else it
                    }
                    when (val result = confirmer.confirm(confirmation)) {
                        ConfirmationResult.Confirmed -> action.perform()
                        is ConfirmationResult.Declined -> notConfirmed(result.userSaid)
                    }
                }
            }.also { Log.i(TAG, "Tool ${call.name} -> ${outcome(it)}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tool ${call.name} failed", e)
            error(e.message ?: "Tool failed")
        }
    }

    // Scoped explicitly: without the note, a small model treats one "no" as declining later requests too.
    private fun notConfirmed(userSaid: String?) = JSONObject()
        .put("status", "not_confirmed")
        .put("note", "Only this action was not performed. It says nothing about later requests.")
        .apply { userSaid?.let { put("user_said", it) } }
        .toString()

    private fun error(message: String) = JSONObject().put("error", message).toString()

    // Only the status or error for the log: arguments and results carry message text, numbers and contacts.
    private fun outcome(result: String): String = try {
        JSONObject(result).run { optString("status").ifEmpty { optString("error").ifEmpty { "ok" } } }
    } catch (e: JSONException) {
        "ok"
    }
}
