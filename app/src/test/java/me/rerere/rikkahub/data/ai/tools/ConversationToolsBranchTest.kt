package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.embedding.MessageTextExtractor
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationToolsBranchTest {

    private fun msg(role: MessageRole, text: String) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `selected branch follows selectIndex and filters tool messages`() {
        val conv = Conversation(
            id = Uuid.random(), assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(messages = listOf(msg(MessageRole.USER, "hi")), selectIndex = 0),
                MessageNode(
                    messages = listOf(
                        msg(MessageRole.ASSISTANT, "answer A"),
                        msg(MessageRole.ASSISTANT, "answer B"),
                    ),
                    selectIndex = 1, // 用户选的是 B
                ),
                MessageNode(messages = listOf(msg(MessageRole.TOOL, "tool")), selectIndex = 0),
            ),
        )
        val visible = conv.currentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .map { MessageTextExtractor.messageToSearchText(it) }
        assertEquals(listOf("hi", "answer B"), visible)
    }

    @Test
    fun `pagination slices the branch`() {
        val nodes = (0 until 5).map { i ->
            MessageNode(messages = listOf(msg(MessageRole.USER, "m$i")), selectIndex = 0)
        }
        val conv = Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = nodes)
        val messages = conv.currentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        val page = messages.drop(1).take(2)
        assertEquals(listOf("m1", "m2"), page.map { MessageTextExtractor.messageToSearchText(it) })
    }
}
