package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 复现并回归"重新生成含图消息 → 图片变占位符"的根因：
 * ChatService.regenerateAtMessage 用 equals（node.messages.contains）定位消息节点，
 * 而 UI 传入的 message 实例可能与 session 最新实例 equals 不匹配
 * （usage/annotations 等字段更新过），导致定位失败 → indexOf(null) = -1 →
 * subList(0, 0) 清空整个会话，checkFilesDelete 连带删除全部图片文件。
 *
 * 修复：改用 id 匹配（getMessageNodeByMessageId，id 稳定），并加 node==null 防御。
 */
class RegenerateMessageResolutionTest {

    private fun conversation(): Pair<Conversation, UIMessage> {
        val imageUrl = "file:///data/user/0/xyz.lynsei.rikkahub.debug/files/upload/a.png"
        val userMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image(url = imageUrl)),
        )
        val assistantMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("ok")),
        )
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(userMsg.toMessageNode(), assistantMsg.toMessageNode()),
        )
        return conversation to userMsg
    }

    @Test
    fun `UI 传入的旧实例与 session 最新实例 equals 不匹配`() {
        val (conversation, userMsg) = conversation()

        // UI 渲染时拿到的 message 实例，与 session 里最新实例仅在 usage 上不同 → equals 失败
        val staleUser = userMsg.copy(
            usage = TokenUsage(promptTokens = 10, completionTokens = 5),
        )

        assertFalse(conversation.messageNodes[0].messages.contains(staleUser))
        assertNull(conversation.getMessageNodeByMessage(staleUser))
    }

    @Test
    fun `id 匹配能正确定位节点，截断后用户图片保留`() {
        val (conversation, userMsg) = conversation()

        val staleUser = userMsg.copy(
            usage = TokenUsage(promptTokens = 10, completionTokens = 5),
        )

        // 修复后：用 id 匹配（id 稳定），而非 equals
        val node = conversation.getMessageNodeByMessageId(staleUser.id)
        assertNotNull(node)

        val indexAt = conversation.messageNodes.indexOf(node)
        val newConversation = conversation.copy(
            messageNodes = conversation.messageNodes.subList(0, indexAt + 1),
        )

        // 截断后用户消息保留，图片 URL 原样保留（checkFilesDelete 不会误删）
        assertEquals(1, newConversation.messageNodes.size)
        val images = newConversation.currentMessages.first().parts
            .filterIsInstance<UIMessagePart.Image>()
        assertEquals(listOf("file:///data/user/0/xyz.lynsei.rikkahub.debug/files/upload/a.png"), images.map { it.url })
    }

    @Test
    fun `equals 匹配失败若不加防御会导致会话清空与文件全删`() {
        val (conversation, userMsg) = conversation()
        val staleUser = userMsg.copy(
            usage = TokenUsage(promptTokens = 10, completionTokens = 5),
        )

        // 旧逻辑：equals 匹配返回 null → indexOf(null) = -1 → subList(0, 0) 空列表
        val node = conversation.getMessageNodeByMessage(staleUser)
        val indexAt = conversation.messageNodes.indexOf(node)
        val emptied = conversation.copy(
            messageNodes = conversation.messageNodes.subList(0, indexAt + 1),
        )

        // 会话被清空 → files 为空 → checkFilesDelete 会把旧会话全部文件（含用户图片）判为删除
        assertEquals(0, emptied.messageNodes.size)
    }
}
