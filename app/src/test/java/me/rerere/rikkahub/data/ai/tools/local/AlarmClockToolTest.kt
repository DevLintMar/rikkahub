package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmClockToolTest {

    @Test
    fun parseSetAlarmValidArgs() {
        val params = buildJsonObject {
            put("action", "set_alarm")
            put("hour", 7)
            put("minute", 30)
            put("message", "起床晨会")
            put("days", buildJsonArray {
                add(JsonPrimitive(2)) // 周一
                add(JsonPrimitive(3)) // 周二
                add(JsonPrimitive(4)) // 周三
                add(JsonPrimitive(5)) // 周四
                add(JsonPrimitive(6)) // 周五
            })
            put("skip_ui", true)
        }

        val result = parseAlarmClockAction(params)
        assertTrue(result.isSuccess)
        val spec = result.getOrNull() as? AlarmClockIntentSpec.Alarm
        assertEquals(7, spec?.hour)
        assertEquals(30, spec?.minute)
        assertEquals("起床晨会", spec?.message)
        assertEquals(listOf(2, 3, 4, 5, 6), spec?.days)
        assertEquals(true, spec?.skipUi)
    }

    @Test
    fun parseSetTimerValidArgs() {
        val params = buildJsonObject {
            put("action", "set_timer")
            put("seconds", 300)
            put("message", "煮鸡蛋")
        }

        val result = parseAlarmClockAction(params)
        assertTrue(result.isSuccess)
        val spec = result.getOrNull() as? AlarmClockIntentSpec.Timer
        assertEquals(300, spec?.seconds)
        assertEquals("煮鸡蛋", spec?.message)
        assertEquals(true, spec?.skipUi) // 默认 skip_ui = true
    }

    @Test
    fun parseShowClockValidArgs() {
        val params = buildJsonObject {
            put("action", "show_clock")
        }

        val result = parseAlarmClockAction(params)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is AlarmClockIntentSpec.ShowClock)
    }

    @Test
    fun parseInvalidArgsRejectsOutOfBounds() {
        val invalidHour = buildJsonObject {
            put("action", "set_alarm")
            put("hour", 25)
            put("minute", 0)
        }
        assertTrue(parseAlarmClockAction(invalidHour).isFailure)

        val invalidSeconds = buildJsonObject {
            put("action", "set_timer")
            put("seconds", -5)
        }
        assertTrue(parseAlarmClockAction(invalidSeconds).isFailure)
    }
}
