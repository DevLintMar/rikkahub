package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolOutput
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecuteFlowTest {

    @Test
    fun `default executeFlow emits exactly one Completed with execute result`() = runBlocking {
        val executed = mutableListOf<String>()
        val tool = Tool(
            name = "fake_tool",
            description = "",
            execute = { _ ->
                executed += "called"
                listOf(UIMessagePart.Text("""{"type":"fake_tool","ok":true}"""))
            },
        )
        val outputs = tool.executeFlow(JsonObject(emptyMap())).toList()
        assertEquals(1, outputs.size)
        val completed = outputs.single() as? ToolOutput.Completed
        assertTrue(completed != null)
        assertEquals(listOf("called"), executed)
        assertEquals("""{"type":"fake_tool","ok":true}""", completed!!.parts.single().let { (it as UIMessagePart.Text).text })
    }

    @Test
    fun `executeFlow propagates execute exception`() = runBlocking {
        val tool = Tool(
            name = "boom",
            description = "",
            execute = { _ -> throw IllegalStateException("boom") },
        )
        val error = runCatching { tool.executeFlow(JsonObject(emptyMap())).toList() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}
