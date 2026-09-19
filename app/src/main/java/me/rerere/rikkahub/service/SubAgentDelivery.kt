package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant

internal const val SUB_AGENT_TOOL_NAME = "sub_agent"
internal const val SUB_AGENT_TASK_METADATA_KEY = "subAgentTask"

/** 子代理任务通知的辨识标签。**只能按内容判定**：`UIMessage.isSynthetic` 是 @Transient，不持久化。 */
internal const val TASK_NOTIFICATION_TAG = "<task-notification>"

/**
 * 投递期写在**可见标记**上的机器可读部分。
 *
 * 载体选 `UIMessagePart.Text.metadata` 而不是「隐藏节点」：metadata 随 `nodes` blob 一起持久化、
 * 从不发给 provider、也不被任何渲染器读取（渲染器只读 `text`）。于是结果正文既能持久化、
 * 又只给 AI 看，且不需要任何展示层过滤。
 */
internal data class SubAgentTaskMarker(
    val taskId: String,
    val status: String,        // "completed" | "failed"
    val reason: String?,       // null | "user_cancelled" | "app_exit"
    val description: String,
    val result: String?,       // 结果正文；失败时为 null
    val error: String?,        // 原始错误正文；成功时为 null
)

internal fun UIMessage.subAgentTaskMarkerOrNull(): SubAgentTaskMarker? {
    if (role != MessageRole.SYSTEM) return null
    val part = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull() ?: return null
    val meta = part.metadata?.get(SUB_AGENT_TASK_METADATA_KEY) as? JsonObject ?: return null
    val taskId = meta["taskId"]?.jsonPrimitive?.contentOrNull ?: return null
    val status = meta["status"]?.jsonPrimitive?.contentOrNull ?: return null
    return SubAgentTaskMarker(
        taskId = taskId,
        status = status,
        reason = meta["reason"]?.jsonPrimitive?.contentOrNull,
        description = meta["description"]?.jsonPrimitive?.contentOrNull ?: taskId,
        result = meta["result"]?.jsonPrimitive?.contentOrNull,
        error = meta["error"]?.jsonPrimitive?.contentOrNull,
    )
}

/**
 * 还没汇报给 AI 的标记，按出现顺序返回。
 *
 * 判据：标记**之后是否已经存在一条带非空文本的 assistant 消息**。标记总在投递时追加到末尾，
 * 所以任何排在它之后的 assistant 回复，其请求必然在标记已存在之后构建——也就必然已经派生过通知。
 * 于是这条规则不会漏发，同时天然做到「只通知一次」（缺陷④的结构性根因消失）。
 */
internal fun List<UIMessage>.pendingTaskMarkers(): List<SubAgentTaskMarker> {
    val found = mutableListOf<SubAgentTaskMarker>()
    var hasAssistantTextAfter = false
    for (index in indices.reversed()) {
        val message = this[index]
        val marker = message.subAgentTaskMarkerOrNull()
        if (marker != null) {
            if (!hasAssistantTextAfter) found += marker
        } else if (message.role == MessageRole.ASSISTANT &&
            message.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
        ) {
            hasAssistantTextAfter = true
        }
    }
    return found.reversed()
}

internal fun xmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

internal fun taskNotificationXml(marker: SubAgentTaskMarker, pendingTaskCount: Int): String = buildString {
    appendLine("<task-notification>")
    appendLine("  <task-id>${xmlEscape(marker.taskId)}</task-id>")
    appendLine("  <status>${xmlEscape(marker.status)}</status>")
    marker.reason?.let { appendLine("  <reason>${xmlEscape(it)}</reason>") }
    appendLine("  <pending-tasks>$pendingTaskCount</pending-tasks>")
    appendLine("  <summary>Agent \"${xmlEscape(marker.description)}\" ${xmlEscape(marker.status)}</summary>")
    val body = marker.result ?: marker.error
    if (!body.isNullOrBlank()) {
        appendLine("  <result>${xmlEscape(body)}</result>")
    }
    append("</task-notification>")
}

/**
 * 派生待汇报的任务通知，追加在请求消息列表末尾。
 *
 * 这些消息**只存在于这一次请求**：`ChatService.handleMessageComplete` 的 `.collect` 会按
 * [TASK_NOTIFICATION_TAG] 把它们剔除，不写回会话状态。
 */
internal fun injectTaskNotifications(
    messages: List<UIMessage>,
    pendingTaskCount: Int,
): List<UIMessage> {
    val markers = messages.pendingTaskMarkers()
    if (markers.isEmpty()) return messages
    val injected = markers.map { marker ->
        UIMessage.system(prompt = taskNotificationXml(marker, pendingTaskCount))
    }
    return messages + injected
}

/** 结果正文的硬上限，与 `clipToolOutput` 的 100KB 惯例一致。 */
internal const val SUB_AGENT_RESULT_MAX_CHARS = 100_000

/** 一次投递的输入。`description` 为 null 时从工具结果里取。 */
internal data class TaskDelivery(
    val taskId: String,
    val status: String,        // "completed" | "failed"
    val reason: String?,       // null | "user_cancelled" | "app_exit"
    val description: String?,
    val result: String?,
    val error: String?,
)

