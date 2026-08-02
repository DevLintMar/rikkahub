package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.tools.ToolKind
import me.rerere.rikkahub.ui.components.message.tools.ToolPresentationResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPresentationTest {

    private fun tool(name: String, output: String = """{"type":"$name"}"""): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = name,
            input = "{}",
            toolState = ToolState.SUCCEEDED,  // 已完成工具（真实中由 GenerationHandler 写入）
            output = if (output.isBlank()) emptyList() else listOf(UIMessagePart.Text(output)),
        )

    @Test
    fun `kindFor maps literal tool names`() {
        assertEquals(ToolKind.WEB_SEARCH, ToolPresentationResolver.kindFor("search_web"))
        assertEquals(ToolKind.WEB_FETCH, ToolPresentationResolver.kindFor("scrape_web"))
        assertEquals(ToolKind.CONVERSATION_SEARCH, ToolPresentationResolver.kindFor("conversation_search"))
        assertEquals(ToolKind.SHELL_EXECUTE, ToolPresentationResolver.kindFor("workspace_shell"))
        assertEquals(ToolKind.UNKNOWN, ToolPresentationResolver.kindFor("mcp_tool"))
    }

    @Test
    fun `resolve reads subject and count from envelope`() {
        val t = tool(
            "conversation_search",
            """{"type":"conversation_search","query":"番茄","results":[{"title":"a"},{"title":"b"}]}""",
        )
        val p = ToolPresentationResolver.resolve(t)
        assertEquals(ToolKind.CONVERSATION_SEARCH, p.kind)
        assertEquals("番茄", p.subject)
        assertEquals(2, p.count)
    }

    @Test
    fun `resolve state comes from toolState with empty refinement`() {
        val empty = tool("recent_chats", """{"type":"recent_chats","conversations":[]}""")
        assertEquals(ToolState.EMPTY, ToolPresentationResolver.resolve(empty).state)
        val failed = tool("workspace_shell", """{"type":"workspace_shell","exitCode":1}""")
        assertEquals(ToolState.FAILED, ToolPresentationResolver.resolve(failed).state)
    }
}
