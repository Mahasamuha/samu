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
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Locale

/**
 * `set_alarm`: sets a one-time or repeating alarm in the phone's Clock app, set at once and reported. The
 * announcement is built from the resolved values, never from model text (except the label). It always
 * says "today" or "tomorrow" for a one-time alarm, so an AM/PM slip is audible. Alarms can't be listed
 * or cancelled through [AlarmClock], so this tool only sets them.
 *
 * [launch] starts the intent from the session while it's visible (Android 17 blocks background
 * launches); [closePanel] dismisses the session afterwards. [now] is the clock for today/tomorrow.
 */
class SetAlarmTool(
    private val context: Context,
    private val launch: (Intent) -> Unit,
    private val closePanel: () -> Unit,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : ActionTool {
    override val definition =
        ToolDefinition(
            name = "set_alarm",
            description = "Set an alarm in the phone's Clock app. The phone announces it.",
            inputSchema =
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "hour",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 0)
                                    .put("maximum", 23)
                                    .put("description", "Hour, 24-hour clock."),
                            ).put(
                                "minute",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 0)
                                    .put("maximum", 59),
                            ).put(
                                "days",
                                JSONObject()
                                    .put("type", "array")
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("enum", JSONArray(week.map { it.id })),
                                    ).put("description", "Only for a repeating alarm. Omit for a one-time alarm."),
                            ).put(
                                "label",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Only when the user names the alarm, e.g. \"wake up\". Omit otherwise."),
                            ),
                    ).put("required", JSONArray().put("hour").put("minute"))
                    .put("additionalProperties", false),
            // An alarm changes nothing that matters and is easy to delete; it's set at once and reported.
            annotations = ToolAnnotations(title = "Alarm", readOnlyHint = false, destructiveHint = false, openWorldHint = false),
        )

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        val alarm = resolveAlarm(arguments, now())
        val hour = alarm.hour
        val minute = alarm.minute
        val label = alarm.label
        val days = alarm.days

        val intent =
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it.calendar }))
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        require(intent.resolveActivity(context.packageManager) != null) { "No clock app handles alarms" }

        return PendingAction(Confirmation.Report(alarm.announcement)) {
            launch(intent)
            closePanel()
            JSONObject().put("status", "alarm_set").toString()
        }
    }
}

internal class Day(
    val id: String,
    val calendar: Int,
    val plural: String,
)

/** In week order, Monday first. */
private val week =
    listOf(
        Day("mon", Calendar.MONDAY, "Mondays"),
        Day("tue", Calendar.TUESDAY, "Tuesdays"),
        Day("wed", Calendar.WEDNESDAY, "Wednesdays"),
        Day("thu", Calendar.THURSDAY, "Thursdays"),
        Day("fri", Calendar.FRIDAY, "Fridays"),
        Day("sat", Calendar.SATURDAY, "Saturdays"),
        Day("sun", Calendar.SUNDAY, "Sundays"),
    )

/** A validated alarm request ([days] in week order, empty for a one-time alarm) and what is said about it. */
internal class AlarmRequest(
    val hour: Int,
    val minute: Int,
    val label: String?,
    val days: List<Day>,
    val announcement: String,
)

/** Validates the model's arguments and builds the announcement from the resolved values. Throws for bad arguments. */
internal fun resolveAlarm(
    arguments: JSONObject,
    now: ZonedDateTime,
): AlarmRequest {
    require(arguments.has("hour") && arguments.optInt("hour", -1) in 0..23) { "hour must be 0 to 23" }
    require(arguments.has("minute") && arguments.optInt("minute", -1) in 0..59) { "minute must be 0 to 59" }
    val hour = arguments.getInt("hour")
    val minute = arguments.getInt("minute")
    val label = cleanLabel(arguments.optString("label"))

    val ids = arguments.optJSONArray("days")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
    val unknown = ids.filter { id -> week.none { it.id == id } }
    require(unknown.isEmpty()) { "Unknown day: ${unknown.first()}; use mon, tue, wed, thu, fri, sat or sun" }
    val days = week.filter { it.id in ids }

    // "6:30 AM tomorrow" for a one-time alarm, "7 AM, weekdays" for a repeating one.
    val spoken = spokenTime(hour, minute) + if (days.isEmpty()) " ${oneTimeDay(hour, minute, now)}" else ", ${spokenDays(days)}"
    val announce = if (label == null) "Alarm, $spoken." else "${label.replaceFirstChar { it.uppercase() }} alarm, $spoken."
    return AlarmRequest(hour, minute, label, days, announce)
}

/** "today" when the time is still ahead of the local clock, otherwise "tomorrow". */
private fun oneTimeDay(
    hour: Int,
    minute: Int,
    now: ZonedDateTime,
): String = if (LocalTime.of(hour, minute).isAfter(now.toLocalTime())) "today" else "tomorrow"

/** 6:30 becomes "6:30 AM", 7:00 "7 AM", 0:00 "12 AM". */
private fun spokenTime(
    hour: Int,
    minute: Int,
): String {
    val hour12 = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "AM" else "PM"
    return if (minute == 0) "$hour12 $suffix" else "$hour12:${"%02d".format(Locale.US, minute)} $suffix"
}

/** "every day", "weekdays", "weekends", or plural names in week order: "Mondays and Thursdays". */
private fun spokenDays(days: List<Day>): String =
    when (days.map { it.id }.toSet()) {
        week.map { it.id }.toSet() -> {
            "every day"
        }

        setOf("mon", "tue", "wed", "thu", "fri") -> {
            "weekdays"
        }

        setOf("sat", "sun") -> {
            "weekends"
        }

        else -> {
            val names = days.map { it.plural }
            if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }
