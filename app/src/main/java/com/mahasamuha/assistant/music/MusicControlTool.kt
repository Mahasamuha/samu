package com.mahasamuha.assistant.music

import android.content.Context
import android.media.AudioManager
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * `music_control`: play, pause, skip, volume, and moving playback to another speaker, all through
 * Symfonium. Done at once and reported, since each is easy to undo.
 */
class MusicControlTool(
    private val context: Context,
    private val symfonium: Symfonium,
    private val speakers: SpeakerDirectory,
    private val closePanel: () -> Unit,
    private val keepPaused: () -> Unit,
) : ActionTool {
    override val definition =
        ToolDefinition(
            name = "music_control",
            description = "Control music playback: play, pause, skip, volume, or move it to another speaker. The phone reports it.",
            inputSchema =
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(ACTIONS))
                                    .put("description", "What to do."),
                            ).put(
                                "volume",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 0)
                                    .put("maximum", 100)
                                    .put("description", "Percent, for set_volume."),
                            ).put(
                                "steps",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", 10)
                                    .put(
                                        "description",
                                        "For volume_up / volume_down: how many steps, when the user says a number (\"up by 3\"). Omit otherwise.",
                                    ),
                            ).put(
                                "speaker",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "For move_to_speaker: the speaker or room the user named, or \"phone\"."),
                            ),
                    ).put("required", JSONArray().put("action"))
                    .put("additionalProperties", false),
            annotations = ToolAnnotations(title = "Music control", readOnlyHint = false, destructiveHint = false, openWorldHint = false),
        )

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        require(symfonium.installed) { "Symfonium isn't installed" }
        val action = arguments.optString("action")
        require(action in ACTIONS) { "action must be one of ${ACTIONS.joinToString()}" }

        val text: String
        val run: suspend () -> Unit
        when (action) {
            "play" -> {
                text = "Resuming."
                run = { symfonium.command("play") }
            }

            "pause" -> {
                text = "Paused."
                run = {
                    // The panel's focus has usually paused it already, so the session must end in a way that
                    // doesn't resume it. The command still covers the case where the focus was refused.
                    keepPaused()
                    symfonium.command("pause")
                }
            }

            "next" -> {
                text = "Next track."
                run = { symfonium.command("next") }
            }

            "previous" -> {
                text = "Previous track."
                run = { symfonium.command("previous") }
            }

            "set_volume" -> {
                require(arguments.has("volume")) { "volume (0 to 100) is required for set_volume" }
                val level = arguments.optInt("volume", -1)
                require(level in 0..100) { "volume must be between 0 and 100" }
                text = "Volume $level."
                run = { symfonium.command("volume", level) }
            }

            "volume_up" -> {
                text = "Turning it up."
                run = { adjustVolume(AudioManager.ADJUST_RAISE, steps(arguments)) }
            }

            "volume_down" -> {
                text = "Turning it down."
                run = { adjustVolume(AudioManager.ADJUST_LOWER, steps(arguments)) }
            }

            else -> {
                val renderer = resolveRenderer(arguments.optString("speaker"), speakers)
                require(renderer != null) { "speaker is required for move_to_speaker" }
                text = "Moving it ${renderer.destination}."
                run = {
                    symfonium.selectRenderer(renderer.type, renderer.identifier)
                    // The panel's audio focus has paused Symfonium, and a speaker doesn't resume when the focus
                    // is released (only the phone does), so playback is restarted once the switch settles.
                    delay(RENDERER_SETTLE_MS)
                    symfonium.command("play")
                }
            }
        }
        return PendingAction(Confirmation.Report(text)) {
            run()
            closePanel()
            JSONObject().put("status", "done").toString()
        }
    }

    // To verify on device: with no stream type given, the system should route this to the active media
    // session, including a remote (cast) one, so it changes the speaker's volume and not the phone's.
    private fun steps(arguments: JSONObject): Int {
        val steps = arguments.optInt("steps", DEFAULT_STEPS)
        require(steps in 1..10) { "steps must be between 1 and 10" }
        return steps
    }

    private fun adjustVolume(
        direction: Int,
        steps: Int,
    ) {
        val audio = context.getSystemService(AudioManager::class.java)
        repeat(steps) { audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, 0) }
    }

    private companion object {
        const val DEFAULT_STEPS = 2
        const val RENDERER_SETTLE_MS = 1_500L
        val ACTIONS = listOf("play", "pause", "next", "previous", "volume_up", "volume_down", "set_volume", "move_to_speaker")
    }
}
