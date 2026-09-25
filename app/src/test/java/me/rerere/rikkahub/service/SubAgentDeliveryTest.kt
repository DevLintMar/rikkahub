package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        // 必须是 USER：SYSTEM 会被 ClaudeProvider / Responses API 整类丢弃，那样通知就送不到模型
        assertEquals(MessageRole.USER, injected.last().role)
        assertTrue(injected.last().isSynthetic)
    }

    @Test
    fun `注入的通知被识别为待剔除`() {
        val injected = injectTaskNotifications(conversationOf(userText("你好"), marker()).currentMessages, 1).last()

        assertTrue(injected.isInjectedTaskNotification())
    }

    @Test
    fun `真实消息里出现标签不算注入通知（否则会被误剔除）`() {
        // 用户或模型复述了 <task-notification> 字面量：必须**不**被剔除，否则最后一条 assistant
        // 会被静默丢弃、标记永远 pending（见该谓词的 KDoc）
        assertTrue(!UIMessage.user("<task-notification> 这串是什么？</task-notification>").isInjectedTaskNotification())
        assertTrue(!UIMessage.assistant("<task-notification>x</task-notification>").isInjectedTaskNotification())
        // 合成的 USER 消息若不含标签，也不该被剔除
        assertTrue(!UIMessage.user("普通合成消息").copy(isSynthetic = true).isInjectedTaskNotification())
    }

    @Test
    fun `无需汇报时注入是恒等变换`() {
        val messages = listOf(userText("你好"), assistantText("在的"))

        assertEquals(messages, injectTaskNotifications(messages, pendingTaskCount = 0))
    }

    // ---- Task 3 ----

    private fun subAgentToolPart(taskId: String, status: String = "started", description: String = "搜索 AI 新闻") =
        UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "sub_agent",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("type", "sub_agent")
                        put("status", status)
                        put("task_id", taskId)
                        put("description", description)
                        put("mode", "background")
                    }.toString(),
                ),
            ),
        )

    private fun toolCallMessage(part: UIMessagePart.Tool) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("已启动后台任务"), part))

    private fun conversationOf(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        title = "t",
        messageNodes = messages.map { it.toMessageNode() },
    )

    @Test
    fun `投递改写工具结果为终态且不含结果正文`() {
        val conversation = conversationOf(userText("起个子代理"), toolCallMessage(subAgentToolPart("sub_1")))

        val updated = conversation.applyTaskDelivery(
            delivery = TaskDelivery(
                taskId = "sub_1",
                status = "completed",
                reason = null,
                description = null,
                result = "三条新闻……",
                error = null,
            ),
            markerText = { _ -> "Agent \"搜索 AI 新闻\" finished" },
        )!!

        // 只追加一条标记节点，历史节点数 +1
        assertEquals(3, updated.messageNodes.size)
        assertEquals(conversation.messageNodes[0], updated.messageNodes[0])
        // 未变更的节点要原样返回（写时复制短路），不是照抄一份等值副本
        assertSame(conversation.messageNodes[0], updated.messageNodes[0])
        assertEquals(conversation.messageNodes[1].id, updated.messageNodes[1].id)
        assertEquals(conversation.messageNodes[1].messages[0].parts[0], updated.messageNodes[1].messages[0].parts[0])

        val rewritten = updated.messageNodes[1].messages[0].parts.filterIsInstance<UIMessagePart.Tool>().single()
        val json = JsonInstant.parseToJsonElement(
            rewritten.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
        ).jsonObject

        assertEquals("completed", json["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("sub_1", json["task_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("background", json["mode"]?.jsonPrimitive?.contentOrNull)   // 原字段要保留
        assertNull(json["result"])                                              // 决策 5：结果不进工具结果
        assertNull(json["error"])
    }

    @Test
    fun `投递结果正文只出现在标记 metadata 里`() {
        val conversation = conversationOf(userText("起个子代理"), toolCallMessage(subAgentToolPart("sub_1")))

        val updated = conversation.applyTaskDelivery(
            TaskDelivery("sub_1", "completed", null, null, "三条新闻……", null),
            // 用 lambda 拼文案顺带钉住「description 从工具结果里解析出来」这条路径
            markerText = { desc -> "Agent \"$desc\" finished" },
        )!!

        val markerPart = updated.messageNodes.last().messages.single().parts.single() as UIMessagePart.Text
        assertEquals("Agent \"搜索 AI 新闻\" finished", markerPart.text)
        assertTrue(markerPart.text.contains("三条新闻").not())
        assertEquals("三条新闻……", updated.messageNodes.last().messages.single().subAgentTaskMarkerOrNull()?.result)
    }

    @Test
    fun `找不到对应工具结果时仍然追加标记（后台工作流步骤等无锚点路径）`() {
        val conversation = conversationOf(userText("起个子代理"), toolCallMessage(subAgentToolPart("sub_1")))

        val updated = conversation.applyTaskDelivery(
            TaskDelivery("sub_other", "completed", null, "web 搜索", "三条新闻", null),
            markerText = { description -> "Agent \"$description\" finished" },
        )

        // 不再返回 null：无锚点时也要留住回执，否则整条投递（结果正文 + 触发）被丢弃
        val marker = requireNotNull(updated).messageNodes.last().messages.single().subAgentTaskMarkerOrNull()
        assertEquals("sub_other", marker?.taskId)
        assertEquals("三条新闻", marker?.result)
        assertEquals(3, updated.messageNodes.size)     // 用户 + 工具调用 + 标记
    }

    @Test
    fun `重复投递同一条是幂等的`() {
        val conversation = conversationOf(userText("起个子代理"), toolCallMessage(subAgentToolPart("sub_1")))
        val delivery = TaskDelivery("sub_1", "failed", "app_exit", null, null, "进程被回收")

        val interruptedText = { _: String -> "Agent \"搜索 AI 新闻\" 已中断（应用退出）" }

        val once = conversation.applyTaskDelivery(delivery, markerText = interruptedText)!!
        val twice = once.applyTaskDelivery(delivery, markerText = interruptedText)

        // 幂等的判据是「返回 null」：标记已在会话里，第二次投递不做任何变更
        assertNull("重复投递必须返回 null 表示无需变更", twice)
        assertEquals(3, once.messageNodes.size)
        assertEquals(1, once.messageNodes.count { it.messages.single().subAgentTaskMarkerOrNull() != null })

        // 终态与 reason 都必须落进被改写的工具结果里
        val rewritten = once.messageNodes[1].messages[0].parts
            .filterIsInstance<UIMessagePart.Tool>().single()
        val json = JsonInstant.parseToJsonElement(
            rewritten.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
        ).jsonObject

        assertEquals("failed", json["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("app_exit", json["reason"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `超长结果按 100KB 截断`() {
        val conversation = conversationOf(toolCallMessage(subAgentToolPart("sub_1")))
        val huge = "x".repeat(SUB_AGENT_RESULT_MAX_CHARS + 1_000)

        val updated = conversation.applyTaskDelivery(
            TaskDelivery("sub_1", "completed", null, null, huge, null),
            markerText = { _ -> "marker" },
        )!!

        val stored = updated.messageNodes.last().messages.single().subAgentTaskMarkerOrNull()?.result.orEmpty()
        // clipTaskResult = take(MAX) + "[truncated]"，所以长度是 MAX + 后缀长度
        assertEquals(SUB_AGENT_RESULT_MAX_CHARS + "[truncated]".length, stored.length)
        assertTrue(stored.endsWith("[truncated]"))
    }

    @Test
    fun `只有空白的结果正文不产 result 行`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker("sub_4", "completed", null, "任务", "   ", null),
            pendingTaskCount = 0,
        )

        assertFalse(xml.contains("<result>"))
    }

    @Test
    fun `标记与 assistant 文本之间的用户消息对扫描透明`() {
        val messages = listOf(marker(), userText("顺便再起一个"), assistantText("好"))

        assertTrue(messages.pendingTaskMarkers().isEmpty())
    }

    @Test
    fun `标记 metadata 缺 description 时回退成 taskId`() {
        val message = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(
                UIMessagePart.Text(
                    text = "Agent finished",
                    metadata = buildJsonObject {
                        put(SUB_AGENT_TASK_METADATA_KEY, buildJsonObject {
                            put("taskId", "sub_9")
                            put("status", "completed")
                        })
                    },
                ),
            ),
        )

        assertEquals("sub_9", message.subAgentTaskMarkerOrNull()?.description)
    }

    @Test
    fun `中断判据只认 registry 里不存在的 started 任务`() {
        val messages = listOf(
            toolCallMessage(subAgentToolPart("sub_live", status = "started")),
            toolCallMessage(subAgentToolPart("sub_dead", status = "started")),
            toolCallMessage(subAgentToolPart("sub_done", status = "completed")),
        )

        val interrupted = messages.interruptedSubAgentTaskIds { it == "sub_live" }

        assertEquals(listOf("sub_dead"), interrupted)
    }

    @Test
    fun `终态的旧任务不再被判定为中断`() {
        val messages = listOf(
            toolCallMessage(subAgentToolPart("sub_dead", status = "failed")),
        )

        assertTrue(messages.interruptedSubAgentTaskIds { false }.isEmpty())
    }

    @Test
    fun `对账判据在投递后不再命中同一条`() {
        val conversation = conversationOf(toolCallMessage(subAgentToolPart("sub_dead", status = "started")))

        assertEquals(listOf("sub_dead"), conversation.currentMessages.interruptedSubAgentTaskIds { false })

        val updated = conversation.applyTaskDelivery(
            TaskDelivery("sub_dead", "failed", "app_exit", null, null, "进程被回收"),
            markerText = { _ -> "已中断" },
        )!!

        assertTrue(updated.currentMessages.interruptedSubAgentTaskIds { false }.isEmpty())
    }

    @Test
    fun `中断候选带回卡片自身的进程与时间证据`() {
        val cardTime = LocalDateTime(2026, 9, 25, 16, 26, 30)
        val card = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_p",
                    toolName = "sub_agent",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("type", "sub_agent")
                                put("status", "started")
                                put("task_id", "sub_self")
                                put("description", "自证")
                                put("mode", "background")
                                put("launched_at", 1_700_000_000_000L)
                                put("process_started_at", 1_699_999_000_000L)
                            }.toString(),
                        ),
                    ),
                ),
            ),
            createdAt = cardTime,
        )

        val candidates = listOf(card).interruptedSubAgentTaskCandidates { false }

        assertEquals(1, candidates.size)
        assertEquals("sub_self", candidates.single().taskId)
        assertEquals(cardTime, candidates.single().cardCreatedAt)
        assertEquals(1_699_999_000_000L, candidates.single().cardProcessStartedAt ?: -1L)
    }

    @Test
    fun `没有进程字段的老卡片仍是候选，只是证据为 null`() {
        val messages = listOf(toolCallMessage(subAgentToolPart("sub_old")))

        val candidates = messages.interruptedSubAgentTaskCandidates { false }

        assertEquals(listOf("sub_old"), candidates.map { it.taskId })
        assertNull(candidates.single().cardProcessStartedAt)
    }

    @Test
    fun `本进程认识的任务不出现在候选里`() {
        val messages = listOf(toolCallMessage(subAgentToolPart("sub_live")))

        assertTrue(messages.interruptedSubAgentTaskCandidates { it == "sub_live" }.isEmpty())
    }
}
