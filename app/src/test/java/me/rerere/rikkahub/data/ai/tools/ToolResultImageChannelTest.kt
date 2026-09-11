package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.providers.TOOL_RESULT_IMAGE_ATTACHED_NOTE
import me.rerere.ai.provider.providers.TOOL_RESULT_IMAGE_OMITTED_NOTE
import me.rerere.ai.provider.providers.toolResultImagesForUserMessage
import me.rerere.ai.provider.providers.toolResultText
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat Completions 的 tool 消息 content 只能是文本（OpenAI 规范），图片必须改走 user 消息。
 * 这里锁定"哪些图片要搬出去 / tool 消息里留什么文本"的决策，防止回退成把 image_url
 * 塞进 tool 消息 → 服务端 400 "Invalid input"（param=messages.N.content）。
 */
class ToolResultImageChannelTest {

    private val vision = listOf(Modality.TEXT, Modality.IMAGE)
    private val textOnly = listOf(Modality.TEXT)

    private fun toolWithImage() = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "read_image",
        input = """{"urls":["file:///upload/a.png"]}""",
        output = listOf(
            UIMessagePart.Text("""{"type":"read_image","results":[]}"""),
            UIMessagePart.Image("file:///data/user/0/x/files/upload/a.png"),
        ),
    )

    @Test
    fun `视觉模型 工具结果图片搬出 tool 消息`() {
        val tool = toolWithImage()
        val images = tool.toolResultImagesForUserMessage(vision)
        assertEquals(1, images.size)
        assertEquals("file:///data/user/0/x/files/upload/a.png", images.first().url)
    }

    @Test
    fun `视觉模型 tool 消息文本保留信封并标注图片在后续消息`() {
        val text = toolWithImage().toolResultText(vision)
        assertTrue(text.contains("""{"type":"read_image","results":[]}"""))
        assertTrue(text.contains(TOOL_RESULT_IMAGE_ATTACHED_NOTE))
    }

    @Test
    fun `非视觉模型不搬图片且给出省略说明`() {
        val tool = toolWithImage()
        assertTrue(tool.toolResultImagesForUserMessage(textOnly).isEmpty())
        val text = tool.toolResultText(textOnly)
        assertTrue(text.contains(TOOL_RESULT_IMAGE_OMITTED_NOTE))
    }

    @Test
    fun `只有图片没有文本时 tool 消息内容非空`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-2",
            toolName = "read_image",
            input = "{}",
            output = listOf(UIMessagePart.Image("file:///data/user/0/x/files/upload/a.png")),
        )
        assertEquals(TOOL_RESULT_IMAGE_ATTACHED_NOTE, tool.toolResultText(vision))
        assertEquals(TOOL_RESULT_IMAGE_OMITTED_NOTE, tool.toolResultText(textOnly))
    }

    @Test
    fun `纯文本工具结果不受影响`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "search_web",
            input = "{}",
            output = listOf(UIMessagePart.Text("hello"), UIMessagePart.Text(" ")),
        )
        // 空白的文本块被丢掉（空 text 块会被部分服务端拒收）
        assertEquals("hello", tool.toolResultText(vision))
        assertTrue(tool.toolResultImagesForUserMessage(vision).isEmpty())
    }
}
