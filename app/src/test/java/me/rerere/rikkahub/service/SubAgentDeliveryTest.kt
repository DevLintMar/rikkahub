package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentDeliveryTest {

    private fun marker(
        taskId: String = "sub_1",
        status: String = "completed",
        reason: String? = null,
        description: String = "搜索 AI 新闻",
        result: String? = "三条新闻……",
        error: String? = null,
    ) = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(
            UIMessagePart.Text(
                text = "Agent \"$description\" finished",
                metadata = buildJsonObject {
                    put("subAgentTask", buildJsonObject {
                        put("taskId", taskId)
                        put("status", status)
                        if (reason != null) put("reason", reason) else put("reason", JsonNull)
                        put("description", description)
                        if (result != null) put("result", result) else put("result", JsonNull)
                        if (error != null) put("error", error) else put("error", JsonNull)
                    })
                },
            ),
        ),
    )

    private fun assistantText(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun userText(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `标记的 metadata 往返后可读`() {
        val parsed = marker(status = "failed", reason = "app_exit", result = null, error = "boom").subAgentTaskMarkerOrNull()

        assertEquals("sub_1", parsed?.taskId)
        assertEquals("failed", parsed?.status)
        assertEquals("app_exit", parsed?.reason)
        assertEquals("搜索 AI 新闻", parsed?.description)
        assertNull(parsed?.result)
        assertEquals("boom", parsed?.error)
    }

    @Test
    fun `没有 metadata 的普通消息不是标记`() {
        assertNull(userText("你好").subAgentTaskMarkerOrNull())
        assertNull(assistantText("好的").subAgentTaskMarkerOrNull())
        assertNull(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("普通系统消息"))).subAgentTaskMarkerOrNull())
    }

    @Test
    fun `标记之后已有 assistant 文本则不再派生通知`() {
        val messages = listOf(userText("起个子代理"), marker(), assistantText("我看了报告"))

        assertTrue(messages.pendingTaskMarkers().isEmpty())
    }

    @Test
    fun `末尾的标记一定派生通知`() {
        val messages = listOf(userText("起个子代理"), assistantText("已启动后台任务"), marker())

        assertEquals(listOf("sub_1"), messages.pendingTaskMarkers().map { it.taskId })
    }

    @Test
    fun `中间的标记按其后的 assistant 文本逐个判定`() {
        val messages = listOf(
            marker(taskId = "sub_a"),
            assistantText("A 的结果我看了"),
            marker(taskId = "sub_b"),
        )

        assertEquals(listOf("sub_b"), messages.pendingTaskMarkers().map { it.taskId })
    }

    @Test
    fun `只有空白的 assistant 文本不算已汇报`() {
        val messages = listOf(marker(), assistantText("   "))

        assertEquals(listOf("sub_1"), messages.pendingTaskMarkers().map { it.taskId })
    }

    @Test
    fun `多个未汇报的标记按出现顺序全部返回`() {
        val messages = listOf(marker(taskId = "sub_a"), marker(taskId = "sub_b"))

        assertEquals(listOf("sub_a", "sub_b"), messages.pendingTaskMarkers().map { it.taskId })
    }

    @Test
    fun `通知正文取自标记 metadata 且带待完成任务数`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker(
                taskId = "sub_1",
                status = "completed",
                reason = null,
                description = "搜索 AI 新闻",
                result = "三条新闻",
                error = null,
            ),
            pendingTaskCount = 2,
        )

        assertTrue(xml.contains("<task-id>sub_1</task-id>"))
        assertTrue(xml.contains("<status>completed</status>"))
        assertTrue(xml.contains("<pending-tasks>2</pending-tasks>"))
        assertTrue(xml.contains("<result>三条新闻</result>"))
        assertTrue(xml.startsWith("<task-notification>"))
        assertTrue(xml.endsWith("</task-notification>"))
    }

    @Test
    fun `失败的通知带 reason 与 error 正文而不是 result`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker(
                taskId = "sub_2",
                status = "failed",
                reason = "app_exit",
                description = "搜索 AI 新闻",
                result = null,
                error = "进程被回收",
            ),
            pendingTaskCount = 0,
        )

        assertTrue(xml.contains("<reason>app_exit</reason>"))
        assertTrue(xml.contains("<result>进程被回收</result>"))
        assertTrue(xml.contains("<summary>Agent \"搜索 AI 新闻\" failed</summary>"))
    }

    @Test
    fun `没有 reason 与正文时不出这两行`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker("sub_3", "completed", null, "任务", null, null),
            pendingTaskCount = 0,
        )

        assertFalse(xml.contains("<reason>"))
        assertFalse(xml.contains("<result>"))
    }

    @Test
    fun `结果里的尖括号被转义，不会破坏通知结构`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker(
                taskId = "sub_1",
                status = "completed",
                reason = null,
                description = "a<b>c",
                result = "</result><injected>",
                error = null,
            ),
            pendingTaskCount = 0,
        )

        assertTrue(xml.contains("&lt;injected&gt;"))
        // 结果里的 `</result>` 已被转义，所以整段 XML 里只有闭合标签那一处字面量
        assertEquals(1, Regex("</result>").findAll(xml).count())
        assertTrue(xml.contains("<summary>Agent \"a&lt;b&gt;c\" completed</summary>"))
    }

    @Test
    fun `xmlEscape 处理与号与尖括号`() {
        assertEquals("a&amp;b&lt;c&gt;d", xmlEscape("a&b<c>d"))
    }

    @Test
    fun `注入只追加待汇报的通知，不动原消息`() {
        val messages = listOf(userText("起个子代理"), marker())

        val injected = injectTaskNotifications(messages, pendingTaskCount = 1)

        assertEquals(messages.size + 1, injected.size)
        assertEquals(messages, injected.dropLast(1))
        assertTrue(injected.last().parts.first().let { (it as UIMessagePart.Text).text }.contains(TASK_NOTIFICATION_TAG))
    }

    @Test
    fun `无需汇报时注入是恒等变换`() {
        val messages = listOf(userText("你好"), assistantText("在的"))

        assertEquals(messages, injectTaskNotifications(messages, pendingTaskCount = 0))
    }
}
