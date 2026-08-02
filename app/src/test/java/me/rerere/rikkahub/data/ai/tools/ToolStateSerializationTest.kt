package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolStateSerializationTest {

    private fun toolJson(toolState: String = "", liveOutput: String = ""): String {
        val stateField = if (toolState.isNotEmpty()) """, "toolState":"$toolState"""" else ""
        val liveField = if (liveOutput.isNotEmpty()) """, "liveOutput":"$liveOutput"""" else ""
        return """{"type":"tool","toolCallId":"t1","toolName":"x","input":"{}","output":[]$stateField$liveField}"""
    }

    @Test
    fun `old node json without new fields decodes with defaults`() {
        val tool = JsonInstant.decodeFromString<UIMessagePart>(toolJson()) as UIMessagePart.Tool
        assertEquals(ToolState.CALLING, tool.toolState)
        assertNull(tool.liveOutput)
    }

    @Test
    fun `toolState persists through round trip`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "t1", toolName = "x", input = "{}",
            toolState = ToolState.FAILED,
        )
        val encoded = JsonInstant.encodeToString<UIMessagePart>(tool)
        val decoded = JsonInstant.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Tool
        assertEquals(ToolState.FAILED, decoded.toolState)
    }

    @Test
    fun `liveOutput is transient and not persisted`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "t1", toolName = "x", input = "{}",
            liveOutput = "some live text",
        )
        val encoded = JsonInstant.encodeToString<UIMessagePart>(tool)
        val decoded = JsonInstant.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Tool
        assertNull(decoded.liveOutput)
    }
}
