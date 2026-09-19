package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

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
