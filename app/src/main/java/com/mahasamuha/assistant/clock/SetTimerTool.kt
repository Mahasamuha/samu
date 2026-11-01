package com.mahasamuha.assistant.clock

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * `set_timer`: starts a countdown in the phone's Clock app, set at once and reported. The announcement is
 * built from the resolved duration, never from model text; only the label (trimmed and capped) is the
 * model's. Timers can't be listed or cancelled through [AlarmClock], so this tool only starts them.
 *
 * [launch] starts the intent from the session while it's visible (Android 17 blocks background
 * launches); [closePanel] dismisses the session afterwards.
 */
class SetTimerTool(
    private val context: Context,
    private val launch: (Intent) -> Unit,
    private val closePanel: () -> Unit,
) : ActionTool {
    override val definition =
        ToolDefinition(
            name = "set_timer",
            description = "Start a countdown timer in the phone's Clock app. The phone announces it.",
            inputSchema =
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "duration_seconds",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", MIN_SECONDS)
                                    .put("maximum", MAX_SECONDS)
                                    .put("description", "Timer length in seconds."),
                            ).put(
                                "label",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Only when the user names the timer, e.g. \"pasta\". Omit otherwise."),
                            ),
                    ).put("required", JSONArray().put("duration_seconds"))
                    .put("additionalProperties", false),
            // A timer changes nothing that matters and is easy to dismiss; it's set at once and reported.
            annotations = ToolAnnotations(title = "Timer", readOnlyHint = false, destructiveHint = false, openWorldHint = false),
        )

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        val timer = resolveTimer(arguments)
        val seconds = timer.seconds
        val label = timer.label
        val intent =
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        require(intent.resolveActivity(context.packageManager) != null) { "No clock app handles timers" }

        return PendingAction(Confirmation.Report(timer.announcement)) {
            launch(intent)
            closePanel()
            JSONObject().put("status", "timer_set").toString()
        }
    }
}

private const val MIN_SECONDS = 1
private const val MAX_SECONDS = 86_400

/** A validated timer request and what is said about it. */
internal class TimerRequest(
    val seconds: Int,
    val label: String?,
    val announcement: String,
)

/** Validates the model's arguments and builds the announcement from the resolved duration. Throws for bad arguments. */
internal fun resolveTimer(arguments: JSONObject): TimerRequest {
    val seconds = arguments.optInt("duration_seconds", 0)
    require(seconds in MIN_SECONDS..MAX_SECONDS) {
        "duration_seconds must be between $MIN_SECONDS and $MAX_SECONDS (24 hours)"
    }
    val label = cleanLabel(arguments.optString("label"))
    val duration = spokenDuration(seconds)
    val announce = if (label == null) "Timer, $duration." else "${label.replaceFirstChar { it.uppercase() }} timer, $duration."
    return TimerRequest(seconds, label, announce)
}

/** 5400 becomes "1 hour 30 minutes"; zero parts are left out. */
internal fun spokenDuration(seconds: Int): String {
    val parts = listOf(seconds / 3600 to "hour", seconds % 3600 / 60 to "minute", seconds % 60 to "second")
    return parts.filter { (n, _) -> n > 0 }.joinToString(" ") { (n, unit) -> "$n $unit${if (n == 1) "" else "s"}" }
}

/** The model's label, trimmed and capped at 40 characters; null when empty. */
internal fun cleanLabel(raw: String): String? =
    raw
        .trim()
        .take(40)
        .trim()
        .ifEmpty { null }
