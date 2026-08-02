package me.rerere.rikkahub.data.embedding

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTextExtractorTest {
    @Test
    fun `joins text parts and skips non-text`() {
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("hello "),
                UIMessagePart.Image("http://example.com/a.png"),
                UIMessagePart.Text("world"),
            ),
        )
        assertEquals("hello \nworld", MessageTextExtractor.messageToSearchText(msg))
    }
}
