package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.subAgentTaskMarkerOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 触发轮的写回必须把**新消息**放成末尾的新节点。
 *
 * 现场症状：一个子代理的完成回执与主代理那条回复被塞进同一个节点，UI 上变成一条消息加
 * 一个 1/2 分支切换器。根因是 `updateCurrentMessages` 按**快照下标**安置新消息，而快照之后
 * 会话又追加了别的子代理的回执节点——下标就错位了。
 */
class ConversationAppendTest {

    private fun textMessage(role: MessageRole, text: String) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    private fun marker(taskId: String) = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(
            UIMessagePart.Text(
                text = "Agent \"$taskId\" 已完成",
                metadata = buildJsonObject {
                    put("subAgentTask", buildJsonObject {
                        put("taskId", taskId)
                        put("status", "completed")
                    })
                },
            ),
        ),
    )

    private fun conversationOf(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    @Test
    fun `新回复不会被塞进快照之后才出现的那个节点`() {
        val user = textMessage(MessageRole.USER, "同时开三个子代理")
        val launch = textMessage(MessageRole.ASSISTANT, "三个都放出去啦")
        val reply = textMessage(MessageRole.ASSISTANT, "3+3 → 6")
        val snapshot = listOf(user, launch, reply)

        // 会话在本轮期间多了一个节点：另一个子代理的完成回执
        val conversation = conversationOf(user, launch, marker("计算 2+2"))

        val updated = conversation.updateCurrentMessagesAppendingNew(snapshot)

        // 承重断言：回执节点里仍然只有它自己那一条（旧实现会把回复塞进去 ⇒ 1/2 分支）
        assertEquals(1, updated.messageNodes[2].messages.size)
        assertEquals("计算 2+2", updated.messageNodes[2].messages.single().subAgentTaskMarkerOrNull()?.taskId)
        // 回复成为末尾的新节点
        assertEquals(4, updated.messageNodes.size)
        assertEquals("3+3 → 6", updated.messageNodes.last().messages.single().toText())
    }

    @Test
    fun `已存在的消息按 id 就地更新，不产生新节点`() {
        val user = textMessage(MessageRole.USER, "问一句")
        val streaming = textMessage(MessageRole.ASSISTANT, "先说一半")
        val conversation = conversationOf(user, streaming)

        val updated = conversation.updateCurrentMessagesAppendingNew(
            listOf(user, streaming.copy(parts = listOf(UIMessagePart.Text("先说一半，然后说完了")))),
        )

        assertEquals(2, updated.messageNodes.size)
        assertEquals("先说一半，然后说完了", updated.messageNodes.last().messages.single().toText())
    }
}
