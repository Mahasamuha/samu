package com.mahasamuha.assistant.music

import android.util.Log
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.remote.McpClient
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `play_music`: starts music from the user's library in Symfonium, on the phone or a named speaker, and
 * reports it. Names are first resolved through the server's `music_find` (a private remote tool, kept out
 * of the model's list), because Symfonium silently ignores a name it doesn't know. The report is built from
 * the resolved names; the model's own text is used only if that lookup is unavailable.
 */
class PlayMusicTool(
    private val symfonium: Symfonium,
    private val speakers: SpeakerDirectory,
    private val mcp: McpClient,
    private val closePanel: () -> Unit,
) : ActionTool {
    override val privateRemoteTools = setOf(FIND_TOOL)

    override val definition =
        ToolDefinition(
            name = "play_music",
            description = "Start music from the user's library in Symfonium, on the phone or a named speaker. The phone reports it.",
            inputSchema =
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "kind",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(KINDS))
                                    .put(
                                        "description",
                                        "What the user asked for. \"current\" is what's already playing (\"play it in the office\", " +
                                            "\"put this on the Sonos\"), with speaker. \"mix\" is only for music without any name " +
                                            "(\"play some music\", \"shuffle my library\").",
                                    ),
                            ).put(
                                "name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "As the user said it. Required unless kind is mix or current."),
                            ).put(
                                "artist",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Only if the user said it."),
                            ).put(
                                "speaker",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "description",
                                        "A speaker or room the user named, e.g. \"office\"; \"phone\" for this phone. Omit to keep the current output.",
                                    ),
                            ),
                    ).put("required", JSONArray().put("kind"))
                    .put("additionalProperties", false),
            annotations = ToolAnnotations(title = "Play music", readOnlyHint = false, destructiveHint = false, openWorldHint = false),
        )

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        require(symfonium.installed) { "Symfonium isn't installed" }
        val kind = arguments.optString("kind")
        require(kind in KINDS) { "kind must be one of ${KINDS.joinToString()}" }
        val renderer = resolveRenderer(arguments.optString("speaker"), speakers)

        val type: String
        var name: String? = null
        var artist: String? = null
        val sentence: String
        if (kind == "current") {
            require(renderer != null) { "speaker is required to move what's playing" }
            return PendingAction(Confirmation.Report("Moving it ${renderer.destination}.")) {
                symfonium.selectRenderer(renderer.type, renderer.identifier)
                // As in music_control: a speaker doesn't resume when the panel's audio focus is released.
                delay(RENDERER_SETTLE_MS)
                symfonium.command("play")
                closePanel()
                JSONObject().put("status", "moved").toString()
            }
        } else if (kind == "mix") {
            type = "song_mix"
            sentence = "Playing a mix from your library"
        } else {
            val asked = arguments.optString("name").trim()
            require(asked.isNotEmpty()) { "name is required unless kind is mix" }
            val askedArtist = arguments.optString("artist").trim().ifEmpty { null }
            val item = find(kind, asked, askedArtist)
            type = item?.kind ?: kind
            name = item?.name ?: asked
            artist = item?.artist ?: askedArtist
            sentence = "Playing " +
                when (type) {
                    "artist" -> name
                    "playlist" -> "the $name playlist"
                    "genre" -> "${name.lowercase()}, shuffled"
                    else -> if (artist != null) "$name by $artist" else name
                }
        }
        val text = sentence + (renderer?.let { " " + it.spoken } ?: "") + "."

        return PendingAction(Confirmation.Report(text)) {
            if (renderer != null) {
                symfonium.selectRenderer(renderer.type, renderer.identifier)
                // Assumption to verify on device: the output switch must settle before the start is sent.
                delay(RENDERER_SETTLE_MS)
            }
            symfonium.start(type, name, artist, album = null, shuffle = type == "genre")
            closePanel()
            JSONObject().put("status", "playing").toString()
        }
    }

    private class Item(
        val kind: String,
        val name: String,
        val artist: String?,
    )

    /** Looks the name up on the server. Null means unavailable, so the caller falls back to the model's text. */
    private suspend fun find(
        kind: String,
        name: String,
        artist: String?,
    ): Item? {
        val result =
            try {
                val query = JSONObject().put("kind", kind).put("name", name).apply { artist?.let { put("artist", it) } }
                withTimeoutOrNull(FIND_TIMEOUT_MS) { mcp.callTool(FIND_TOOL, query) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server's messages name the failure ("Plex returned 401"), never the music asked for.
                Log.w(TAG, "music_find failed: ${e.message}")
                null
            }
        val json =
            try {
                result?.let { JSONObject(it) }
            } catch (e: JSONException) {
                null
            }
        if (json == null) {
            Log.w(TAG, "music_find unavailable; using the names as given")
            return null
        }
        when (json.optString("status")) {
            "found" -> {
                json.optJSONObject("item")?.let { item ->
                    val found = item.optString("name")
                    if (found.isNotEmpty()) {
                        return Item(item.optString("kind").ifEmpty { kind }, found, item.optString("artist").ifEmpty { null })
                    }
                }
            }

            "not_found" -> {
                throw IllegalArgumentException(
                    "Nothing called $name in the library. Closest: " + suggestions(json.optJSONArray("suggestions")),
                )
            }
        }
        Log.w(TAG, "music_find returned an unexpected result; using the names as given")
        return null
    }

    private fun suggestions(array: JSONArray?): String {
        if (array == null || array.length() == 0) return "nothing"
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.joinToString("; ") { s ->
            val by = s.optString("artist").ifEmpty { null }?.let { " by $it" } ?: ""
            "${s.optString("name")}$by (${s.optString("kind")})"
        }
    }

    private companion object {
        const val FIND_TOOL = "music_find"
        const val FIND_TIMEOUT_MS = 4_000L
        const val RENDERER_SETTLE_MS = 1_500L
        val KINDS = listOf("album", "song", "artist", "playlist", "genre", "mix", "current")
    }
}

/** An output chosen for playback; [name] is null for this phone. */
internal class Renderer(
    val type: Int,
    val identifier: String?,
    val name: String?,
) {
    /** "on the phone" or "in the office". */
    val spoken get() = if (name == null) "on the phone" else "in the $name"

    /** "to the phone" or "to the office". */
    val destination get() = "to the ${name ?: "phone"}"
}

/** Null when [speaker] is blank (keep the current output). Speaker errors are written for the model. */
internal suspend fun resolveRenderer(
    speaker: String,
    speakers: SpeakerDirectory,
): Renderer? {
    val asked = speaker.trim()
    if (asked.isEmpty()) return null
    if (asked.lowercase() in PHONE_NAMES) return Renderer(0, null, null)
    val chosen = SpeakerDirectory.match(asked, speakers.speakers())
    return Renderer(chosen.type, chosen.identifier, chosen.name)
}

private val PHONE_NAMES = setOf("phone", "this phone", "my phone")
