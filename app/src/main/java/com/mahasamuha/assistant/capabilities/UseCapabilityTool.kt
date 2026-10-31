package com.mahasamuha.assistant.capabilities

import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ReadOnlyTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * `use_capability`: binds a lazy capability's tools into the session and returns its skills' instructions.
 * Local and read-only, and not open-world: binding reads nothing from outside, so it must not turn
 * announced actions into asked ones. Nothing is fetched; the definitions are already on the phone.
 */
class UseCapabilityTool(private val catalog: SessionCatalog) : ReadOnlyTool {
    override val definition = ToolDefinition(
        name = NAME,
        description = "Make a capability listed under Capabilities available. Its tools can be called from the next step.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string").put("description", "The capability id, as listed.")))
            .put("required", JSONArray().put("id")),
        annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )

    override suspend fun run(arguments: JSONObject): String {
        val id = arguments.optString("id").trim()
        val result = catalog.bind(id)
            ?: throw IllegalArgumentException("Unknown capability \"$id\". Available: ${catalog.lazyCapabilities.joinToString { it.id }}")
        return JSONObject()
            .put("capability", result.capability)
            .put("tools", JSONArray(result.toolNames))
            .put("instructions", result.instructions)
            .apply { if (result.alreadyBound) put("note", "Already available.") }
            .toString()
    }

    companion object {
        const val NAME = "use_capability"
    }
}
