package com.mahasamuha.assistant.capabilities

import android.util.Log
import com.mahasamuha.assistant.voice.TAG
import org.json.JSONArray
import org.json.JSONObject

enum class Load { EAGER, LAZY }

enum class Where { LOCAL, REMOTE, HYBRID }

/** Who owns a capability (capabilities/README.md); derived from its tools, never declared. */
enum class Tier { LOCAL, HYBRID, SERVER }

/** A tool a capability groups. An optional tool that is missing doesn't make the capability unavailable. */
data class CapabilityTool(val name: String, val where: Where, val required: Boolean = true)

/** What Samu can do, as a group of tools; the unit the model sees and binds (plan: phase 3.5). */
data class Capability(
    val id: String,
    val title: String,
    val description: String,
    val load: Load,
    val tools: List<CapabilityTool>,
) {
    val tier: Tier
        get() = when {
            tools.all { it.where == Where.LOCAL } -> Tier.LOCAL
            tools.all { it.where == Where.REMOTE } -> Tier.SERVER
            else -> Tier.HYBRID
        }

    companion object {
        /**
         * Parses the `capabilities` array shared by the bundled manifest and the fetched index. An entry
         * with an unknown `load` or `where` (e.g. from a newer server) is skipped with a log, so one bad
         * entry doesn't hide the rest.
         */
        fun parseAll(array: JSONArray): List<Capability> = (0 until array.length()).mapNotNull { i ->
            val entry = array.getJSONObject(i)
            try {
                parse(entry)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Skipping capability ${entry.optString("id")}: ${e.message}")
                null
            }
        }

        private fun parse(entry: JSONObject): Capability {
            val tools = entry.getJSONArray("tools")
            return Capability(
                id = entry.getString("id"),
                title = entry.optString("title"),
                description = entry.optString("description"),
                load = enumValue<Load>(entry.getString("load")),
                tools = (0 until tools.length()).map { j ->
                    val tool = tools.getJSONObject(j)
                    CapabilityTool(
                        name = tool.getString("name"),
                        where = enumValue<Where>(tool.getString("where")),
                        required = tool.optBoolean("required", true),
                    )
                },
            )
        }

        private inline fun <reified E : Enum<E>> enumValue(text: String): E =
            enumValues<E>().firstOrNull { it.name.equals(text, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown ${E::class.java.simpleName} \"$text\"")
    }
}
