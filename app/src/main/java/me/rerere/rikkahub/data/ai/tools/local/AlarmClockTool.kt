package me.rerere.rikkahub.data.ai.tools.local

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

sealed interface AlarmClockIntentSpec {
    data class Alarm(
        val hour: Int,
        val minute: Int,
        val message: String = "",
        val days: List<Int> = emptyList(),
        val skipUi: Boolean = true
    ) : AlarmClockIntentSpec

    data class Timer(
        val seconds: Int,
        val message: String = "",
        val skipUi: Boolean = true
    ) : AlarmClockIntentSpec

    data object ShowClock : AlarmClockIntentSpec
}

internal fun parseAlarmClockAction(args: JsonObject): Result<AlarmClockIntentSpec> = runCatching {
    val action = args["action"]?.jsonPrimitive?.content ?: error("action is required")
    when (action) {
        "set_alarm" -> {
            val hour = args["hour"]?.jsonPrimitive?.intOrNull ?: error("hour is required for set_alarm")
            require(hour in 0..23) { "hour must be between 0 and 23" }
            val minute = args["minute"]?.jsonPrimitive?.intOrNull ?: 0
            require(minute in 0..59) { "minute must be between 0 and 59" }
            val message = args["message"]?.jsonPrimitive?.content ?: ""
            val days = args["days"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
            val skipUi = args["skip_ui"]?.jsonPrimitive?.booleanOrNull ?: true
            AlarmClockIntentSpec.Alarm(hour, minute, message, days, skipUi)
        }
        "set_timer" -> {
            val seconds = args["seconds"]?.jsonPrimitive?.intOrNull ?: error("seconds is required for set_timer")
            require(seconds > 0) { "seconds must be greater than 0" }
            val message = args["message"]?.jsonPrimitive?.content ?: ""
            val skipUi = args["skip_ui"]?.jsonPrimitive?.booleanOrNull ?: true
            AlarmClockIntentSpec.Timer(seconds, message, skipUi)
        }
        "show_clock", "show_alarms", "show_timers" -> {
            AlarmClockIntentSpec.ShowClock
        }
        else -> error("Unknown action: $action")
    }
}

internal fun buildAlarmClockTool(context: Context): Tool = Tool(
    name = "alarm_clock",
    description = """
        Manage system alarms and countdown timers on the user's device.
        Actions:
        - 'set_alarm': Set an alarm at a specific hour (0-23) and minute (0-59), with optional message and repeat days (1=Sun, 2=Mon...7=Sat).
        - 'set_timer': Set a countdown timer for a duration in seconds, with optional message.
        - 'show_clock': Open the device's clock app.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action to perform: 'set_alarm', 'set_timer', or 'show_clock'")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("set_alarm"))
                        add(JsonPrimitive("set_timer"))
                        add(JsonPrimitive("show_clock"))
                    })
                })
                put("hour", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hour of day (0-23) for alarm")
                })
                put("minute", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minute (0-59) for alarm (default 0)")
                })
                put("seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Countdown duration in seconds (e.g. 300 for 5 minutes)")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Label or description for the alarm or timer (e.g. 'Meeting', 'Pasta')")
                })
                put("days", buildJsonObject {
                    put("type", "array")
                    put("description", "Days of week to repeat (1=Sunday, 2=Monday, ..., 7=Saturday)")
                    put("items", buildJsonObject { put("type", "integer") })
                })
                put("skip_ui", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to set directly in background without opening clock UI (default true)")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val parsed = parseAlarmClockAction(args.jsonObject)
        if (parsed.isFailure) {
            val errorPayload = buildJsonObject {
                put("error", true)
                put("message", parsed.exceptionOrNull()?.message ?: "Invalid arguments")
            }
            return@Tool listOf(
                UIMessagePart.Text(errorPayload.toString())
            )
        }

        try {
            val message = when (val spec = parsed.getOrThrow()) {
                is AlarmClockIntentSpec.Alarm -> {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
                        if (spec.message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, spec.message)
                        if (spec.days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(spec.days))
                        putExtra(AlarmClock.EXTRA_SKIP_UI, spec.skipUi)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    val timeStr = "%02d:%02d".format(spec.hour, spec.minute)
                    val labelStr = if (spec.message.isNotBlank()) " ('${spec.message}')" else ""
                    "Alarm set successfully for $timeStr$labelStr"
                }
                is AlarmClockIntentSpec.Timer -> {
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, spec.seconds)
                        if (spec.message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, spec.message)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, spec.skipUi)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    val labelStr = if (spec.message.isNotBlank()) " ('${spec.message}')" else ""
                    "Timer set successfully for ${spec.seconds} seconds$labelStr"
                }
                is AlarmClockIntentSpec.ShowClock -> {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "Opened system clock app"
                }
            }
            val payload = buildJsonObject {
                put("success", true)
                put("message", message)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: ActivityNotFoundException) {
            val payload = buildJsonObject {
                put("error", true)
                put("message", "No clock application found on device to handle this action.")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", true)
                put("message", e.message ?: "Unknown error")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    }
)
