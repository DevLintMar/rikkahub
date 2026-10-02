package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.ui.components.charts.ChartSpec
import me.rerere.rikkahub.ui.components.charts.ChartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChartDisplayToolTest {

    @Test
    fun `validateChartArgs tolerates bare array for x_axis`() {
        val tool = buildChartDisplayTool()
        // 模型误把 x_axis 直接传成数组: ["周一", "周二", "周三"]
        val params = buildJsonObject {
            put("style", "line")
            put("title", "测试图表")
            put("x_axis", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("周一"))
                add(kotlinx.serialization.json.JsonPrimitive("周二"))
                add(kotlinx.serialization.json.JsonPrimitive("周三"))
            })
            put("series", buildJsonArray {
                add(buildJsonObject {
                    put("name", "数值")
                    put("values", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(10))
                        add(kotlinx.serialization.json.JsonPrimitive(20))
                        add(kotlinx.serialization.json.JsonPrimitive(30))
                    })
                })
            })
        }

        val error = validateChartArgs(params)
        assertNull("Bare array for x_axis should be auto-wrapped without validation error", error)
    }

    @Test
    fun `ChartSpec fromJson correctly parses bare array x_axis`() {
        val params = buildJsonObject {
            put("style", "bar")
            put("title", "心情指数")
            put("x_axis", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("周一"))
                add(kotlinx.serialization.json.JsonPrimitive("周二"))
            })
            put("series", buildJsonArray {
                add(buildJsonObject {
                    put("name", "开心度")
                    put("values", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(80))
                        add(kotlinx.serialization.json.JsonPrimitive(90))
                    })
                })
            })
        }

        val spec = ChartSpec.fromJson(params)
        assertNotNull(spec)
        assertEquals(ChartStyle.Bar, spec!!.style)
        assertEquals(listOf("周一", "周二"), spec.xAxis.data)
    }
}
