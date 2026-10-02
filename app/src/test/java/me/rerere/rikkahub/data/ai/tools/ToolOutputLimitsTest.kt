package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputLimitsTest {

    @Test
    fun `output under limit is not clipped`() {
        val original = listOf(UIMessagePart.Text("hello world"))
        val result = clipToolOutput(original)
        assertEquals(original, result)
    }

    @Test
    fun `large output exceeding 160k chars is clipped with truncated indicator`() {
        val largeText = "a".repeat(170_000)
        val parts = listOf(UIMessagePart.Text(largeText))
        val clipped = clipToolOutput(parts)

        val textPart = clipped.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals(MAX_TOOL_RESULT_LENGTH + "…[truncated]".length, textPart.text.length)
        assertTrue(textPart.text.endsWith("…[truncated]"))
    }
}
