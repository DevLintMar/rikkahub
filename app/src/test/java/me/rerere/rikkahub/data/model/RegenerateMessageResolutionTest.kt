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

    @Test
    fun `regenerate 完整流程下用户图片每一步都保留`() {
        val imageUrl = "file:///data/user/0/xyz.lynsei.rikkahub.debug/files/upload/a.png"
        val u0 = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(url = imageUrl)))
        val a1 = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("reply")))
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(u0.toMessageNode(), a1.toMessageNode()),
        )

        // 步骤1：regenerate USER 分支截断（id 匹配定位）
        val node = conversation.getMessageNodeByMessageId(u0.id)
        assertNotNull(node)
        val indexAt = conversation.messageNodes.indexOf(node)
        val truncated = conversation.copy(
            messageNodes = conversation.messageNodes.subList(0, indexAt + 1),
        )

        // 步骤2：截断后用户消息的图片 part 必须保留。
        // （JVM 测试无法调 conversation.files（androidx toUri stub），
        //   但 checkFilesDelete 的 new.files 就来自这些 part——part 在则文件不会被视为删除）
        val imagesAfterTruncate = truncated.currentMessages.first().parts
            .filterIsInstance<UIMessagePart.Image>()
        assertEquals(listOf(imageUrl), imagesAfterTruncate.map { it.url })

        // 步骤3：模拟 handleMessageComplete 生成后 updateCurrentMessages
        // （第一轮 chunk.messages = [用户消息(图), assistant流式回复]）
        val a2 = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("new reply")))
        val chunkMessages = listOf(truncated.currentMessages.first(), a2)
        val updated = truncated.updateCurrentMessages(chunkMessages)

        // 用户消息仍保留图片 URL
        val imagesAfter = updated.currentMessages[0].parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(listOf(imageUrl), imagesAfter.map { it.url })
        assertEquals(2, updated.messageNodes.size)
    }

    @Test
    fun `多轮工具循环 updateCurrentMessages 不丢用户图片`() {
        val imageUrl = "file:///data/user/0/xyz.lynsei.rikkahub.debug/files/upload/a.png"
        val u0 = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(url = imageUrl)))
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(u0.toMessageNode()),
        )

        // 模拟生成中多轮 emit：每轮 chunk.messages 的第一个元素始终是用户消息
        var current = conversation
        repeat(3) { round ->
            val a = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("round $round")),
            )
            val chunk = listOf(current.currentMessages.first(), a)
            current = current.updateCurrentMessages(chunk)
            val images = current.currentMessages.first().parts.filterIsInstance<UIMessagePart.Image>()
            assertEquals("第 ${round + 1} 轮后用户图片应保留", listOf(imageUrl), images.map { it.url })
        }
    }
}
