package com.mahasamuha.assistant.capabilities

import android.util.Log
import com.mahasamuha.assistant.voice.TAG
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Bounds on what a capability index (or the bundled manifest) may contain. The server is trusted but
 * not blindly: these keep a malformed or oversized index from growing the prompt or the cache. They are
 * several times today's largest values (capabilities/ and skills/ in the repo). An entry over a per-field
 * limit is skipped whole, never truncated; entries past a count limit are dropped.
 *
 * Outgrowing the count limits is a design signal, not a reason to raise them: that many capabilities
 * or skills listed up front would cost the model coherence. The next step then is a discovery round,
 * where the model searches for a capability ("can I do X?") instead of seeing every one listed.
 */
object IndexLimits {
    const val MAX_CAPABILITIES = 64
    const val MAX_TOOLS_PER_CAPABILITY = 32
    const val MAX_TOOL_DEFINITIONS = 256
    const val MAX_SKILLS = 64
    const val MAX_CAPABILITY_DESCRIPTION = 1024
    const val MAX_TOOL_DESCRIPTION = 2048
    const val MAX_INPUT_SCHEMA = 8 * 1024
    const val MAX_SKILL_DESCRIPTION = 1024
    const val MAX_SKILL_INSTRUCTIONS = 8 * 1024

    /** Throws [IllegalArgumentException] naming [field] when [value] is longer than [max]. */
    fun checkLength(
        field: String,
        value: String,
        max: Int,
    ) {
        require(value.length <= max) { "$field is ${value.length} characters (limit $max)" }
    }
}

/**
 * Parses each object in [array] with [parse]. An entry that isn't an object, fails to parse or is over a
 * limit ([JSONException], [IllegalArgumentException]) is skipped and logged with its id or name; the rest
 * are kept. Entries past [max] are dropped, logged once.
 */
fun <T> parseEntries(
    array: JSONArray,
    what: String,
    max: Int,
    parse: (JSONObject) -> T,
): List<T> {
    if (array.length() > max) Log.w(TAG, "Index has ${array.length()} $what; keeping the first $max")
    return (0 until minOf(array.length(), max)).mapNotNull { i ->
        try {
            parse(array.getJSONObject(i))
        } catch (e: JSONException) {
            Log.w(TAG, "Skipping $what entry ${entryLabel(array, i)}: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Skipping $what entry ${entryLabel(array, i)}: ${e.message}")
            null
        }
    }
}

private fun entryLabel(
    array: JSONArray,
    i: Int,
): String {
    val entry = array.optJSONObject(i) ?: return "#$i"
    return entry.optString("id").ifEmpty { entry.optString("name") }.ifEmpty { "#$i" }
}
