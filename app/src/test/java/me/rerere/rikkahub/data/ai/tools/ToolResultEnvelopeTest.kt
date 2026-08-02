package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolResultEnvelopeTest {

    private fun text(s: String) = UIMessagePart.Text(s)

    @Test
    fun `parseEnvelope returns null for non json`() {
        assertNull(parseEnvelope(listOf(text("plain markdown"))))
        assertNull(parseEnvelope(emptyList()))
    }

    @Test
    fun `parseEnvelope returns object for envelope json`() {
        val env = parseEnvelope(listOf(text("""{"type":"workspace_shell","exitCode":0}""")))
        assertEquals("workspace_shell", env?.get("type")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `inferToolState maps error and non-zero exitCode to FAILED`() {
        assertEquals(ToolState.FAILED, inferToolState(listOf(text("""{"type":"x","error":"no_results"}"""))))
        assertEquals(ToolState.FAILED, inferToolState(listOf(text("""{"type":"x","exitCode":1}"""))))
    }

    @Test
    fun `inferToolState maps success and empty to SUCCEEDED / EMPTY`() {
        assertEquals(ToolState.SUCCEEDED, inferToolState(listOf(text("""{"type":"x","exitCode":0}"""))))
        assertEquals(ToolState.SUCCEEDED, inferToolState(listOf(text("plain text"))))
        assertEquals(ToolState.EMPTY, inferToolState(emptyList()))
    }
}
