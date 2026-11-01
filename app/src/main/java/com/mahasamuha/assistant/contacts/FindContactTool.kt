package com.mahasamuha.assistant.contacts

import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ReadOnlyTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * `find_contact`: resolves a spoken name to contacts and their numbers. Only the matches leave the
 * phone, never the address book (plan §5a).
 */
class FindContactTool(
    private val contacts: ContactDirectory,
) : ReadOnlyTool {
    override val definition =
        ToolDefinition(
            name = "find_contact",
            description =
                "Look up the user's contacts by name or nickname. Returns matching contacts with their " +
                    "phone numbers and number types (mobile, home, work). If nothing matches, returns contacts whose names " +
                    "sound similar, marked approximate.",
            inputSchema =
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "name",
                            JSONObject()
                                .put("type", "string")
                                .put("description", "The name as the user said it, e.g. \"Sam\" or \"Sam Lee\"."),
                        ),
                    ).put("required", JSONArray().put("name"))
                    .put("additionalProperties", false),
            annotations = ToolAnnotations(title = "Find contact", readOnlyHint = true, openWorldHint = false),
        )

    override suspend fun run(arguments: JSONObject): String {
        val name = arguments.optString("name").trim()
        require(name.isNotEmpty()) { "A name is required" }
        val exact = contacts.find(name)
        // Speech recognition often misspells uncommon names; offer sound-alikes rather than nothing.
        val matches = exact.ifEmpty { contacts.soundAlike(name) }
        return JSONObject()
            .apply {
                if (exact.isEmpty() && matches.isNotEmpty()) {
                    put("approximate", true)
                    put(
                        "note",
                        "These sound like the name the user said, which was probably misheard. Reply only " +
                            "\"Did you mean\" and their full names, e.g. \"Did you mean Siobhan Kelly or Sean Kelly?\", " +
                            "without saying the name wasn't found, and act only after the user picks one.",
                    )
                }
            }.put(
                "contacts",
                JSONArray().apply {
                    matches.forEach { contact ->
                        put(
                            JSONObject()
                                .put("name", contact.name)
                                .put(
                                    "numbers",
                                    JSONArray().apply {
                                        contact.numbers.forEach {
                                            put(
                                                JSONObject().put("number", it.number).put("type", it.label.lowercase()),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            ).toString()
    }
}
