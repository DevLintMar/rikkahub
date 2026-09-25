package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRuntimeTest {

    private fun toolPart(toolCallId: String = "call_1", toolName: String = "search_web") =
        UIMessagePart.Tool(
            toolCallId = toolCallId,
            toolName = toolName,
            input = "{}",
            output = emptyList(),
        )

    private fun assistantWithTool(part: UIMessagePart.Tool) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("让我去看看最近的消息"), part),
    )

    private fun outputTextOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Tool>()
            .single()
            .output
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }

    @Test
    fun `工具结果写回 Tool part，不追加 user 消息`() {
        val assistant = assistantWithTool(toolPart())
        val messages = listOf(UIMessage.user(prompt = "查一下原神最新情报"), assistant)

        val updated = messages.withToolOutputs(
            toolCallMessageId = assistant.id,
            outputs = mapOf("call_1" to listOf(UIMessagePart.Text("搜索结果：原神 6.1 前瞻 ……"))),
        )

        // 条数不变：工具结果**不是**一条新消息。（原来的实现会在这里追加一条 `UIMessage.user`
        // ——那正是模型看不到工具返回值的根因，所以这条断言是这次修复的承重断言。）
        assertEquals(messages.size, updated.size)
        assertEquals(1, updated.count { it.role == MessageRole.USER })
        assertEquals("搜索结果：原神 6.1 前瞻 ……", outputTextOf(updated.last()))
    }

    @Test
    fun `没有对应输出时写回空输出而不是抛异常`() {
        val assistant = assistantWithTool(toolPart())
        val messages = listOf(assistant)

        val updated = messages.withToolOutputs(toolCallMessageId = assistant.id, outputs = emptyMap())

        assertEquals(1, updated.size)
        assertEquals("", outputTextOf(updated.single()))
    }

    @Test
    fun `只改承载调用的那条消息，别的消息原样保留`() {
        val assistant = assistantWithTool(toolPart())
        val older = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("上一轮")))
        val messages = listOf(older, assistant)

        val updated = messages.withToolOutputs(
            toolCallMessageId = assistant.id,
            outputs = mapOf("call_1" to listOf(UIMessagePart.Text("结果"))),
        )

        assertEquals(older, updated.first())
        assertEquals("结果", outputTextOf(updated.last()))
    }

    @Test
    fun `空回复判失败而不是成功`() {
        val blank = subAgentCompletion("   \n")
        assertFalse(blank.success)
        assertTrue((blank.error ?: "").isNotBlank())

        val done = subAgentCompletion("《原神》速报（截至 9/25）：……")
        assertTrue(done.success)
        assertEquals("《原神》速报（截至 9/25）：……", done.text)
    }
}