private fun clipTaskResult(text: String?): String? = text?.let {
    if (it.length <= SUB_AGENT_RESULT_MAX_CHARS) it else it.take(SUB_AGENT_RESULT_MAX_CHARS) + "[truncated]"
}

private fun UIMessagePart.Tool.outputText(): String =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

/**
 * 本次投递要改写的工具结果：`sub_agent` 且输出 JSON 里 `task_id` 命中。
 *
 * 用内嵌 `task_id` 作锚点是因为 `Tool.execute` 拿不到自己的 `toolCallId`
 * （签名是 `suspend (JsonElement) -> List<UIMessagePart>`）。
 */
private fun UIMessagePart.Tool.matchesTask(taskId: String): Boolean =
    toolName == SUB_AGENT_TOOL_NAME && outputText().contains("\"task_id\":\"$taskId\"")

/**
 * 把一次终态投递写进会话：改写工具结果（只留状态）+ 追加一条可见标记。
 *
 * 返回 `null` 表示无需变更——找不到对应工具结果，或者该任务的标记已经在会话里（幂等）。
 * 结果正文只进标记 metadata，**绝不进工具结果**（决策 5）。
 *
 * `markerText` 是**函数**而不是成品字符串：可见文案要过 `stringResource`（Android 资源在本文件里用不了），
 * 而 description 可能来自工具结果（`delivery.description` 为 null 时）——所以由调用方拿着解析后的
 * description 去拼文案。中断对账那条路径就是靠它才能显示出子代理的名字而不是 taskId。
 */
internal fun Conversation.applyTaskDelivery(
    delivery: TaskDelivery,
    markerText: (description: String) -> String,
): Conversation? {
    if (messageNodes.any { node -> node.messages.any { it.subAgentTaskMarkerOrNull()?.taskId == delivery.taskId } }) {
        return null
    }

    var matched = false
    val clipped = clipTaskResult(delivery.result)
    val clippedError = clipTaskResult(delivery.error)
    var description = delivery.description

    val newNodes = messageNodes.map { node ->
        val newMessages = node.messages.map { message ->
            val newParts = message.parts.map { part ->
                if (part !is UIMessagePart.Tool || !part.matchesTask(delivery.taskId)) return@map part
                matched = true
                val original = runCatching {
                    JsonInstant.parseToJsonElement(part.outputText()).jsonObject.toMutableMap()
                }.getOrNull()
                if (description == null) {
                    description = original?.get("description")?.jsonPrimitive?.contentOrNull
                }
                val rewritten = buildJsonObject {
                    // status/reason/task_id 由本次投递决定；result/error 一律不留在工具结果里（决策 5）
                    original?.forEach { (key, value) ->
                        if (key !in setOf("status", "reason", "task_id", "result", "error")) {
                            put(key, value)
                        }
                    }
                    put("type", JsonPrimitive(SUB_AGENT_TOOL_NAME))
                    put("status", JsonPrimitive(delivery.status))
                    if (delivery.reason != null) put("reason", JsonPrimitive(delivery.reason))
                    put("task_id", JsonPrimitive(delivery.taskId))
                    put("description", JsonPrimitive(description ?: delivery.taskId))
                }
                // 保留可能存在的非文本部件（子代理目前只产出文本，但别在这里埋雷）
                part.copy(
                    output = listOf(UIMessagePart.Text(rewritten.toString())) +
                        part.output.filter { it !is UIMessagePart.Text },
                )
            }
            if (newParts == message.parts) message else message.copy(parts = newParts)
        }
        if (newMessages == node.messages) node else node.copy(messages = newMessages)
    }

    if (!matched) return null

    val effectiveDescription = description ?: delivery.taskId
    val marker = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(
            UIMessagePart.Text(
                text = markerText(effectiveDescription),
                metadata = buildJsonObject {
                    put(SUB_AGENT_TASK_METADATA_KEY, buildJsonObject {
                        put("taskId", JsonPrimitive(delivery.taskId))
                        put("status", JsonPrimitive(delivery.status))
                        put("reason", delivery.reason?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("description", JsonPrimitive(effectiveDescription))
                        put("result", clipped?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("error", clippedError?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                },
            ),
        ),
    )

    return copy(messageNodes = newNodes + marker.toMessageNode())
}

/**
 * 中断对账的判据：工具结果仍是 `started` **且** registry 里没有对应的存活任务。
 *
 * 一条判据同时覆盖「进程被回收后重启」与「任务在运行中丢失」；改写后 `status` 变终态，
 * 所以这条判据天然幂等。
 */
internal fun List<UIMessage>.interruptedSubAgentTaskIds(isLive: (String) -> Boolean): List<String> {
    val ids = mutableListOf<String>()
    forEach { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>().forEach { part ->
            if (part.toolName != SUB_AGENT_TOOL_NAME) return@forEach
            val json = runCatching { JsonInstant.parseToJsonElement(part.outputText()).jsonObject }.getOrNull() ?: return@forEach
            if (json["status"]?.jsonPrimitive?.contentOrNull != "started") return@forEach
            val taskId = json["task_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (!isLive(taskId)) ids += taskId
        }
    }
    return ids
}
